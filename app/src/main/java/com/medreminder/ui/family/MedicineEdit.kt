package com.medreminder.ui.family

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.medreminder.AppGraph
import com.medreminder.data.DoseUnit
import com.medreminder.data.FoodRule
import com.medreminder.data.Language
import com.medreminder.data.Medicine
import com.medreminder.data.ReminderText
import com.medreminder.ui.common.BigButton
import com.medreminder.ui.common.MedicinePhoto
import com.medreminder.ui.common.ScreenScaffold
import com.medreminder.ui.common.clockLabel
import com.medreminder.ui.theme.Muted
import com.medreminder.ui.theme.Red
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * Add or edit a medicine from the family member's phone: a photo of the strip, the dose, the
 * times and the food rule. Saving reaches the elder's phone within seconds when it is online,
 * and at its next sync otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MedicineEditScreen(graph: AppGraph, familyId: String, elderId: String, existing: Medicine?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var spokenName by remember { mutableStateOf(existing?.spokenName.orEmpty()) }
    var amount by remember { mutableStateOf(existing?.doseAmount ?: 1.0) }
    var unit by remember { mutableStateOf(existing?.doseUnit ?: DoseUnit.TABLET) }
    var food by remember { mutableStateOf(existing?.foodRule ?: FoodRule.AFTER) }
    val times = remember { mutableStateListOf<String>().apply { addAll(existing?.times.orEmpty()) } }
    var startDate by remember { mutableStateOf(existing?.startDate ?: today.toString()) }
    var endDate by remember { mutableStateOf(existing?.endDate) }
    var tabletsLeft by remember { mutableStateOf(existing?.tabletsLeft?.toString().orEmpty()) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var photoUrl by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(existing?.photoPath) { photoUrl = existing?.photoPath?.let { graph.family.photoUrl(it) } }

    val cameraFile = remember { File(context.cacheDir, "photos/capture.jpg").apply { parentFile?.mkdirs() } }
    val cameraUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", cameraFile) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch { photo = withContext(Dispatchers.IO) { loadScaled(context, cameraUri) } }
    }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { photo = withContext(Dispatchers.IO) { loadScaled(context, uri) } }
    }

    ScreenScaffold(if (existing == null) "Add a medicine" else existing.name, onBack = onDone) {
        val shown = photo
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(160.dp).clip(RoundedCornerShape(16.dp)),
            )
        } else {
            MedicinePhoto(photoUrl, size = 160.dp)
        }
        Text("A photo of the actual strip is what the elder sees first.", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { takePicture.launch(cameraUri) }) { Text("Take photo") }
            OutlinedButton(onClick = {
                pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Choose photo") }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(80) },
            label = { Text("Medicine name, as on the strip") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = spokenName,
            onValueChange = { spokenName = it.take(40) },
            label = { Text("What you call it out loud (optional), e.g. BP की गोली") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Dose", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { amount = (amount - 0.5).coerceAtLeast(0.5) }) { Text("−") }
            Text(
                ReminderText.doseLabel(Language.ENGLISH, amount, unit),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            OutlinedButton(onClick = { amount = (amount + 0.5).coerceAtMost(20.0) }) { Text("+") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DoseUnit.entries.forEach { u ->
                FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u.wire) })
            }
        }

        Text("Food", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FoodRule.entries.forEach { rule ->
                FilterChip(
                    selected = food == rule,
                    onClick = { food = rule },
                    label = { Text(ReminderText.foodLabel(Language.ENGLISH, rule) ?: "Any time") },
                )
            }
        }

        Text("Times", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { (label, time) ->
                FilterChip(
                    selected = time in times,
                    onClick = { if (time in times) times.remove(time) else times.add(time) },
                    label = { Text("$label ${clockLabel(time)}") },
                )
            }
            times.filter { t -> PRESETS.none { it.second == t } }.sorted().forEach { t ->
                InputChip(selected = true, onClick = { times.remove(t) }, label = { Text("${clockLabel(t)}  ✕") })
            }
        }
        OutlinedButton(onClick = {
            TimePickerDialog(context, { _, h, m ->
                val t = "%02d:%02d".format(Locale.US, h, m)
                if (t !in times) times.add(t)
            }, 9, 0, false).show()
        }) { Text("Add another time") }

        Text("Course", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = {
            val d = LocalDate.parse(startDate)
            DatePickerDialog(context, { _, y, mo, day -> startDate = LocalDate.of(y, mo + 1, day).toString() }, d.year, d.monthValue - 1, d.dayOfMonth).show()
        }) { Text("Starts $startDate") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = endDate == null, onClick = { endDate = null }, label = { Text("No end date") })
            listOf(5, 7, 10, 15, 30).forEach { days ->
                val end = LocalDate.parse(startDate).plusDays(days - 1L).toString()
                FilterChip(selected = endDate == end, onClick = { endDate = end }, label = { Text("$days days") })
            }
        }
        endDate?.let { Text("Last dose day: $it", color = Muted) }

        OutlinedTextField(
            value = tabletsLeft,
            onValueChange = { tabletsLeft = it.filter(Char::isDigit).take(4) },
            label = { Text("Tablets left (optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        BigButton("Save", enabled = !busy && name.isNotBlank() && times.isNotEmpty(), onClick = {
            busy = true
            error = null
            val medicine = Medicine(
                id = existing?.id.orEmpty(),
                name = name.trim(),
                spokenName = spokenName.trim().ifBlank { null },
                photoPath = existing?.photoPath,
                doseAmount = amount,
                doseUnit = unit,
                foodRule = food,
                times = times.toList(),
                startDate = startDate,
                endDate = endDate,
                tabletsLeft = tabletsLeft.toIntOrNull(),
                active = true,
            )
            scope.launch {
                try {
                    val jpeg = photo?.let { withContext(Dispatchers.Default) { toJpeg(it) } }
                    graph.family.saveMedicine(familyId, elderId, medicine, jpeg)
                    onDone()
                } catch (e: Exception) {
                    error = "Could not save. Check the internet connection."
                } finally {
                    busy = false
                }
            }
        })
        if (existing != null) {
            TextButton(onClick = { confirmStop = true }) { Text("Stop this medicine", color = Red) }
        }
        error?.let { Text(it, color = Red) }
        Text(
            "The app only reminds. It never suggests doses or changes; for those, ask the doctor or pharmacist.",
            color = Muted,
        )
    }

    if (confirmStop && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop ${existing.name}?") },
            text = { Text("Reminders for it stop on the elder's phone. Its history is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    scope.launch {
                        try {
                            graph.family.stopMedicine(familyId, elderId, existing.id)
                            onDone()
                        } catch (e: Exception) {
                            error = "Could not save. Check the internet connection."
                        }
                    }
                }) { Text("Stop") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
        )
    }
}

private val PRESETS = listOf("Morning" to "08:00", "Afternoon" to "14:00", "Night" to "20:00")

private const val MAX_SIDE = 1024

/** Decodes a photo at most 1024 px on its long side, upright. */
private fun loadScaled(context: Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null

    val rotation = resolver.openInputStream(uri)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
    if (rotation == 0f && scale == 1f) return decoded
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate(rotation)
    }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}

private fun toJpeg(bitmap: Bitmap): ByteArray =
    ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        out.toByteArray()
    }
