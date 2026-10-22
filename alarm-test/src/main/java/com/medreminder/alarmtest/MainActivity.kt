package com.medreminder.alarmtest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import com.medreminder.alarm.AlarmMode
import com.medreminder.alarm.ReliabilityChecks
import com.medreminder.alarm.ReminderSpeaker

/**
 * The tester's screen: what this phone allows, buttons to fix it, buttons to schedule test
 * alarms, and the result of every alarm so far.
 */
class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private var speaker: ReminderSpeaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = column()
        val scroll = ScrollView(this).apply { addView(content) }
        scroll.padForSystemBars()
        setContentView(scroll)

        speaker = ReminderSpeaker(this, ReminderSpeaker.HINDI) { runOnUiThread { render() } }

        if (!ReliabilityChecks.notificationsGranted(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun render() {
        val app = testApp
        content.removeAllViews()

        content.text(Report.deviceLine(), sizeSp = 14f, color = Palette.INK)

        content.heading("1. Phone settings")
        content.text("Fix every red line. Lines marked ? cannot be read by the app: open them and check by hand.", sizeSp = 14f)
        ReliabilityChecks.run(this).forEach { result ->
            val (mark, color) = when (result.ok) {
                true -> "✔" to Palette.GREEN
                false -> "✘" to Palette.RED
                null -> "?" to Palette.AMBER
            }
            content.text("$mark  ${Labels.check(result.id)}", color = color, topMarginDp = 10)
            if (result.ok != true) {
                content.button(if (result.ok == null) "Open setting" else "Fix", topMarginDp = 2) {
                    if (result.id == ReliabilityChecks.CheckId.NOTIFICATIONS &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                    ) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
                    } else if (!ReliabilityChecks.openFix(this, result)) {
                        toast("This phone has no such screen")
                    }
                }
            }
        }
        val voice = speaker?.hasPrimaryVoice
        val voiceText = when (voice) {
            true -> "✔  Hindi voice installed"
            false -> "✘  Hindi voice missing: reminders will speak English"
            null -> "…  Checking Hindi voice"
        }
        content.text(voiceText, color = if (voice == false) Palette.RED else Palette.GREEN, topMarginDp = 10)
        if (voice == false) {
            content.button("Install Hindi voice", topMarginDp = 2) {
                ReliabilityChecks.launchFirst(
                    this,
                    listOf(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA), Intent("com.android.settings.TTS_SETTINGS")),
                )
            }
        }

        content.heading("2. How to ring")
        val group = RadioGroup(this)
        AlarmMode.entries.filter { it != AlarmMode.INEXACT_WHILE_IDLE }.forEach { mode ->
            group.addView(
                RadioButton(this).apply {
                    id = mode.ordinal + 1
                    text = when (mode) {
                        AlarmMode.ALARM_CLOCK -> "Alarm clock (setAlarmClock) — the real app's choice"
                        AlarmMode.EXACT_WHILE_IDLE -> "Exact while idle (setExactAndAllowWhileIdle)"
                        AlarmMode.INEXACT_WHILE_IDLE -> ""
                    }
                    isChecked = app.settings.mode == mode
                },
            )
        }
        group.setOnCheckedChangeListener { _, checkedId -> app.settings.mode = AlarmMode.entries[checkedId - 1] }
        content.addView(group)
        content.addView(
            CheckBox(this).apply {
                text = "Ring again after 5 and 10 minutes if nobody taps"
                isChecked = app.settings.repeatIfNotTapped
                setOnCheckedChangeListener { _, checked -> app.settings.repeatIfNotTapped = checked }
            },
        )
        content.addView(
            CheckBox(this).apply {
                text = "Speak in Hindi (untick for English)"
                isChecked = app.settings.hindi
                setOnCheckedChangeListener { _, checked -> app.settings.hindi = checked }
            },
        )

        content.heading("3. Test")
        content.text(
            "Lock the phone after scheduling. For the overnight test, also swipe the app away from recent apps, " +
                "leave the phone locked and on battery, and read the results in the morning.",
            sizeSp = 14f,
        )
        content.button("Ring in 1 minute") {
            app.schedule(triggerAt = System.currentTimeMillis() + 60_000L)
            toast("Rings in 1 minute — lock the phone now")
            render()
        }
        content.button("Ring every 30 minutes until 7 am") {
            toast("${app.scheduleOvernight(30)} alarms scheduled")
            render()
        }
        content.button("Ring every 60 minutes until 7 am") {
            toast("${app.scheduleOvernight(60)} alarms scheduled")
            render()
        }
        content.button("Cancel alarms that have not rung") {
            app.cancelPending()
            render()
        }

        content.heading("4. Results")
        val entries = app.log.entries()
        content.text(Report.summary(entries), bold = true)
        entries.asReversed().take(MAX_ROWS).forEach { entry ->
            val color = when (Report.outcome(entry)) {
                Report.Outcome.ON_TIME -> Palette.GREEN
                Report.Outcome.LATE -> Palette.AMBER
                Report.Outcome.NOT_FIRED -> Palette.RED
                else -> Palette.INK
            }
            content.text(Report.line(entry, app.log), sizeSp = 13f, color = color, topMarginDp = 6)
        }
        app.log.events().takeLast(5).forEach { content.text(it, sizeSp = 12f, topMarginDp = 4) }
        content.divider()
        content.button("Share report") {
            val text = Report.build(this, speaker?.hasPrimaryVoice)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(send, "Share alarm report"))
        }
        content.button("Clear results") {
            app.cancelPending()
            app.log.clear()
            render()
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
        const val MAX_ROWS = 60
    }
}
