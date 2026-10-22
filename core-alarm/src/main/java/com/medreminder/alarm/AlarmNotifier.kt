package com.medreminder.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager

/**
 * Posts the dose notification. Its full-screen intent opens the alarm screen over the lock
 * screen; when the phone is in use it shows as a heads-up instead. The channel sound plays on
 * the alarm stream, so it rings even when the phone is on silent.
 */
class AlarmNotifier(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    /** Channel settings cannot change after creation: bump the id to change sound or vibration. */
    fun ensureChannel(name: CharSequence, description: String) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH).apply {
            this.description = description
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** False when notifications are off or the family lowered this channel below "pop on screen". */
    fun isChannelUsable(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return true
        return channel.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    fun show(
        notificationId: Int,
        title: String,
        text: String,
        smallIcon: Int,
        fullScreenIntent: PendingIntent,
        actions: List<Notification.Action> = emptyList(),
        insistent: Boolean = true,
    ) {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(fullScreenIntent)
            .setOngoing(true)
            .setAutoCancel(false)
        actions.forEach { builder.addAction(it) }
        val notification = builder.build()
        // Keeps the sound looping until the alarm screen or an action clears the notification.
        if (insistent) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager.notify(notificationId, notification)
    }

    fun cancel(notificationId: Int) = manager.cancel(notificationId)

    companion object {
        const val CHANNEL_ID = "dose_alarm_v1"
        private val VIBRATION_PATTERN = longArrayOf(0, 800, 400, 800, 400, 800)
    }
}
