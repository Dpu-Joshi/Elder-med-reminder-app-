package com.medreminder.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * Turns a medicine's schedule into dated doses, exactly as the server's planner.ts does, so the
 * elder's phone and the server agree on dose ids even when the phone confirmed offline.
 * DosePlannerTest checks both against shared/dose-plan-vectors.json.
 */
object DosePlanner {

    data class Schedule(
        val times: List<String>,
        val startDate: String,
        val endDate: String?,
        val active: Boolean,
    )

    data class Planned(
        val id: String,
        val medicineId: String,
        val localDate: String,
        val localTime: String,
        val scheduledAtMillis: Long,
    )

    private val TIME = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    fun doseId(medicineId: String, localDate: String, localTime: String): String =
        "${medicineId}_${localDate.replace("-", "")}_${localTime.replace(":", "")}"

    /** The medicine id a dose id or alarm key starts with. */
    fun medicineIdOf(doseId: String): String = doseId.substringBeforeLast('_').substringBeforeLast('_')

    /** Doses on every local date from [from] to [to] inclusive. */
    fun plan(medicineId: String, schedule: Schedule, zone: ZoneId, from: LocalDate, to: LocalDate): List<Planned> {
        if (!schedule.active) return emptyList()
        val start = parseDate(schedule.startDate) ?: return emptyList()
        val end = schedule.endDate?.let(::parseDate)
        val times = schedule.times.filter { TIME.matches(it) }.distinct().sorted()

        val doses = mutableListOf<Planned>()
        var date = from
        while (!date.isAfter(to)) {
            if (!date.isBefore(start) && (end == null || !date.isAfter(end))) {
                val iso = date.toString()
                for (time in times) {
                    // In a daylight-saving gap the time moves forward; in an overlap the earlier
                    // instant is used. Luxon on the server does the same.
                    val at = ZonedDateTime.of(date, LocalTime.parse(time), zone)
                    doses += Planned(doseId(medicineId, iso, time), medicineId, iso, time, at.toInstant().toEpochMilli())
                }
            }
            date = date.plusDays(1)
        }
        return doses
    }

    private fun parseDate(value: String): LocalDate? =
        try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            null
        }
}
