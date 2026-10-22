package com.medreminder.data

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Small settings kept on this phone. Medicines and doses live in Room (elder) or Firestore (family). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("medreminder", Context.MODE_PRIVATE)

    enum class Mode { ELDER, FAMILY }

    /** Chosen once by the person who installs the app. */
    var mode: Mode?
        get() = sp.getString("mode", null)?.let { name -> Mode.entries.firstOrNull { it.name == name } }
        set(value) = sp.edit { putString("mode", value?.name) }

    // ---- Elder mode ----

    var familyId: String?
        get() = sp.getString("familyId", null)
        set(value) = sp.edit { putString("familyId", value) }

    var elderId: String?
        get() = sp.getString("elderId", null)
        set(value) = sp.edit { putString("elderId", value) }

    val isPaired: Boolean get() = familyId != null && elderId != null

    var language: Language
        get() = Language.from(sp.getString("language", null))
        set(value) = sp.edit { putString("language", value.wire) }

    var nickname: String
        get() = sp.getString("nickname", "") ?: ""
        set(value) = sp.edit { putString("nickname", value) }

    var callName: String
        get() = sp.getString("callName", "") ?: ""
        set(value) = sp.edit { putString("callName", value) }

    var callNumber: String
        get() = sp.getString("callNumber", "") ?: ""
        set(value) = sp.edit { putString("callNumber", value) }

    var timezone: String
        get() = sp.getString("timezone", DEFAULT_TIMEZONE) ?: DEFAULT_TIMEZONE
        set(value) = sp.edit { putString("timezone", value) }

    var alertAfterMinutes: Int
        get() = sp.getInt("alertAfterMinutes", 30)
        set(value) = sp.edit { putInt("alertAfterMinutes", value) }

    var silentAfterHours: Int
        get() = sp.getInt("silentAfterHours", 8)
        set(value) = sp.edit { putInt("silentAfterHours", value) }

    /** Progress through the setup wizard, so a restart resumes where it stopped. */
    var setupStep: Int
        get() = sp.getInt("setupStep", 0)
        set(value) = sp.edit { putInt("setupStep", value) }

    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(value) = sp.edit { putBoolean("setupDone", value) }

    var lastCheckInAt: Long
        get() = sp.getLong("lastCheckInAt", 0L)
        set(value) = sp.edit { putLong("lastCheckInAt", value) }

    /** False when the last scheduling pass had to fall back to inexact alarms. */
    var exactAlarmsWorking: Boolean
        get() = sp.getBoolean("exactAlarmsWorking", true)
        set(value) = sp.edit { putBoolean("exactAlarmsWorking", value) }

    fun applyElder(elder: Elder) = sp.edit {
        putString("language", elder.language.wire)
        putString("nickname", elder.nickname)
        putString("callName", elder.callName)
        putString("callNumber", elder.callNumber)
        putString("timezone", elder.timezone)
        putInt("alertAfterMinutes", elder.settings.alertAfterMinutes)
        putInt("silentAfterHours", elder.settings.silentAfterHours)
    }

    /** Settings on the elder's phone sit behind a PIN the family sets during setup. */
    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit {
            putString("pinSalt", Base64.getEncoder().encodeToString(salt))
            putString("pinHash", hash(salt, pin))
        }
    }

    val hasPin: Boolean get() = sp.getString("pinHash", null) != null

    fun checkPin(pin: String): Boolean {
        val salt = sp.getString("pinSalt", null)?.let { Base64.getDecoder().decode(it) } ?: return false
        return hash(salt, pin) == sp.getString("pinHash", null)
    }

    /** Forget the pairing on this phone. Server data is removed separately (deleteElderData). */
    fun clearElder() = sp.edit {
        listOf(
            "familyId", "elderId", "nickname", "callName", "callNumber", "setupStep", "setupDone",
            "lastCheckInAt", "pinSalt", "pinHash", "exactAlarmsWorking",
        ).forEach(::remove)
    }

    fun clearAll() = sp.edit { clear() }

    private fun hash(salt: ByteArray, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }
}
