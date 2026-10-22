package com.medreminder.data

// Plain Kotlin models shared by both modes. Field names on the wire match
// firebase/functions/src/model.ts.

enum class Language(val wire: String) {
    HINDI("hi"),
    ENGLISH("en"),
    ;

    companion object {
        fun from(wire: String?): Language = entries.firstOrNull { it.wire == wire } ?: HINDI
    }
}

enum class FoodRule(val wire: String) {
    BEFORE("before"),
    AFTER("after"),
    ANY("any"),
    ;

    companion object {
        fun from(wire: String?): FoodRule = entries.firstOrNull { it.wire == wire } ?: ANY
    }
}

enum class DoseUnit(val wire: String) {
    TABLET("tablet"),
    CAPSULE("capsule"),
    SPOON("spoon"),
    DROPS("drops"),
    PUFF("puff"),
    INJECTION("injection"),
    ML("ml"),
    ;

    companion object {
        fun from(wire: String?): DoseUnit = entries.firstOrNull { it.wire == wire } ?: TABLET
    }
}

enum class DoseStatus(val wire: String) {
    PENDING("pending"),
    SNOOZED("snoozed"),
    CONFIRMED("confirmed"),
    NOT_CONFIRMED("not_confirmed"),
    CONFIRMED_BY_FAMILY("confirmed_by_family"),
    ;

    val isConfirmed: Boolean get() = this == CONFIRMED || this == CONFIRMED_BY_FAMILY

    companion object {
        fun from(wire: String?): DoseStatus = entries.firstOrNull { it.wire == wire } ?: PENDING
    }
}

enum class TimeOfDay { MORNING, AFTERNOON, NIGHT;

    companion object {
        fun of(localTime: String): TimeOfDay {
            val hour = localTime.substringBefore(':').toIntOrNull() ?: 0
            return when {
                hour < 12 -> MORNING
                hour < 17 -> AFTERNOON
                else -> NIGHT
            }
        }
    }
}

data class Medicine(
    val id: String,
    val name: String,
    /** What the family calls it out loud, for example "BP की गोली". */
    val spokenName: String? = null,
    /** Cloud Storage path of the photo of the strip. */
    val photoPath: String? = null,
    val doseAmount: Double = 1.0,
    val doseUnit: DoseUnit = DoseUnit.TABLET,
    val foodRule: FoodRule = FoodRule.AFTER,
    /** Local "HH:mm" times. */
    val times: List<String> = emptyList(),
    /** Local "yyyy-MM-dd", inclusive. */
    val startDate: String,
    val endDate: String? = null,
    val tabletsLeft: Int? = null,
    val active: Boolean = true,
) {
    val schedule: DosePlanner.Schedule get() = DosePlanner.Schedule(times, startDate, endDate, active)
}

data class ElderSettings(
    val alertAfterMinutes: Int = 30,
    val escalateAfterMinutes: Int = 60,
    val silentAfterHours: Int = 8,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 7,
)

data class Elder(
    val id: String,
    val name: String,
    val nickname: String = "",
    val language: Language = Language.HINDI,
    val timezone: String = DEFAULT_TIMEZONE,
    /** The elder's own number, for the family's Call button. */
    val phoneNumber: String = "",
    /** Who the elder's "Call" button rings, for example "Rahul". */
    val callName: String = "",
    val callNumber: String = "",
    val lastCheckInAt: Long? = null,
    val settings: ElderSettings = ElderSettings(),
)

data class Dose(
    val id: String,
    val medicineId: String,
    val scheduledAt: Long,
    val localDate: String,
    val localTime: String,
    val status: DoseStatus,
    val confirmedAt: Long? = null,
    /** "elder" or the uid of the family member who checked. */
    val confirmedBy: String? = null,
    val alertLevel: Int = 0,
    val acknowledgedBy: String? = null,
)

const val DEFAULT_TIMEZONE = "Asia/Kolkata"
