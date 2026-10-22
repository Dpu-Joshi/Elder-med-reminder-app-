package com.medreminder.ui.family

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.medreminder.AppGraph
import com.medreminder.data.Elder
import com.medreminder.data.Medicine
import com.medreminder.push.AlertTarget
import com.medreminder.push.FamilyAlertNotifier
import com.medreminder.ui.common.BigButton
import com.medreminder.ui.common.ScreenScaffold
import com.medreminder.ui.common.agoLabel
import com.medreminder.ui.common.clockLabel
import com.medreminder.ui.common.dial
import com.medreminder.ui.common.shareText
import com.medreminder.ui.theme.Blue
import com.medreminder.ui.theme.FamilyTheme
import com.medreminder.ui.theme.Green
import com.medreminder.ui.theme.Grey
import com.medreminder.ui.theme.Ink
import com.medreminder.ui.theme.Muted
import com.medreminder.ui.theme.Red
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Screens in Family mode, kept on a simple back stack. */
sealed interface FamilyScreen {
    data object Home : FamilyScreen
    data object Pair : FamilyScreen
    data object Invite : FamilyScreen
    data object Join : FamilyScreen
    data class ElderDetail(val familyId: String, val elderId: String) : FamilyScreen
    data class ElderEdit(val familyId: String, val elderId: String) : FamilyScreen
    data class EditMedicine(val familyId: String, val elderId: String, val medicine: Medicine?) : FamilyScreen
    data class Alert(val target: AlertTarget) : FamilyScreen
}

@Composable
fun FamilyFlow(graph: AppGraph, alert: AlertTarget?, onAlertHandled: () -> Unit, onModeReset: () -> Unit) {
    var signedIn by remember { mutableStateOf(graph.family.user != null) }
    FamilyTheme {
        if (!signedIn) {
            SignInScreen(graph, onSignedIn = { signedIn = true }, onBack = onModeReset)
        } else {
            FamilyNav(graph, alert, onAlertHandled, onSignedOut = { signedIn = false })
        }
    }
}

@Composable
private fun FamilyNav(graph: AppGraph, alert: AlertTarget?, onAlertHandled: () -> Unit, onSignedOut: () -> Unit) {
    val stack = remember { mutableStateListOf<FamilyScreen>(FamilyScreen.Home) }
    val push: (FamilyScreen) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val replaceTop: (FamilyScreen) -> Unit = {
        stack.removeAt(stack.lastIndex)
        stack.add(it)
    }

    LaunchedEffect(alert) {
        if (alert != null) {
            stack.add(FamilyScreen.Alert(alert))
            onAlertHandled()
        }
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    when (val screen = stack.last()) {
        FamilyScreen.Home -> FamilyHome(graph, push, onSignedOut)
        FamilyScreen.Pair -> PairElderScreen(graph, onBack = { pop() }, onPaired = { f, e -> replaceTop(FamilyScreen.ElderDetail(f, e)) })
        FamilyScreen.Invite -> InviteScreen(graph, onBack = { pop() })
        FamilyScreen.Join -> JoinScreen(graph, onBack = { pop() })
        is FamilyScreen.ElderDetail -> ElderDetailScreen(graph, screen.familyId, screen.elderId, push, onBack = { pop() })
        is FamilyScreen.ElderEdit -> ElderEditScreen(graph, screen.familyId, screen.elderId, onBack = { pop() })
        is FamilyScreen.EditMedicine -> MedicineEditScreen(graph, screen.familyId, screen.elderId, screen.medicine, onDone = { pop() })
        is FamilyScreen.Alert -> AlertScreen(graph, screen.target, push, onBack = { pop() })
    }
}

@Composable
private fun SignInScreen(graph: AppGraph, onSignedIn: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ScreenScaffold("Family mode", onBack = onBack) {
        Text("Sign in to set up medicines and get alerts", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Your Google account keeps your family's data separate from everyone else's. " +
                "The elder never signs in to anything.",
            style = MaterialTheme.typography.bodyLarge,
        )
        BigButton("Sign in with Google", enabled = !busy, onClick = {
            val activity = context.findActivity() ?: return@BigButton
            busy = true
            error = null
            scope.launch {
                try {
                    graph.family.signInWithGoogle(activity)
                    onSignedIn()
                } catch (e: Exception) {
                    error = "Sign-in did not finish. Check the internet connection and try again."
                } finally {
                    busy = false
                }
            }
        })
        error?.let { Text(it, color = Red) }
    }
}

private sealed interface FamilyState {
    data object Loading : FamilyState
    data object None : FamilyState
    data class Member(val familyId: String) : FamilyState
}

@Composable
private fun FamilyHome(graph: AppGraph, push: (FamilyScreen) -> Unit, onSignedOut: () -> Unit) {
    val context = LocalContext.current
    val familyState by remember {
        graph.family.observeFamilyId().map { id -> if (id == null) FamilyState.None else FamilyState.Member(id) }
    }.collectAsStateWithLifecycle<FamilyState>(FamilyState.Loading)

    // Alerts are useless if this phone cannot show them.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        runCatching { graph.family.registerToken() }
    }

    ScreenScaffold("Your family") {
        when (val state = familyState) {
            FamilyState.Loading -> Text("Loading…")
            FamilyState.None -> {
                Text("Start by pairing your parent's phone", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Install this app on their phone, choose \"My parent's phone\", and follow the steps. " +
                        "It will show a code for you to scan here.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                BigButton("Pair a parent's phone", onClick = { push(FamilyScreen.Pair) })
                BigButton("Join my family with an invite code", onClick = { push(FamilyScreen.Join) }, container = Blue)
            }
            is FamilyState.Member -> {
                val elders by remember(state.familyId) { graph.family.observeElders(state.familyId) }
                    .collectAsStateWithLifecycle(emptyList())
                elders.forEach { elder ->
                    ElderCard(elder) { push(FamilyScreen.ElderDetail(state.familyId, elder.id)) }
                }
                BigButton("Pair a parent's phone", onClick = { push(FamilyScreen.Pair) })
                BigButton("Invite another family member", onClick = { push(FamilyScreen.Invite) }, container = Blue)
            }
        }
        TextButton(onClick = {
            graph.family.signOut()
            onSignedOut()
        }) { Text("Sign out") }
    }
}

@Composable
private fun ElderCard(elder: Elder, onClick: () -> Unit) {
    val now = System.currentTimeMillis()
    val last = elder.lastCheckInAt
    val silent = last == null || now - last > elder.settings.silentAfterHours * 3_600_000L
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(elder.name, style = MaterialTheme.typography.titleLarge)
            Text(
                if (last == null) "Phone has not checked in yet" else "Phone checked in ${agoLabel(last, now)}",
                color = if (silent) Red else Muted,
            )
        }
    }
}

@Composable
private fun InviteScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            code = graph.family.createInvite()
        } catch (e: Exception) {
            error = "Could not make an invite code. Check the internet connection."
        }
    }
    ScreenScaffold("Invite family", onBack = onBack) {
        Text(
            "A brother, sister or spouse can share the alerts. They install the app, choose Family mode, sign in, " +
                "and enter this code. They are alerted second, after you; change the order on each elder's page.",
            style = MaterialTheme.typography.bodyLarge,
        )
        code?.let {
            Text(it.chunked(3).joinToString(" "), fontSize = 44.sp, style = MaterialTheme.typography.headlineLarge)
            Text("Valid for 24 hours", color = Muted)
            BigButton("Share code", onClick = { shareText(context, "Join our family in the medicine reminder app. Code: $it") })
        }
        error?.let { Text(it, color = Red) }
    }
}

@Composable
private fun JoinScreen(graph: AppGraph, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    ScreenScaffold("Join a family", onBack = onBack) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("6-digit invite code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        BigButton("Join", enabled = code.length == 6 && !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    graph.family.joinFamily(code)
                    onBack()
                } catch (e: Exception) {
                    error = e.message ?: "Could not join."
                } finally {
                    busy = false
                }
            }
        })
        error?.let { Text(it, color = Red) }
    }
}

/** Opened from an alert: call, or say you checked. */
@Composable
private fun AlertScreen(graph: AppGraph, target: AlertTarget, push: (FamilyScreen) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val elder by remember { graph.family.observeElder(target.familyId, target.elderId) }.collectAsStateWithLifecycle(null)
    val dose by remember {
        target.doseId?.let { graph.family.observeDose(target.familyId, target.elderId, it) }
            ?: flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val medicineId = dose?.medicineId
    val medicine by remember(medicineId) {
        medicineId?.let { graph.family.observeMedicine(target.familyId, target.elderId, it) }
            ?: flowOf(null)
    }.collectAsStateWithLifecycle(null)
    var error by remember { mutableStateOf<String?>(null) }
    val name = elder?.name ?: "Your family member"
    val phone = elder?.phoneNumber?.ifBlank { null } ?: target.phoneNumber

    ScreenScaffold(name, onBack = onBack) {
        when (target.type) {
            "phone_silent" -> {
                Text("$name's phone is silent", style = MaterialTheme.typography.headlineSmall, color = Red)
                Text(
                    "It has not checked in for a while. It may be switched off, out of battery, or the app was closed. " +
                        "Reminders may not ring until it is back.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            else -> {
                val d = dose
                val confirmed = d?.status?.isConfirmed == true
                Text(
                    if (confirmed) "Confirmed" else "Not confirmed",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (confirmed) Green else Red,
                )
                Text(
                    listOfNotNull(medicine?.name, d?.localTime?.let { "${clockLabel(it)} dose" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "\"Not confirmed\" means Taken was not tapped. It does not mean the medicine was missed: call and ask.",
                    color = Muted,
                )
                if (d != null && !confirmed) {
                    BigButton("She took it, I checked", container = Green, onClick = {
                        scope.launch {
                            try {
                                graph.family.markChecked(target.familyId, target.elderId, d.id)
                                FamilyAlertNotifier.cancel(context, target)
                            } catch (e: Exception) {
                                error = "Could not save. Check the internet connection."
                            }
                        }
                    })
                }
            }
        }
        if (phone.isNotBlank()) {
            BigButton("📞  Call $name", container = Blue, onClick = {
                target.doseId?.let { id ->
                    scope.launch { runCatching { graph.family.acknowledge(target.familyId, target.elderId, id) } }
                }
                dial(context, phone)
            })
        }
        BigButton("Open ${name}'s page", container = Grey, content = Ink, onClick = {
            push(FamilyScreen.ElderDetail(target.familyId, target.elderId))
        })
        error?.let { Text(it, color = Red) }
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
