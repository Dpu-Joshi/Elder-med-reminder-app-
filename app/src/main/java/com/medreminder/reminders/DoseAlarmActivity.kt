package com.medreminder.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.medreminder.alarm.AlarmSession
import com.medreminder.alarm.ReminderSpeaker
import com.medreminder.data.Language
import com.medreminder.data.Medicine
import com.medreminder.data.ReminderText
import com.medreminder.data.TimeOfDay
import com.medreminder.data.local.DoseEntity
import com.medreminder.graph
import com.medreminder.ui.common.BigButton
import com.medreminder.ui.common.MedicinePhoto
import com.medreminder.ui.common.clockLabel
import com.medreminder.ui.theme.ElderTheme
import com.medreminder.ui.theme.Green
import com.medreminder.ui.theme.Grey
import com.medreminder.ui.theme.Ink
import com.medreminder.ui.theme.Muted
import com.medreminder.ui.theme.Paper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale

/**
 * The elder's alarm screen, over the lock screen: the photo of the strip, the dose in big
 * type, the reminder spoken aloud, and two choices. When several doses are due together they
 * come one after another.
 */
class DoseAlarmActivity : ComponentActivity() {

    private sealed interface UiState {
        data object Loading : UiState
        data class Ringing(val dose: DoseEntity?, val medicine: Medicine?, val photo: String?, val position: Int, val total: Int) : UiState
        data class AlreadyTaken(val clock: String) : UiState
        data class Done(val message: String) : UiState
    }

    private var state by mutableStateOf<UiState>(UiState.Loading)
    private var session: AlarmSession? = null
    private val queue = ArrayDeque<DoseEntity>()
    private var isTest = false
    private var isManual = false

    private val lang: Language get() = graph.prefs.language

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContent {
            ElderTheme {
                // Nothing to break: Back does not dismiss a ringing reminder.
                BackHandler { if (state !is UiState.Ringing) finish() }
                AlarmContent(state)
            }
        }
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    override fun onStop() {
        super.onStop()
        // Power button or Home: go quiet; the repeat alarm rings again in 5 minutes.
        session?.stopSound()
    }

    override fun onDestroy() {
        session?.release()
        super.onDestroy()
    }

    private fun load(intent: Intent) {
        isTest = intent.getBooleanExtra(EXTRA_TEST, false)
        isManual = intent.getBooleanExtra(EXTRA_MANUAL, false)
        val requestedId = intent.getStringExtra(EXTRA_DOSE_ID)
        lifecycleScope.launch {
            if (isTest) {
                graph.alarmNotifier.cancel(DoseAlarmHandler.TEST_NOTIFICATION_ID)
                state = UiState.Ringing(null, null, null, 1, 1)
                startSound(testSpeech(), "This is a test. Your medicine reminders will sound like this.")
                return@launch
            }
            val doses = graph.db.doses()
            val requested = requestedId?.let { doses.get(it) }
            if (requested != null && requested.doseStatus.isConfirmed) {
                val zone = runCatching { ZoneId.of(graph.prefs.timezone) }.getOrDefault(ZoneId.systemDefault())
                state = UiState.AlreadyTaken(clockLabel(requested.confirmedAt ?: System.currentTimeMillis(), zone))
                return@launch
            }
            val now = System.currentTimeMillis()
            val open = doses.open(now - OPEN_WINDOW_MILLIS, now + 60_000L)
            queue.clear()
            queue.addAll((listOfNotNull(requested) + open).distinctBy { it.id })
            if (queue.isEmpty()) finish() else showNext(queue.size)
        }
    }

    private suspend fun showNext(total: Int) {
        val dose = queue.firstOrNull() ?: return
        val entity = graph.db.medicines().get(dose.medicineId)
        val medicine = entity?.toMedicine()
        graph.alarmNotifier.cancel(ReminderScheduler.notificationId(dose.id))
        state = UiState.Ringing(dose, medicine, entity?.localPhotoFile, total - queue.size + 1, total)
        if (!isManual && medicine != null) {
            startSound(
                ReminderText.speech(lang, graph.prefs.nickname, medicine),
                ReminderText.englishSpeech(graph.prefs.nickname, medicine),
            )
        }
    }

    private fun startSound(line: String, english: String) {
        session?.release()
        session = AlarmSession(this, if (lang == Language.HINDI) ReminderSpeaker.HINDI else Locale.ENGLISH).also {
            it.start(line, english)
        }
    }

    private fun onTaken() {
        session?.stopSound()
        val current = state as? UiState.Ringing ?: return
        lifecycleScope.launch {
            current.dose?.let { graph.elder.confirm(it.id) }
            advance(current.total, ReminderText.done(lang))
        }
    }

    private fun onSnooze() {
        session?.stopSound()
        val current = state as? UiState.Ringing ?: return
        lifecycleScope.launch {
            current.dose?.let { graph.elder.snooze(it.id) }
            advance(current.total, ReminderText.willRingAgain(lang))
        }
    }

    private suspend fun advance(total: Int, doneMessage: String) {
        queue.removeFirstOrNull()
        if (queue.isNotEmpty()) {
            showNext(total)
            return
        }
        state = UiState.Done(doneMessage)
        delay(DONE_MILLIS)
        finish()
    }

    private fun testSpeech() =
        if (lang == Language.HINDI) "यह टेस्ट है। दवाई का समय होने पर फ़ोन ऐसे ही बजेगा।" else "This is a test. Your medicine reminders will sound like this."

    @Composable
    private fun AlarmContent(s: UiState) {
        when (s) {
            UiState.Loading -> Column(Modifier.fillMaxSize().background(Paper)) {}
            is UiState.Ringing -> RingingScreen(s)
            is UiState.AlreadyTaken -> Column(
                Modifier.fillMaxSize().background(Paper).safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Text("✓", fontSize = 120.sp, color = Green)
                Text(ReminderText.alreadyTaken(lang, s.clock), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                BigButton(if (lang == Language.HINDI) "ठीक है" else "OK", onClick = { finish() }, container = Grey, content = Ink)
            }
            is UiState.Done -> Column(
                Modifier.fillMaxSize().background(Green).safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("✓", fontSize = 140.sp, color = Color.White)
                Text(s.message, style = MaterialTheme.typography.headlineLarge, color = Color.White, textAlign = TextAlign.Center)
            }
        }
    }

    @Composable
    private fun RingingScreen(s: UiState.Ringing) {
        val medicine = s.medicine
        val part = TimeOfDay.of(s.dose?.localTime ?: "08:00")
        Column(
            Modifier
                .fillMaxSize()
                .background(Paper)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${ReminderText.timeOfDayIcon(part)}  ${ReminderText.timeOfDayLabel(lang, part)}",
                style = MaterialTheme.typography.headlineMedium,
            )
            if (s.total > 1) Text("${s.position} / ${s.total}", style = MaterialTheme.typography.titleMedium, color = Muted)
            MedicinePhoto(s.photo, size = 220.dp)
            if (medicine != null) {
                Text(
                    ReminderText.doseLabel(lang, medicine.doseAmount, medicine.doseUnit),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                )
                ReminderText.foodLabel(lang, medicine.foodRule)?.let {
                    Text("🍽️  $it", fontSize = 30.sp)
                }
                Text(medicine.name, style = MaterialTheme.typography.bodyMedium, color = Muted)
            } else {
                Text(if (lang == Language.HINDI) "टेस्ट अलार्म" else "Test alarm", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            if (!isManual) {
                BigButton("🔊  ${ReminderText.sayAgain(lang)}", onClick = { session?.repeatNow() }, container = Grey, content = Ink, minHeight = 64.dp)
            }
            BigButton("✓  ${ReminderText.taken(lang)}", onClick = ::onTaken, minHeight = 96.dp)
            if (!isTest) {
                BigButton("⏰  ${ReminderText.remindLater(lang)}", onClick = ::onSnooze, container = Grey, content = Ink)
            }
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

    companion object {
        private const val EXTRA_DOSE_ID = "doseId"
        private const val EXTRA_TEST = "test"
        private const val EXTRA_MANUAL = "manual"
        private const val OPEN_WINDOW_MILLIS = 3 * 60 * 60_000L
        private const val DONE_MILLIS = 2_500L

        fun pendingIntent(context: Context, doseId: String): PendingIntent = PendingIntent.getActivity(
            context,
            doseId.hashCode(),
            Intent(context, DoseAlarmActivity::class.java)
                .putExtra(EXTRA_DOSE_ID, doseId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun testIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            DoseAlarmHandler.TEST_NOTIFICATION_ID,
            Intent(context, DoseAlarmActivity::class.java)
                .putExtra(EXTRA_TEST, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** From the Today screen: same screen, no ringing. */
        fun openManual(context: Context, doseId: String) {
            context.startActivity(
                Intent(context, DoseAlarmActivity::class.java)
                    .putExtra(EXTRA_DOSE_ID, doseId)
                    .putExtra(EXTRA_MANUAL, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
