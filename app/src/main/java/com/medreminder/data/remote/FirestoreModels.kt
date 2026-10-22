package com.medreminder.data.remote

import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.functions
import com.google.firebase.messaging.messaging
import com.google.firebase.storage.storage
import com.medreminder.data.DEFAULT_TIMEZONE
import com.medreminder.data.Dose
import com.medreminder.data.DoseStatus
import com.medreminder.data.DoseUnit
import com.medreminder.data.Elder
import com.medreminder.data.ElderSettings
import com.medreminder.data.FoodRule
import com.medreminder.data.Language
import com.medreminder.data.Medicine
import java.util.Date

/** Firestore paths and hand-written mapping. Field names match firebase/functions/src/model.ts. */
object Paths {
    const val REGION = "asia-south1"

    fun user(uid: String) = "users/$uid"
    fun family(familyId: String) = "families/$familyId"
    fun elders(familyId: String) = "families/$familyId/elders"
    fun elder(familyId: String, elderId: String) = "families/$familyId/elders/$elderId"
    fun medicines(familyId: String, elderId: String) = "${elder(familyId, elderId)}/medicines"
    fun doses(familyId: String, elderId: String) = "${elder(familyId, elderId)}/doses"
    fun pairingCode(code: String) = "pairingCodes/$code"

    /** A new path per upload, so the elder's phone notices a replaced photo. */
    fun photo(familyId: String, elderId: String, medicineId: String, version: Long) =
        "families/$familyId/elders/$elderId/medicines/${medicineId}_$version.jpg"
}

internal object Fb {
    val auth get() = Firebase.auth
    val db get() = Firebase.firestore
    val functions get() = Firebase.functions(Paths.REGION)
    val storage get() = Firebase.storage
    val messaging get() = Firebase.messaging
}

fun Long.toTimestamp(): Timestamp = Timestamp(Date(this))

private fun DocumentSnapshot.millis(field: String): Long? = getTimestamp(field)?.toDate()?.time

fun DocumentSnapshot.toMedicine(): Medicine = Medicine(
    id = id,
    name = getString("name").orEmpty(),
    spokenName = getString("spokenName")?.takeIf { it.isNotBlank() },
    photoPath = getString("photoPath")?.takeIf { it.isNotBlank() },
    doseAmount = getDouble("doseAmount") ?: 1.0,
    doseUnit = DoseUnit.from(getString("doseUnit")),
    foodRule = FoodRule.from(getString("foodRule")),
    times = (get("times") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    startDate = getString("startDate").orEmpty(),
    endDate = getString("endDate")?.takeIf { it.isNotBlank() },
    tabletsLeft = getLong("tabletsLeft")?.toInt(),
    active = getBoolean("active") ?: true,
)

fun Medicine.toFirestore(): Map<String, Any?> = mapOf(
    "name" to name.trim(),
    "spokenName" to spokenName?.trim().orEmpty(),
    "photoPath" to photoPath,
    "doseAmount" to doseAmount,
    "doseUnit" to doseUnit.wire,
    "foodRule" to foodRule.wire,
    "times" to times.distinct().sorted(),
    "startDate" to startDate,
    "endDate" to endDate,
    "tabletsLeft" to tabletsLeft,
    "active" to active,
)

fun DocumentSnapshot.toElder(): Elder {
    val s = get("settings") as? Map<*, *>
    fun setting(key: String, default: Int) = (s?.get(key) as? Number)?.toInt() ?: default
    val defaults = ElderSettings()
    return Elder(
        id = id,
        name = getString("name").orEmpty(),
        nickname = getString("nickname").orEmpty(),
        language = Language.from(getString("language")),
        timezone = getString("timezone") ?: DEFAULT_TIMEZONE,
        phoneNumber = getString("phoneNumber").orEmpty(),
        callName = getString("callName").orEmpty(),
        callNumber = getString("callNumber").orEmpty(),
        lastCheckInAt = millis("lastCheckInAt"),
        settings = ElderSettings(
            alertAfterMinutes = setting("alertAfterMinutes", defaults.alertAfterMinutes),
            escalateAfterMinutes = setting("escalateAfterMinutes", defaults.escalateAfterMinutes),
            silentAfterHours = setting("silentAfterHours", defaults.silentAfterHours),
            quietStartHour = setting("quietStartHour", defaults.quietStartHour),
            quietEndHour = setting("quietEndHour", defaults.quietEndHour),
        ),
    )
}

fun DocumentSnapshot.toDose(): Dose = Dose(
    id = id,
    medicineId = getString("medicineId").orEmpty(),
    scheduledAt = millis("scheduledAt") ?: 0L,
    localDate = getString("localDate").orEmpty(),
    localTime = getString("localTime").orEmpty(),
    status = DoseStatus.from(getString("status")),
    confirmedAt = millis("confirmedAt"),
    confirmedBy = getString("confirmedBy"),
    alertLevel = getLong("alertLevel")?.toInt() ?: 0,
    acknowledgedBy = getString("acknowledgedBy"),
)

data class FamilyInfo(
    val id: String,
    val name: String,
    val memberUids: List<String>,
    val alertOrder: List<String>,
    val memberNames: Map<String, String>,
)

fun DocumentSnapshot.toFamily(): FamilyInfo {
    val members = (get("members") as? Map<*, *>).orEmpty()
    return FamilyInfo(
        id = id,
        name = getString("name").orEmpty(),
        memberUids = (get("memberUids") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        alertOrder = (get("alertOrder") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        memberNames = members.entries.mapNotNull { (uid, value) ->
            val name = (value as? Map<*, *>)?.get("name") as? String
            if (uid is String && name != null) uid to name else null
        }.toMap(),
    )
}
