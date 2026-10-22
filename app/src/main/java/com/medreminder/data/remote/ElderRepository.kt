package com.medreminder.data.remote

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import com.medreminder.BuildConfig
import com.medreminder.data.DoseStatus
import com.medreminder.data.Prefs
import com.medreminder.data.local.AppDatabase
import com.medreminder.data.local.MedicineEntity
import com.medreminder.reminders.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Everything the elder's phone does with the server: pairing, pulling medicines, pushing
 * Taken and snooze, and the check-in that tells the server the phone is alive.
 * The elder never signs in to anything; the phone uses an anonymous account.
 */
class ElderRepository(
    private val context: Context,
    private val prefs: Prefs,
    private val db: AppDatabase,
    private val reminders: ReminderScheduler,
) {
    enum class PullResult { OK, NOT_PAIRED, REMOVED }

    // ---- Pairing ----

    suspend fun ensureSignedIn(): String {
        Fb.auth.currentUser?.let { return it.uid }
        return Fb.auth.signInAnonymously().await().user!!.uid
    }

    /** A 6-digit code the family member scans or types. Valid for 30 minutes. */
    suspend fun createPairingCode(): String {
        ensureSignedIn()
        val result = Fb.functions.getHttpsCallable("createPairingCode").call().await()
        return (result.getData() as Map<*, *>)["code"] as String
    }

    /** Emits the family and elder ids once a family member has claimed [code]. */
    fun observeClaim(code: String): Flow<Pair<String, String>?> =
        Fb.db.document(Paths.pairingCode(code)).snapshots().map { snap ->
            val familyId = snap.getString("familyId")
            val elderId = snap.getString("elderId")
            if (familyId != null && elderId != null) familyId to elderId else null
        }

    suspend fun completePairing(familyId: String, elderId: String) {
        // Pick up the {fid, eid} claims the server just set; the security rules need them.
        Fb.auth.currentUser!!.getIdToken(true).await()
        prefs.familyId = familyId
        prefs.elderId = elderId
        pull()
        runCatching { registerToken() }
        runCatching { checkIn(force = true) }
    }

    // ---- Sync ----

    suspend fun pull(): PullResult = withContext(Dispatchers.IO) {
        val (familyId, elderId) = ids() ?: return@withContext PullResult.NOT_PAIRED
        val elderRef = Fb.db.document(Paths.elder(familyId, elderId))
        val elderSnap = elderRef.get().await()
        if (!elderSnap.exists()) return@withContext PullResult.REMOVED
        prefs.applyElder(elderSnap.toElder())

        val medicines = elderRef.collection("medicines").get().await().documents.map { it.toMedicine() }
        val existing = db.medicines().all().associateBy { it.id }
        db.medicines().replaceAll(medicines.map { m -> MedicineEntity.from(m, localPhoto(m.id, m.photoPath, existing[m.id])) })
        reminders.rescheduleAll()
        PullResult.OK
    }

    /** Sends every Taken or snooze that has not reached the server yet. */
    suspend fun pushUnsynced() {
        db.doses().unsynced().forEach { pushDose(it.id) }
    }

    suspend fun pushDose(doseId: String): Boolean {
        val dose = db.doses().get(doseId) ?: return true
        val (familyId, elderId) = ids() ?: return false
        val data = mutableMapOf<String, Any?>(
            "medicineId" to dose.medicineId,
            "scheduledAt" to dose.scheduledAt.toTimestamp(),
            "localDate" to dose.localDate,
            "localTime" to dose.localTime,
        )
        when (dose.doseStatus) {
            DoseStatus.CONFIRMED -> {
                data["status"] = DoseStatus.CONFIRMED.wire
                data["confirmedBy"] = "elder"
                data["confirmedAt"] = (dose.confirmedAt ?: System.currentTimeMillis()).toTimestamp()
                data["alertAt"] = null
            }
            DoseStatus.SNOOZED -> {
                // alertAt is left alone so the family is still alerted on time.
                data["status"] = DoseStatus.SNOOZED.wire
                data["snoozedAt"] = (dose.snoozedAt ?: System.currentTimeMillis()).toTimestamp()
            }
            else -> return true
        }
        return try {
            // Offline, Firestore queues the write; the timeout only stops us waiting for the server.
            withTimeout(WRITE_TIMEOUT_MILLIS) {
                Fb.db.document("${Paths.doses(familyId, elderId)}/$doseId").set(data, SetOptions.merge()).await()
            }
            db.doses().markSynced(doseId)
            true
        } catch (e: TimeoutCancellationException) {
            false
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                // For example a snooze on a dose the server has not created yet; nothing to retry.
                db.doses().markSynced(doseId)
                true
            } else {
                false
            }
        }
    }

    /**
     * Tells the server this phone is alive, and when to raise "phone is silent" if it goes quiet.
     * At most about once an hour, to stay inside the free Firestore write quota.
     */
    suspend fun checkIn(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.lastCheckInAt < CHECK_IN_EVERY_MILLIS) return
        val (familyId, elderId) = ids() ?: return
        withTimeout(WRITE_TIMEOUT_MILLIS) {
            Fb.db.document(Paths.elder(familyId, elderId)).update(
                mapOf(
                    "lastCheckInAt" to FieldValue.serverTimestamp(),
                    "silentAlertAt" to (now + prefs.silentAfterHours * 3_600_000L).toTimestamp(),
                    "appVersion" to BuildConfig.VERSION_NAME,
                ),
            ).await()
        }
        prefs.lastCheckInAt = now
    }

    suspend fun registerToken(token: String? = null) {
        val (familyId, elderId) = ids() ?: return
        val value = token ?: Fb.messaging.token.await()
        Fb.db.document(Paths.elder(familyId, elderId)).update("fcmToken", value).await()
    }

    // ---- Elder actions ----

    suspend fun confirm(doseId: String) {
        db.doses().confirm(doseId, System.currentTimeMillis())
        reminders.cancelDoseAlarms(doseId)
        if (!pushDose(doseId)) com.medreminder.sync.SyncWorker.runSoon(context)
    }

    suspend fun snooze(doseId: String) {
        val dose = db.doses().get(doseId) ?: return
        val now = System.currentTimeMillis()
        reminders.scheduleSnooze(dose, now)
        db.doses().snooze(doseId, now)
        if (!pushDose(doseId)) com.medreminder.sync.SyncWorker.runSoon(context)
    }

    suspend fun recordConsent() {
        val (familyId, elderId) = ids() ?: return
        Fb.db.document(Paths.elder(familyId, elderId))
            .update("consent", mapOf("acceptedAt" to FieldValue.serverTimestamp(), "version" to CONSENT_VERSION))
            .await()
    }

    /** Consent withdrawn: delete the elder's data on the server, then everything on this phone. */
    suspend fun deleteMyData() {
        val (familyId, elderId) = ids() ?: return wipeLocal()
        Fb.functions.getHttpsCallable("deleteElderData")
            .call(mapOf("familyId" to familyId, "elderId" to elderId))
            .await()
        wipeLocal()
    }

    /** Forget the pairing on this phone only. */
    suspend fun wipeLocal() = withContext(Dispatchers.IO) {
        reminders.cancelAll()
        db.clearAllTables()
        File(context.filesDir, PHOTO_DIR).deleteRecursively()
        prefs.clearElder()
        Fb.auth.signOut()
    }

    private fun ids(): Pair<String, String>? {
        val familyId = prefs.familyId ?: return null
        val elderId = prefs.elderId ?: return null
        return familyId to elderId
    }

    /** Downloads the strip photo once per version, so the alarm shows it offline. */
    private suspend fun localPhoto(medicineId: String, photoPath: String?, old: MedicineEntity?): String? {
        if (photoPath == null) return null
        val cached = old?.localPhotoFile?.takeIf { old.photoPath == photoPath && File(it).exists() }
        if (cached != null) return cached
        return try {
            val bytes = Fb.storage.reference.child(photoPath).getBytes(MAX_PHOTO_BYTES).await()
            val file = File(context.filesDir, "$PHOTO_DIR/$medicineId.jpg")
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Exception) {
            old?.localPhotoFile
        }
    }

    private companion object {
        const val WRITE_TIMEOUT_MILLIS = 15_000L
        const val CHECK_IN_EVERY_MILLIS = 55 * 60_000L
        const val MAX_PHOTO_BYTES = 1024L * 1024L
        const val PHOTO_DIR = "photos"
        const val CONSENT_VERSION = 1
    }
}
