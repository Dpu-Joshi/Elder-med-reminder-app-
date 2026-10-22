package com.medreminder.alarmtest

import android.content.Context
import android.os.Build
import com.medreminder.alarm.ReliabilityChecks
import kotlin.math.abs

/** Plain-text summary to share over WhatsApp or email after the overnight test. */
internal object Report {

    enum class Outcome { ON_TIME, LATE, NOT_FIRED, WAITING, CANCELLED }

    fun outcome(entry: TestLog.Entry, now: Long = System.currentTimeMillis()): Outcome = when {
        entry.firedAt != null ->
            if (abs(entry.firedAt - entry.scheduledAt) <= ON_TIME_MILLIS) Outcome.ON_TIME else Outcome.LATE
        entry.cancelled -> Outcome.CANCELLED
        entry.scheduledAt < now - GRACE_MILLIS -> Outcome.NOT_FIRED
        else -> Outcome.WAITING
    }

    fun summary(entries: List<TestLog.Entry>): String {
        val counts = entries.groupingBy { outcome(it) }.eachCount()
        return "${entries.size} alarms · ${counts[Outcome.ON_TIME] ?: 0} on time · " +
            "${counts[Outcome.LATE] ?: 0} late · ${counts[Outcome.NOT_FIRED] ?: 0} did not ring · " +
            "${counts[Outcome.WAITING] ?: 0} waiting"
    }

    fun line(entry: TestLog.Entry, log: TestLog): String {
        val sb = StringBuilder(TestApp.clock(entry.scheduledAt))
        if (entry.attempt > 1) sb.append(" [repeat ${entry.attempt}]")
        if (entry.isSnooze) sb.append(" [snooze]")
        when (outcome(entry)) {
            Outcome.ON_TIME, Outcome.LATE -> {
                val delay = (entry.firedAt!! - entry.scheduledAt) / 1000
                sb.append("  rang ").append(TestApp.clock(entry.firedAt)).append(" (")
                    .append(if (delay >= 0) "+" else "").append(formatSeconds(delay)).append(")")
            }
            Outcome.NOT_FIRED -> sb.append("  DID NOT RING")
            Outcome.WAITING -> sb.append("  waiting")
            Outcome.CANCELLED -> sb.append("  cancelled")
        }
        sb.append("  ").append(entry.usedMode.name)
        if (entry.usedMode != entry.requestedMode) sb.append(" (wanted ${entry.requestedMode.name})")
        if (entry.screenShownAt != null) sb.append("  screen shown")
        if (entry.attempt == 1 && !entry.isSnooze) {
            log.takenAt(entry.base)?.let { sb.append("  taken ").append(TestApp.clock(it)) }
        }
        return sb.toString()
    }

    fun build(context: Context, hindiVoice: Boolean?): String {
        val app = context.testApp
        val entries = app.log.entries()
        val sb = StringBuilder()
        sb.appendLine("Alarm test report")
        sb.appendLine("Phone: ${deviceLine()}")
        sb.appendLine("Made: ${TestApp.clock(System.currentTimeMillis())}")
        sb.appendLine("Alarm type: ${app.settings.mode.name} · repeat if not tapped: ${if (app.settings.repeatIfNotTapped) "on" else "off"}")
        sb.appendLine("Hindi voice: ${when (hindiVoice) { true -> "installed"; false -> "MISSING"; null -> "unknown" }}")
        sb.appendLine()
        sb.appendLine("Phone settings:")
        ReliabilityChecks.run(context).forEach {
            val mark = when (it.ok) { true -> "OK "; false -> "NO "; null -> "?? " }
            sb.appendLine("  $mark ${Labels.check(it.id)}")
        }
        sb.appendLine()
        sb.appendLine("Results: ${summary(entries)}")
        entries.forEach { sb.appendLine(line(it, app.log)) }
        val events = app.log.events()
        if (events.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Events:")
            events.forEach { sb.appendLine("  $it") }
        }
        return sb.toString()
    }

    fun deviceLine(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    private fun formatSeconds(seconds: Long): String =
        if (abs(seconds) < 120) "$seconds s" else "${seconds / 60} min ${abs(seconds % 60)} s"

    private const val ON_TIME_MILLIS = 60_000L
    private const val GRACE_MILLIS = 2 * 60_000L
}

internal object Labels {
    fun check(id: ReliabilityChecks.CheckId): String = when (id) {
        ReliabilityChecks.CheckId.NOTIFICATIONS -> "Notifications allowed"
        ReliabilityChecks.CheckId.ALARM_CHANNEL -> "Alarm notification can pop on screen"
        ReliabilityChecks.CheckId.EXACT_ALARMS -> "Alarms & reminders (exact alarms) allowed"
        ReliabilityChecks.CheckId.FULL_SCREEN -> "Full-screen reminders allowed"
        ReliabilityChecks.CheckId.BACKGROUND_RESTRICTED -> "Background activity not restricted"
        ReliabilityChecks.CheckId.BATTERY_OPTIMIZATION -> "Battery: unrestricted"
        ReliabilityChecks.CheckId.ALARM_VOLUME -> "Alarm volume above zero"
        ReliabilityChecks.CheckId.OEM_AUTOSTART -> "Autostart on (check by hand)"
        ReliabilityChecks.CheckId.OEM_BATTERY -> "Brand battery saver: no restrictions (check by hand)"
        ReliabilityChecks.CheckId.OEM_LOCK_SCREEN_POPUP -> "Show on lock screen + pop-up windows (check by hand)"
    }
}
