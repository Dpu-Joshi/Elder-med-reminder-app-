package com.medreminder.ui.elder

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.medreminder.AppGraph
import com.medreminder.alarm.ReliabilityChecks
import com.medreminder.alarm.ReminderSpeaker
import com.medreminder.data.Language
import com.medreminder.data.ReminderText
import com.medreminder.sync.SyncWorker
import com.medreminder.ui.common.BigButton
import com.medreminder.ui.theme.Amber
import com.medreminder.ui.theme.Blue
import com.medreminder.ui.theme.Green
import com.medreminder.ui.theme.Grey
import com.medreminder.ui.theme.Ink
import com.medreminder.ui.theme.Muted
import com.medreminder.ui.theme.Paper
import com.medreminder.ui.theme.Red
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Done once on the elder's phone by a family member: language, phone settings, pairing,
 * a settings PIN, the elder's own consent, and a test alarm watched together.
 * The elder never creates an account, password or OTP.
 */
private enum class SetupStep { LANGUAGE, CHECKS, PAIR, PIN, CONSENT, TEST }

@Composable
fun ElderSetupScreen(graph: AppGraph, onDone: () -> Unit, onCancel: () -> Unit) {
    var step by remember { mutableStateOf(SetupStep.entries.getOrElse(graph.prefs.setupStep) { SetupStep.LANGUAGE }) }
    val go = { next: SetupStep ->
        graph.prefs.setupStep = next.ordinal
        step = next
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Step ${step.ordinal + 1} of ${SetupStep.entries.size}", style = MaterialTheme.typography.bodyMedium, color = Muted)
        when (step) {
            SetupStep.LANGUAGE -> LanguageStep(graph, onNext = { go(SetupStep.CHECKS) }, onCancel = onCancel)
            SetupStep.CHECKS -> {
                Text("Make sure reminders can ring", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Phones save battery by stopping apps. Fix each red line, and open each ? line and switch it on.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PhoneChecks { allOk ->
                    BigButton("Next", onClick = { go(SetupStep.PAIR) }, enabled = allOk)
                    if (!allOk) TextButton(onClick = { go(SetupStep.PAIR) }) { Text("Skip for now (reminders may not ring)") }
                }
            }
            SetupStep.PAIR -> PairStep(graph, onPaired = { go(SetupStep.PIN) })
            SetupStep.PIN -> PinStep(graph, onNext = { go(SetupStep.CONSENT) })
            SetupStep.CONSENT -> ConsentStep(graph, onAccepted = { go(SetupStep.TEST) }, onDeclined = onCancel)
            SetupStep.TEST -> TestStep(graph, onFinish = {
                graph.prefs.setupDone = true
                onDone()
            })
        }
    }
}

@Composable
private fun LanguageStep(graph: AppGraph, onNext: () -> Unit, onCancel: () -> Unit) {
    Text("Reminder language", style = MaterialTheme.typography.headlineMedium)
    Text("दवाई की याद किस भाषा में?", style = MaterialTheme.typography.titleLarge)
    BigButton("हिंदी", onClick = {
        graph.prefs.language = Language.HINDI
        onNext()
    })
    BigButton("English", onClick = {
        graph.prefs.language = Language.ENGLISH
        onNext()
    }, container = Blue)
    Text("More languages are coming. You can change this later from your own phone.", style = MaterialTheme.typography.bodyMedium, color = Muted)
    TextButton(onClick = onCancel) { Text("Back") }
}

/**
 * Every phone setting that can stop a reminder ringing, with a button to the screen that fixes
 * it. Brand settings (Xiaomi autostart and the like) cannot be read back, so the family member
 * ticks them after switching them on.
 */
@Composable
fun PhoneChecks(footer: @Composable (allOk: Boolean) -> Unit) {
    val context = LocalContext.current
    var results by remember { mutableStateOf(ReliabilityChecks.run(context)) }
    var confirmed by remember { mutableStateOf(emptySet<ReliabilityChecks.CheckId>()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { results = ReliabilityChecks.run(context) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        results = ReliabilityChecks.run(context)
    }

    results.forEach { result ->
        val (mark, color) = when {
            result.ok == true || result.id in confirmed -> "✓" to Green
            result.ok == null -> "?" to Amber
            else -> "✗" to Red
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mark, color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                Text(checkLabel(result.id), style = MaterialTheme.typography.bodyLarge)
            }
            if (result.ok != true) {
                BigButton(
                    if (result.ok == null) "Open setting" else "Fix",
                    onClick = {
                        if (result.id == ReliabilityChecks.CheckId.NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            ReliabilityChecks.openFix(context, result)
                        }
                    },
                    container = Grey,
                    content = Ink,
                    minHeight = 56.dp,
                )
            }
            if (result.ok == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = result.id in confirmed,
                        onCheckedChange = { checked -> confirmed = if (checked) confirmed + result.id else confirmed - result.id },
                    )
                    Text("I switched this on", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    footer(ReliabilityChecks.criticalOk(results, confirmed))
}

private fun checkLabel(id: ReliabilityChecks.CheckId): String = when (id) {
    ReliabilityChecks.CheckId.NOTIFICATIONS -> "Notifications allowed"
    ReliabilityChecks.CheckId.ALARM_CHANNEL -> "Medicine alarm can pop up on screen"
    ReliabilityChecks.CheckId.EXACT_ALARMS -> "Alarms & reminders allowed"
    ReliabilityChecks.CheckId.FULL_SCREEN -> "Full-screen reminders allowed"
    ReliabilityChecks.CheckId.BACKGROUND_RESTRICTED -> "Background activity allowed"
    ReliabilityChecks.CheckId.BATTERY_OPTIMIZATION -> "Battery: unrestricted"
    ReliabilityChecks.CheckId.ALARM_VOLUME -> "Alarm volume is up"
    ReliabilityChecks.CheckId.OEM_AUTOSTART -> "Autostart on"
    ReliabilityChecks.CheckId.OEM_BATTERY -> "Battery saver: no restrictions"
    ReliabilityChecks.CheckId.OEM_LOCK_SCREEN_POPUP -> "Show on lock screen and pop-up windows"
}

@Composable
private fun ColumnScope.PairStep(graph: AppGraph, onPaired: () -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        code = null
        try {
            code = graph.elder.createPairingCode()
        } catch (e: Exception) {
            error = "Could not get a code. Check the internet connection and try again."
        }
    }
    code?.let { current ->
        LaunchedEffect(current) {
            try {
                val (familyId, elderId) = graph.elder.observeClaim(current).filterNotNull().first()
                graph.elder.completePairing(familyId, elderId)
                SyncWorker.schedulePeriodic(context)
                onPaired()
            } catch (e: Exception) {
                error = "Pairing did not finish. Get a new code and try again."
            }
        }
    }

    Text("Connect to your phone", style = MaterialTheme.typography.headlineMedium)
    Text(
        "On your own phone, open this app in Family mode, tap \"Pair a parent's phone\" and scan this code.",
        style = MaterialTheme.typography.bodyMedium,
    )
    val current = code
    if (current != null) {
        val bitmap = remember(current) { qrBitmap("MEDREMINDER-PAIR:$current", 720) }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Pairing code",
            modifier = Modifier.size(280.dp).align(Alignment.CenterHorizontally),
        )
        Text(
            current.chunked(3).joinToString("  "),
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text("Or type these 6 digits. The code works for 30 minutes.", style = MaterialTheme.typography.bodyMedium, color = Muted)
    } else if (error == null) {
        Text("Getting a code…", style = MaterialTheme.typography.bodyLarge)
    }
    error?.let {
        Text(it, color = Red, style = MaterialTheme.typography.bodyMedium)
        BigButton("Get a new code", onClick = { attempt++ })
    }
    if (current != null) TextButton(onClick = { attempt++ }) { Text("Get a new code") }
}

@Composable
private fun PinStep(graph: AppGraph, onNext: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val valid = pin.length == 4 && pin.all(Char::isDigit) && pin == again

    Text("Set a family PIN", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Settings on this phone open only with this PIN, so nothing can be changed by mistake. Do not share it with the elder.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = pin,
        onValueChange = { pin = it.filter(Char::isDigit).take(4) },
        label = { Text("4-digit PIN") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = again,
        onValueChange = { again = it.filter(Char::isDigit).take(4) },
        label = { Text("Same PIN again") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    BigButton("Next", onClick = {
        graph.prefs.setPin(pin)
        onNext()
    }, enabled = valid)
}

/** The elder agrees, not the family: read aloud, with an equally easy No. */
@Composable
private fun ConsentStep(graph: AppGraph, onAccepted: () -> Unit, onDeclined: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = graph.prefs.language
    val text = ReminderText.consent(lang)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val speaker = remember {
        ReminderSpeaker(context, if (lang == Language.HINDI) ReminderSpeaker.HINDI else Locale.ENGLISH)
    }
    DisposableEffect(Unit) {
        speaker.speak(text, ReminderText.consent(Language.ENGLISH))
        onDispose { speaker.shutdown() }
    }

    Text(if (lang == Language.HINDI) "हाथ में फ़ोन दें" else "Hand the phone to the elder", style = MaterialTheme.typography.bodyMedium, color = Muted)
    Text(text, style = MaterialTheme.typography.bodyLarge)
    BigButton("🔊  ${ReminderText.sayAgain(lang)}", onClick = { speaker.speak(text, ReminderText.consent(Language.ENGLISH)) }, container = Grey, content = Ink, minHeight = 64.dp)
    BigButton("✓  ${ReminderText.consentYes(lang)}", enabled = !busy, onClick = {
        busy = true
        scope.launch {
            try {
                graph.elder.recordConsent()
                onAccepted()
            } catch (e: Exception) {
                error = "Could not save. Check the internet connection and try again."
            } finally {
                busy = false
            }
        }
    })
    BigButton("✗  ${ReminderText.consentNo(lang)}", enabled = !busy, container = Grey, content = Ink, onClick = {
        busy = true
        scope.launch {
            runCatching { graph.elder.deleteMyData() }
            graph.prefs.setupStep = 0
            busy = false
            onDeclined()
        }
    })
    error?.let { Text(it, color = Red, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun TestStep(graph: AppGraph, onFinish: () -> Unit) {
    val scope = rememberCoroutineScope()
    var scheduled by remember { mutableStateOf(false) }

    Text("Test it together", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Ring a test alarm, lock the phone, and watch it ring with the elder. If it does not ring, go back to the phone settings.",
        style = MaterialTheme.typography.bodyMedium,
    )
    BigButton(
        if (scheduled) "Rings in about 1 minute — lock the phone" else "Ring a test alarm in 1 minute",
        enabled = !scheduled,
        onClick = {
            scope.launch {
                graph.reminders.scheduleTest()
                scheduled = true
            }
        },
    )
    BigButton("Finish setup", onClick = onFinish, container = Blue)
}

/** The pairing QR code, drawn with ZXing on the phone (no network needed). */
private fun qrBitmap(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) AndroidColor.BLACK else AndroidColor.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
