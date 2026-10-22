package com.medreminder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medreminder.AppGraph
import com.medreminder.data.Prefs
import com.medreminder.push.AlertTarget
import com.medreminder.ui.common.BigButton
import com.medreminder.ui.elder.ElderFlow
import com.medreminder.ui.family.FamilyFlow
import com.medreminder.ui.theme.Blue
import com.medreminder.ui.theme.ElderTheme
import com.medreminder.ui.theme.Paper

/** One app, two modes. The person who installs it picks the mode once. */
@Composable
fun AppRoot(graph: AppGraph, alert: AlertTarget?, onAlertHandled: () -> Unit) {
    var mode by remember { mutableStateOf(graph.prefs.mode) }
    val reset = {
        graph.prefs.mode = null
        mode = null
    }
    when (mode) {
        null -> ModeSelectScreen { chosen ->
            graph.prefs.mode = chosen
            mode = chosen
        }
        Prefs.Mode.ELDER -> ElderFlow(graph, onModeReset = reset)
        Prefs.Mode.FAMILY -> FamilyFlow(graph, alert, onAlertHandled, onModeReset = reset)
    }
}

@Composable
private fun ModeSelectScreen(onChoose: (Prefs.Mode) -> Unit) {
    ElderTheme {
        Column(
            Modifier.fillMaxSize().background(Paper).safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Whose phone is this?", style = MaterialTheme.typography.headlineLarge)
            Text("यह फ़ोन किसका है?", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Set this up once. On a parent's phone, you do the setup and they only ever tap one big button.",
                style = MaterialTheme.typography.bodyMedium,
            )
            BigButton(
                "👵  My parent's phone\nमाता-पिता का फ़ोन",
                onClick = { onChoose(Prefs.Mode.ELDER) },
                minHeight = 110.dp,
            )
            BigButton(
                "📱  My phone (family)\nमेरा फ़ोन (परिवार)",
                onClick = { onChoose(Prefs.Mode.FAMILY) },
                container = Blue,
                minHeight = 110.dp,
            )
        }
    }
}
