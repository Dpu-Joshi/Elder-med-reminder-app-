package com.medreminder.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.medreminder.MainActivity
import com.medreminder.R
import com.medreminder.data.Prefs
import com.medreminder.graph
import com.medreminder.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** What a family alert push carries; also read from the launch intent when the notification is tapped. */
data class AlertTarget(
    val type: String,
    val familyId: String,
    val elderId: String,
    val doseId: String?,
    val phoneNumber: String,
) {
    fun toIntent(intent: Intent): Intent = intent
        .putExtra("type", type)
        .putExtra("familyId", familyId)
        .putExtra("elderId", elderId)
        .putExtra("doseId", doseId)
        .putExtra("phoneNumber", phoneNumber)

    companion object {
        fun from(data: Map<String, String>): AlertTarget? {
            val type = data["type"] ?: return null
            val familyId = data["familyId"] ?: return null
            val elderId = data["elderId"] ?: return null
            return AlertTarget(type, familyId, elderId, data["doseId"]?.takeIf { it.isNotEmpty() }, data["phoneNumber"].orEmpty())
        }

        /** Notification taps put the push's data keys into the launch intent's extras. */
        fun from(intent: Intent?): AlertTarget? {
            val extras = intent?.extras ?: return null
            val keys = listOf("type", "familyId", "elderId", "doseId", "phoneNumber")
            return from(keys.mapNotNull { key -> extras.getString(key)?.let { key to it } }.toMap())
        }
    }
}

object FamilyAlertNotifier {
    const val CHANNEL_ID = "family_alerts"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Alerts about your family", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A dose was not confirmed, or a phone went silent"
            },
        )
    }

    /** Shown when the app is open; in the background Android shows the push itself, without buttons. */
    fun show(context: Context, target: AlertTarget, title: String, body: String) {
        val id = (target.doseId ?: target.elderId).hashCode()
        val open = PendingIntent.getActivity(
            context,
            id,
            target.toIntent(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)

        if (target.phoneNumber.isNotBlank()) {
            val call = PendingIntent.getActivity(
                context,
                id + 1,
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${target.phoneNumber}")),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, "Call", call).build())
        }
        if (target.type == "dose_not_confirmed" && target.doseId != null) {
            val checked = PendingIntent.getBroadcast(
                context,
                id + 2,
                target.toIntent(Intent(context, AlertActionReceiver::class.java).setAction(AlertActionReceiver.ACTION_CHECKED)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, "She took it, I checked", checked).build())
        }
        context.getSystemService(NotificationManager::class.java).notify(id, builder.build())
    }

    fun cancel(context: Context, target: AlertTarget) {
        context.getSystemService(NotificationManager::class.java).cancel((target.doseId ?: target.elderId).hashCode())
    }
}

/** "She took it, I checked" straight from the notification. */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECKED) return
        val target = AlertTarget.from(intent) ?: return
        val doseId = target.doseId ?: return
        val pending = goAsync()
        scope.launch {
            try {
                context.graph.family.markChecked(target.familyId, target.elderId, doseId)
                FamilyAlertNotifier.cancel(context, target)
            } catch (e: Exception) {
                // Leave the notification so they can open the app and try again.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CHECKED = "com.medreminder.action.CHECKED"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

class MessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val graph = applicationContext.graph
        scope.launch {
            runCatching {
                when (graph.prefs.mode) {
                    Prefs.Mode.ELDER -> graph.elder.registerToken(token)
                    Prefs.Mode.FAMILY -> graph.family.registerToken(token)
                    null -> Unit
                }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val graph = applicationContext.graph
        if (message.data["type"] == "sync") {
            // A family member changed a medicine. We have about 10 seconds; pull now, and
            // leave a job behind in case that was not enough.
            runBlocking { withTimeoutOrNull(PULL_BUDGET_MILLIS) { runCatching { graph.elder.pull() } } }
            SyncWorker.runSoon(this)
            return
        }
        val target = AlertTarget.from(message.data) ?: return
        val notification = message.notification ?: return
        FamilyAlertNotifier.show(this, target, notification.title.orEmpty(), notification.body.orEmpty())
    }

    private companion object {
        const val PULL_BUDGET_MILLIS = 8_000L
    }
}
