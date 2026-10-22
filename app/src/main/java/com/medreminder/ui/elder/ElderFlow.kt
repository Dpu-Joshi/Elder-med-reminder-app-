package com.medreminder.ui.elder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medreminder.AppGraph
import com.medreminder.alarm.ReliabilityChecks
import com.medreminder.data.Language
import com.medreminder.data.ReminderText
import com.medreminder.data.TimeOfDay
import com.medreminder.data.local.DoseEntity
import com.medreminder.data.local.MedicineEntity
import com.medreminder.reminders.DoseAlarmActivity
import com.medreminder.sync.SyncWorker
import com.medreminder.ui.common.BigButton
import com.medreminder.ui.common.MedicinePhoto
import com.medreminder.ui.common.clockLabel
import com.medreminder.ui.common.dial
import com.medreminder.ui.theme.Amber
import com.medreminder.ui.theme.Blue
import com.medreminder.ui.theme.ElderTheme
import com.medreminder.ui.theme.Green
import com.medreminder.ui.theme.Grey
import com.medreminder.ui.theme.Ink
import com.medreminder.ui.theme.Muted
import com.medreminder.ui.theme.Paper
import com.medreminder.ui.theme.Red
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private enum class ElderScreen { HOME, PRIVACY, PIN, SETTINGS }

@Composable
fun ElderFlow(graph: AppGraph, onModeReset: () -> Unit) {
    var setupDone by remember { mutableStateOf(graph.prefs.setupDone) }
    var screen by remember { mutableStateOf(ElderScreen.HOME) }
    val unpaired = {
        setupDone = false
        screen = ElderScreen.HOME
        onModeReset()
    }
    ElderTheme {
        BackHandler(enabled = setupDone && screen != ElderScreen.HOME) { screen = ElderScreen.HOME }
        if (!setupDone) {
            ElderSetupScreen(graph, onDone = { setupDone = true }, onCancel = unpaired)
        } else when (screen) {
            ElderScreen.HOME -> ElderHomeScreen(
                graph,
                onPrivacy = { screen = ElderScreen.PRIVACY },
                onSettings = { screen = ElderScreen.PIN },
            )
            ElderScreen.PRIVACY -> ElderPrivacyScreen(graph, onBack = { screen = ElderScreen.HOME }, onDeleted = unpaired)
            ElderScreen.PIN -> PinGate(graph, onOpen = { screen = ElderScreen.SETTINGS }, onBack = { screen = ElderScreen.HOME })
            ElderScreen.SETTINGS -> ElderSettingsScreen(graph, onBack = { screen = ElderScreen.HOME }, onUnpaired = unpaired)
        }
    }
}

/** The Today screen: morning, afternoon and night, each dose with a tick when done. */
@Composable
private fun ElderHomeScreen(graph: AppGraph, onPrivacy: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = graph.prefs.language
    val zone = remember { runCatching { ZoneId.of(graph.prefs.timezone) }.getOrDefault(ZoneId.systemDefault()) }
    var today by remember { mutableStateOf(LocalDate.now(zone).toString()) }
    var phoneOk by remember { mutableStateOf(true) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        today = LocalDate.now(zone).toString()
        phoneOk = graph.prefs.exactAlarmsWorking &&
            ReliabilityChecks.run(context).filter { it.critical && it.ok == false }.isEmpty()
        scope.launch { runCatching { graph.reminders.rescheduleAll() } }
        SyncWorker.runSoon(context)
    }
    val doses by remember(today) { graph.db.doses().observeDay(today) }.collectAsStateWithLifecycle(emptyList())
    val medicines by remember { graph.db.medicines().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val byId = medicines.associateBy { it.id }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(ReminderText.today(lang), style = MaterialTheme.typography.headlineLarge)
        if (!phoneOk) {
            Card(colors = CardDefaults.cardColors(containerColor = Amber.copy(alpha = 0.15f))) {
                Text(
                    if (lang == Language.HINDI) "⚠️ फ़ोन की सेटिंग ठीक करनी है। परिवार को दिखाएं।" else "⚠️ Phone settings need fixing. Show this to your family.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        val visible = doses.filter { byId[it.medicineId] != null }
        if (visible.isEmpty()) Text(ReminderText.nothingToday(lang), style = MaterialTheme.typography.bodyLarge, color = Muted)
        TimeOfDay.entries.forEach { part ->
            val items = visible.filter { TimeOfDay.of(it.localTime) == part }
            if (items.isNotEmpty()) {
                Text(
                    "${ReminderText.timeOfDayIcon(part)}  ${ReminderText.timeOfDayLabel(lang, part)}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                items.forEach { dose ->
                    DoseRow(dose, byId.getValue(dose.medicineId), lang, zone) { DoseAlarmActivity.openManual(context, dose.id) }
                }
            }
        }

        val callNumber = graph.prefs.callNumber
        if (callNumber.isNotBlank()) {
            val who = graph.prefs.callName.ifBlank { if (lang == Language.HINDI) "परिवार" else "family" }
            BigButton("📞  ${ReminderText.call(lang, who)}", onClick = { dial(context, callNumber) }, container = Blue, minHeight = 80.dp)
        }
        TextButton(onClick = onPrivacy, modifier = Modifier.fillMaxWidth()) {
            Text(ReminderText.privacy(lang), style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text(if (lang == Language.HINDI) "परिवार के लिए सेटिंग" else "Family settings", style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
    }
}

@Composable
private fun DoseRow(dose: DoseEntity, medicine: MedicineEntity, lang: Language, zone: ZoneId, onClick: () -> Unit) {
    val m = medicine.toMedicine()
    val taken = dose.doseStatus.isConfirmed
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (taken) Green.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MedicinePhoto(medicine.localPhotoFile, size = 80.dp)
            Column(Modifier.weight(1f)) {
                Text(ReminderText.doseLabel(lang, m.doseAmount, m.doseUnit), style = MaterialTheme.typography.titleLarge)
                ReminderText.foodLabel(lang, m.foodRule)?.let { Text("🍽️ $it", style = MaterialTheme.typography.bodyMedium) }
                Text(clockLabel(dose.localTime), style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            when {
                taken -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✓", fontSize = 40.sp, color = Green)
                    dose.confirmedAt?.let { Text(clockLabel(it, zone), style = MaterialTheme.typography.bodySmall, color = Green) }
                }
                dose.status == "snoozed" -> Text("⏰", fontSize = 34.sp)
            }
        }
    }
}

/** Withdrawing consent is as easy as giving it, and does not need the family PIN. */
@Composable
private fun ElderPrivacyScreen(graph: AppGraph, onBack: () -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lang = graph.prefs.language
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(Paper).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(ReminderText.privacy(lang), style = MaterialTheme.typography.headlineLarge)
        Text(ReminderText.consentBody(lang), style = MaterialTheme.typography.bodyLarge)
        BigButton(ReminderText.deleteMyData(lang), onClick = { confirm = true }, container = Red, minHeight = 80.dp)
        BigButton(if (lang == Language.HINDI) "वापस" else "Back", onClick = onBack, container = Grey, content = Ink)
        error?.let { Text(it, color = Red, style = MaterialTheme.typography.bodyMedium) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (lang == Language.HINDI) "पक्का?" else "Are you sure?") },
            text = {
                Text(
                    if (lang == Language.HINDI) "दवाई की याद बंद हो जाएगी और आपकी जानकारी मिट जाएगी।" else "Reminders will stop and your data will be deleted.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        try {
                            graph.elder.deleteMyData()
                            SyncWorker.cancelAll(graph.app)
                            onDeleted()
                        } catch (e: Exception) {
                            error = if (lang == Language.HINDI) "इंटरनेट चालू करके फिर कोशिश करें।" else "Turn on the internet and try again."
                        }
                    }
                }) { Text(if (lang == Language.HINDI) "हाँ, मिटाएं" else "Yes, delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(if (lang == Language.HINDI) "नहीं" else "No") }
            },
        )
    }
}

@Composable
private fun PinGate(graph: AppGraph, onOpen: () -> Unit, onBack: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Paper).safeDrawingPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Family PIN", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = pin,
            onValueChange = {
                pin = it.filter(Char::isDigit).take(4)
                wrong = false
            },
            label = { Text("4-digit PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = wrong,
            modifier = Modifier.fillMaxWidth(),
        )
        if (wrong) Text("Wrong PIN", color = Red, style = MaterialTheme.typography.bodyMedium)
        BigButton("Open settings", enabled = pin.length == 4, onClick = {
            if (graph.prefs.checkPin(pin)) onOpen() else wrong = true
        })
        BigButton("Back", onClick = onBack, container = Grey, content = Ink)
    }
}

/** For the family, on the elder's phone: phone checks, a test alarm, sync, unpair. */
@Composable
private fun ElderSettingsScreen(graph: AppGraph, onBack: () -> Unit, onUnpaired: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmUnpair by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Paper).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        PhoneChecks { allOk ->
            if (!allOk) Text("Reminders may not ring until the red lines are fixed.", color = Red, style = MaterialTheme.typography.bodyMedium)
        }
        BigButton("Ring a test alarm in 1 minute", onClick = {
            scope.launch {
                graph.reminders.scheduleTest()
                message = "Lock the phone; it rings in about a minute."
            }
        })
        BigButton("Get medicine changes now", container = Blue, onClick = {
            scope.launch {
                message = try {
                    graph.elder.pull()
                    graph.elder.pushUnsynced()
                    "Up to date."
                } catch (e: Exception) {
                    "No internet. Reminders keep working; changes arrive when it is back."
                }
            }
        })
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        BigButton("Unpair this phone", onClick = { confirmUnpair = true }, container = Grey, content = Ink)
        BigButton("Back", onClick = onBack, container = Grey, content = Ink)
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair this phone?") },
            text = { Text("Reminders stop on this phone. The elder's medicines stay in your family's account.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    scope.launch {
                        graph.elder.wipeLocal()
                        SyncWorker.cancelAll(context)
                        onUnpaired()
                    }
                }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}
