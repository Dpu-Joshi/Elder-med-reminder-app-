package com.medreminder.ui.family

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.medreminder.AppGraph
import com.medreminder.data.Dose
import com.medreminder.data.DoseStatus
import com.medreminder.data.Language
import com.medreminder.data.Medicine
import com.medreminder.data.ReminderText
import com.medreminder.ui.common.BigButton
import com.medreminder.ui.common.MedicinePhoto
import com.medreminder.ui.common.ScreenScaffold
import com.medreminder.ui.common.Section
import com.medreminder.ui.common.agoLabel
import com.medreminder.ui.common.clockLabel
import com.medreminder.ui.common.dial
import com.medreminder.ui.theme.Amber
import com.medreminder.ui.theme.Blue
import com.medreminder.ui.theme.Green
import com.medreminder.ui.theme.Muted
import com.medreminder.ui.theme.Red
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Scan the QR code on the elder's phone (or type its 6 digits) and describe the elder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairElderScreen(graph: AppGraph, onBack: () -> Unit, onPaired: (familyId: String, elderId: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var elderName by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(Language.HINDI) }
    var elderPhone by remember { mutableStateOf("") }
    var myName by remember { mutableStateOf(graph.family.user?.displayName?.substringBefore(' ').orEmpty()) }
    var myPhone by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ScreenScaffold("Pair a parent's phone", onBack = onBack) {
        Text(
            "On their phone, the app shows a QR code after the phone-settings step.",
            style = MaterialTheme.typography.bodyLarge,
        )
        BigButton("Scan the QR code", onClick = {
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { barcode ->
                    Regex("\\d{6}").find(barcode.rawValue.orEmpty())?.value?.let { code = it }
                }
        })
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("Or type the 6-digit code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = elderName,
            onValueChange = { elderName = it.take(60) },
            label = { Text("Their name, for example Kamla Joshi") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it.take(30) },
            label = { Text("What the reminder calls them, for example मां") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Reminder language", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = language == Language.HINDI, onClick = { language = Language.HINDI }, label = { Text("हिंदी") })
            FilterChip(selected = language == Language.ENGLISH, onClick = { language = Language.ENGLISH }, label = { Text("English") })
        }
        OutlinedTextField(
            value = elderPhone,
            onValueChange = { elderPhone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) },
            label = { Text("Their phone number (for your Call button)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Their \"Call\" button rings:", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = myName,
            onValueChange = { myName = it.take(30) },
            label = { Text("Your name as they say it, for example Rahul") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = myPhone,
            onValueChange = { myPhone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) },
            label = { Text("Your phone number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        BigButton("Pair", enabled = !busy && code.length == 6 && elderName.isNotBlank(), onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    val (familyId, elderId) = graph.family.claimPairingCode(code, elderName.trim(), nickname.trim(), language, elderPhone.trim())
                    if (myPhone.isNotBlank()) {
                        graph.family.updateElder(familyId, elderId, mapOf("callName" to myName.trim(), "callNumber" to myPhone.trim()))
                    }
                    onPaired(familyId, elderId)
                } catch (e: Exception) {
                    error = e.message ?: "Pairing failed. Ask the elder's phone for a new code."
                } finally {
                    busy = false
                }
            }
        })
        error?.let { Text(it, color = Red) }
    }
}

/** One elder: today's doses as the server sees them, the medicines, and the Call button. */
@Composable
fun ElderDetailScreen(graph: AppGraph, familyId: String, elderId: String, push: (FamilyScreen) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val elder by remember { graph.family.observeElder(familyId, elderId) }.collectAsStateWithLifecycle(null)
    val zone = remember(elder?.timezone) { runCatching { ZoneId.of(elder?.timezone ?: "Asia/Kolkata") }.getOrDefault(ZoneId.systemDefault()) }
    val today = LocalDate.now(zone).toString()
    val doses by remember(today) { graph.family.observeDoses(familyId, elderId, today) }.collectAsStateWithLifecycle(emptyList())
    val medicines by remember { graph.family.observeMedicines(familyId, elderId) }.collectAsStateWithLifecycle(emptyList())
    val family by remember { graph.family.observeFamily(familyId) }.collectAsStateWithLifecycle(null)
    val byId = medicines.associateBy { it.id }
    var error by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(elder?.name ?: "…", onBack = onBack) {
        elder?.let { e ->
            val last = e.lastCheckInAt
            Text(
                if (last == null) "Phone has not checked in yet" else "Phone checked in ${agoLabel(last)}",
                color = if (last == null || System.currentTimeMillis() - last > e.settings.silentAfterHours * 3_600_000L) Red else Muted,
            )
            if (e.phoneNumber.isNotBlank()) {
                BigButton("📞  Call ${e.name}", container = Blue, onClick = { dial(context, e.phoneNumber) })
            }
        }

        Section("Today") {
            if (doses.isEmpty()) Text("No doses today.", color = Muted)
            doses.forEach { dose ->
                DoseStatusRow(dose, byId[dose.medicineId], family?.memberNames.orEmpty(), zone, onChecked = {
                    scope.launch {
                        try {
                            graph.family.markChecked(familyId, elderId, dose.id)
                        } catch (ex: Exception) {
                            error = "Could not save. Check the internet connection."
                        }
                    }
                })
            }
            error?.let { Text(it, color = Red) }
        }

        Section("Medicines") {
            medicines.filter { it.active }.forEach { m ->
                MedicineCard(graph, m) { push(FamilyScreen.EditMedicine(familyId, elderId, m)) }
            }
            BigButton("Add a medicine", onClick = { push(FamilyScreen.EditMedicine(familyId, elderId, null)) })
            val stopped = medicines.filter { !it.active }
            if (stopped.isNotEmpty()) Text("Stopped: ${stopped.joinToString { it.name }}", color = Muted)
        }

        Section("Settings") {
            family?.let { f ->
                if (f.alertOrder.size > 1) {
                    Text("Alerted first: ${f.memberNames[f.alertOrder.first()] ?: "you"}", color = Muted)
                    OutlinedButton(onClick = {
                        scope.launch { runCatching { graph.family.setAlertOrder(familyId, f.alertOrder.drop(1) + f.alertOrder.first()) } }
                    }) { Text("Rotate who is alerted first") }
                }
            }
            OutlinedButton(onClick = { push(FamilyScreen.ElderEdit(familyId, elderId)) }) { Text("Name, language, Call button, alert time") }
        }
    }
}

@Composable
private fun DoseStatusRow(dose: Dose, medicine: Medicine?, names: Map<String, String>, zone: ZoneId, onChecked: () -> Unit) {
    val (label, color) = when (dose.status) {
        DoseStatus.CONFIRMED -> "Confirmed ${dose.confirmedAt?.let { clockLabel(it, zone) }.orEmpty()}" to Green
        DoseStatus.CONFIRMED_BY_FAMILY -> "Checked by ${names[dose.confirmedBy] ?: "family"}" to Green
        DoseStatus.NOT_CONFIRMED -> "Not confirmed" to Red
        DoseStatus.SNOOZED -> "Snoozed" to Amber
        DoseStatus.PENDING ->
            (if (dose.scheduledAt > System.currentTimeMillis()) "Due" else "Waiting for Taken") to Muted
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(clockLabel(dose.localTime), style = MaterialTheme.typography.titleMedium)
                Text(medicine?.name ?: "Medicine", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
            Text(label, color = color)
            if (dose.status == DoseStatus.NOT_CONFIRMED) {
                OutlinedButton(onClick = onChecked) { Text("She took it, I checked") }
            }
        }
    }
}

@Composable
private fun MedicineCard(graph: AppGraph, medicine: Medicine, onClick: () -> Unit) {
    var url by remember(medicine.photoPath) { mutableStateOf<String?>(null) }
    LaunchedEffect(medicine.photoPath) { url = medicine.photoPath?.let { graph.family.photoUrl(it) } }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MedicinePhoto(url, size = 64.dp)
            Column(Modifier.weight(1f)) {
                Text(medicine.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        ReminderText.doseLabel(Language.ENGLISH, medicine.doseAmount, medicine.doseUnit),
                        ReminderText.foodLabel(Language.ENGLISH, medicine.foodRule)?.lowercase(),
                    ).joinToString(", "),
                )
                Text(medicine.times.joinToString(" · ") { clockLabel(it) }, color = Muted)
                medicine.tabletsLeft?.let { Text("$it left", color = if (it <= 5) Red else Muted) }
            }
        }
    }
}

/** Family-editable details of an elder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElderEditScreen(graph: AppGraph, familyId: String, elderId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val elder by remember { graph.family.observeElder(familyId, elderId) }.collectAsStateWithLifecycle(null)
    val e = elder
    if (e == null) {
        ScreenScaffold("…", onBack = onBack) { Text("Loading…") }
        return
    }
    var name by remember(e.id) { mutableStateOf(e.name) }
    var nickname by remember(e.id) { mutableStateOf(e.nickname) }
    var language by remember(e.id) { mutableStateOf(e.language) }
    var phone by remember(e.id) { mutableStateOf(e.phoneNumber) }
    var callName by remember(e.id) { mutableStateOf(e.callName) }
    var callNumber by remember(e.id) { mutableStateOf(e.callNumber) }
    var alertAfter by remember(e.id) { mutableIntStateOf(e.settings.alertAfterMinutes) }
    var error by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(e.name, onBack = onBack) {
        OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = nickname, onValueChange = { nickname = it.take(30) }, label = { Text("What the reminder calls them") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = language == Language.HINDI, onClick = { language = Language.HINDI }, label = { Text("हिंदी") })
            FilterChip(selected = language == Language.ENGLISH, onClick = { language = Language.ENGLISH }, label = { Text("English") })
        }
        OutlinedTextField(value = phone, onValueChange = { phone = it.take(16) }, label = { Text("Their phone number") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = callName, onValueChange = { callName = it.take(30) }, label = { Text("Their Call button rings (name)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = callNumber, onValueChange = { callNumber = it.take(16) }, label = { Text("Their Call button rings (number)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        Text("Alert the family when a dose is not confirmed after", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 45, 60).forEach { minutes ->
                FilterChip(selected = alertAfter == minutes, onClick = { alertAfter = minutes }, label = { Text("$minutes min") })
            }
        }
        BigButton("Save", enabled = name.isNotBlank(), onClick = {
            scope.launch {
                try {
                    graph.family.updateElder(
                        familyId,
                        elderId,
                        mapOf(
                            "name" to name.trim(),
                            "nickname" to nickname.trim(),
                            "language" to language.wire,
                            "phoneNumber" to phone.trim(),
                            "callName" to callName.trim(),
                            "callNumber" to callNumber.trim(),
                            "settings.alertAfterMinutes" to alertAfter,
                            "settings.escalateAfterMinutes" to alertAfter + 30,
                        ),
                    )
                    onBack()
                } catch (ex: Exception) {
                    error = "Could not save. Check the internet connection."
                }
            }
        })
        error?.let { Text(it, color = Red) }
    }
}
