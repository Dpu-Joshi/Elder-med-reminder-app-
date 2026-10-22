package com.medreminder.alarm

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Everything on the elder's phone that can stop a reminder from ringing, with the settings
 * screen that fixes each one. The setup wizard runs this on the elder's phone, with the family
 * member holding it, and again whenever the family opens settings.
 */
object ReliabilityChecks {

    enum class CheckId {
        NOTIFICATIONS,
        ALARM_CHANNEL,
        EXACT_ALARMS,
        FULL_SCREEN,
        BACKGROUND_RESTRICTED,
        BATTERY_OPTIMIZATION,
        ALARM_VOLUME,
        OEM_AUTOSTART,
        OEM_BATTERY,
        OEM_LOCK_SCREEN_POPUP,
    }

    data class Result(
        val id: CheckId,
        /** Null when the phone cannot report it; the family member checks by hand. */
        val ok: Boolean?,
        /** A reminder can fail to ring while this is false. */
        val critical: Boolean,
        val fixIntents: List<Intent>,
    )

    fun run(context: Context): List<Result> {
        val pkg = context.packageName
        val packageUri = Uri.fromParts("package", pkg, null)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val details = OemSettings.appDetailsIntent(context)
        val aggressive = OemSettings.isAggressiveBrand()

        val results = mutableListOf(
            Result(
                CheckId.NOTIFICATIONS,
                ok = notificationsGranted(context) && notificationManager.areNotificationsEnabled(),
                critical = true,
                fixIntents = listOf(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
                    details,
                ),
            ),
            Result(
                CheckId.ALARM_CHANNEL,
                ok = AlarmNotifier(context).isChannelUsable(),
                critical = true,
                fixIntents = listOf(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, AlarmNotifier.CHANNEL_ID),
                    details,
                ),
            ),
            Result(
                CheckId.EXACT_ALARMS,
                ok = AlarmScheduler(context).canScheduleExact(),
                critical = true,
                fixIntents = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    listOf(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri), details)
                } else {
                    listOf(details)
                },
            ),
            Result(
                CheckId.FULL_SCREEN,
                ok = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                    notificationManager.canUseFullScreenIntent(),
                critical = true,
                fixIntents = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    listOf(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri), details)
                } else {
                    listOf(details)
                },
            ),
            Result(
                CheckId.BACKGROUND_RESTRICTED,
                ok = Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                    !context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
                critical = true,
                fixIntents = listOf(details),
            ),
            Result(
                CheckId.BATTERY_OPTIMIZATION,
                ok = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg),
                critical = aggressive,
                fixIntents = listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details),
            ),
            Result(
                CheckId.ALARM_VOLUME,
                ok = AlarmRinger(context).alarmVolume() > 0,
                critical = false,
                fixIntents = listOf(Intent(Settings.ACTION_SOUND_SETTINGS)),
            ),
        )

        OemSettings.steps(context).forEach { step ->
            val id = when (step.id) {
                OemSettings.StepId.AUTOSTART -> CheckId.OEM_AUTOSTART
                OemSettings.StepId.BATTERY -> CheckId.OEM_BATTERY
                OemSettings.StepId.LOCK_SCREEN_POPUP -> CheckId.OEM_LOCK_SCREEN_POPUP
            }
            results += Result(id, ok = null, critical = true, fixIntents = step.intents)
        }
        return results
    }

    /** True when nothing critical is known to be broken. Manual checks count as passed once confirmed by the caller. */
    fun criticalOk(results: List<Result>, confirmedManual: Set<CheckId> = emptySet()): Boolean =
        results.filter { it.critical }.all { it.ok == true || (it.ok == null && it.id in confirmedManual) }

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Opens the first fix screen this phone has. Returns false when none exists. */
    fun openFix(context: Context, result: Result): Boolean = launchFirst(context, result.fixIntents)

    fun launchFirst(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                // Some OEM screens are not exported on every OS version.
                continue
            }
        }
        return false
    }
}
