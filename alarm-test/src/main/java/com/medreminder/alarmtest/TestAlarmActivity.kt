package com.medreminder.alarmtest

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.window.OnBackInvokedDispatcher
import com.medreminder.alarm.AlarmSession
import com.medreminder.alarm.ReminderSpeaker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The elder's alarm screen, as the real app will show it: picture first, the dose in big type,
 * the reminder spoken aloud, and only two choices. Hand the phone to an elder, let it ring, and
 * watch without helping.
 */
class TestAlarmActivity : Activity() {
    private lateinit var root: LinearLayout
    private var session: AlarmSession? = null
    private val main = Handler(Looper.getMainLooper())
    private var key = ""
    private var base = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        root = column(paddingDp = 20).apply {
            setBackgroundColor(Palette.PAPER)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.padForSystemBars()
        setContentView(root)
        ignoreBack()
        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    override fun onStop() {
        super.onStop()
        // Power button or Home: go quiet. The follow-up alarm rings again in 5 minutes.
        session?.stopSound()
    }

    override fun onDestroy() {
        session?.release()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Nothing to break: Back does not dismiss the reminder.
    }

    private fun bind(intent: Intent) {
        val app = testApp
        key = intent.getStringExtra(EXTRA_KEY) ?: return finish()
        base = intent.getStringExtra(EXTRA_BASE) ?: key
        val hindi = app.settings.hindi

        app.log.takenAt(base)?.let { takenAt ->
            renderAlreadyTaken(takenAt, hindi)
            return
        }

        app.log.update(key) { it.copy(screenShownAt = System.currentTimeMillis()) }
        // The screen takes over the sound from the notification.
        app.notifier.cancel(base.hashCode())
        renderReminder(hindi)

        session?.release()
        session = AlarmSession(this, if (hindi) ReminderSpeaker.HINDI else Locale.ENGLISH).also {
            it.start(
                line = if (hindi) "दवाई का समय हो गया। एक गोली, खाने के बाद।" else ENGLISH_LINE,
                englishFallback = ENGLISH_LINE,
            )
        }
    }

    private fun renderReminder(hindi: Boolean) {
        root.removeAllViews()
        root.setBackgroundColor(Palette.PAPER)
        val (icon, timeOfDay) = timeOfDay(hindi)
        root.text("$icon  $timeOfDay", sizeSp = 28f, bold = true, center = true)
        // In the real app this is the family's photo of the strip.
        root.text("💊", sizeSp = 120f, center = true, topMarginDp = 12)
        root.text(if (hindi) "1 गोली" else "1 tablet", sizeSp = 44f, bold = true, center = true)
        root.text(if (hindi) "🍽️  खाने के बाद" else "🍽️  After food", sizeSp = 30f, center = true, topMarginDp = 4)

        root.button(
            if (hindi) "🔊  फिर से सुनें" else "🔊  Say it again",
            background = Palette.GREY,
            sizeSp = 22f,
            minHeightDp = 64,
            topMarginDp = 24,
        ) { session?.repeatNow() }

        root.button(
            if (hindi) "✓  ले ली" else "✓  Taken",
            background = Palette.GREEN,
            textColor = Color.WHITE,
            sizeSp = 32f,
            minHeightDp = 96,
            topMarginDp = 16,
        ) { onTaken(hindi) }

        root.button(
            if (hindi) "⏰  10 मिनट बाद याद दिलाना" else "⏰  Remind me in 10 minutes",
            background = Palette.GREY,
            sizeSp = 22f,
            minHeightDp = 72,
            topMarginDp = 16,
        ) { onSnooze(hindi) }
    }

    private fun renderAlreadyTaken(takenAt: Long, hindi: Boolean) {
        root.removeAllViews()
        root.setBackgroundColor(Palette.PAPER)
        val time = SimpleDateFormat("h:mm", Locale.US).format(Date(takenAt))
        root.text("✓", sizeSp = 120f, color = Palette.GREEN, center = true, topMarginDp = 40)
        root.text(
            if (hindi) "पहले ही ले ली\n$time बजे" else "Already taken\nat $time",
            sizeSp = 40f,
            bold = true,
            center = true,
        )
        root.button(if (hindi) "ठीक है" else "OK", background = Palette.GREY, sizeSp = 24f, minHeightDp = 72, topMarginDp = 32) {
            finish()
        }
    }

    private fun onTaken(hindi: Boolean) {
        val app = testApp
        session?.stopSound()
        app.log.setTaken(base, System.currentTimeMillis())
        app.cancelPending(base)
        app.notifier.cancel(base.hashCode())
        showDone(if (hindi) "हो गया" else "Done")
    }

    private fun onSnooze(hindi: Boolean) {
        val app = testApp
        session?.stopSound()
        app.cancelPending(base)
        app.log.addSnooze(base)
        val n = app.log.snoozeCount(base)
        app.schedule(
            base = base,
            key = "$base#s$n",
            triggerAt = System.currentTimeMillis() + TestApp.SNOOZE_MILLIS,
            isSnooze = true,
        )
        app.notifier.cancel(base.hashCode())
        showDone(if (hindi) "10 मिनट बाद फिर बजेगा" else "Will ring again in 10 minutes")
    }

    /** Every tap gets a large confirmation, then the screen closes by itself. */
    private fun showDone(message: String) {
        root.removeAllViews()
        root.setBackgroundColor(Palette.GREEN)
        root.text("✓", sizeSp = 140f, color = Color.WHITE, center = true, topMarginDp = 60)
        root.text(message, sizeSp = 40f, bold = true, color = Color.WHITE, center = true)
        main.postDelayed({ finish() }, DONE_MILLIS)
    }

    private fun timeOfDay(hindi: Boolean): Pair<String, String> {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> "☀️" to (if (hindi) "सुबह" else "Morning")
            hour < 17 -> "🌤️" to (if (hindi) "दोपहर" else "Afternoon")
            else -> "🌙" to (if (hindi) "रात" else "Night")
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun ignoreBack() {
        // From Android 16 (target 36) onBackPressed is no longer called; register a callback instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { }
        }
    }

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_BASE = "base"
        private const val ENGLISH_LINE = "It is time for your medicine. One tablet, after food."
        private const val DONE_MILLIS = 2_500L
    }
}
