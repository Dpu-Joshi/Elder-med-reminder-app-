package com.medreminder.alarm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

/**
 * Brand-specific screens where background apps get killed to save battery
 * (see https://dontkillmyapp.com). None of these can be read back, so the setup wizard
 * opens each screen and the family member confirms by hand.
 *
 * Component names change between OS versions, so each step lists several candidates and
 * ends with the app's own settings page.
 */
object OemSettings {

    enum class StepId {
        /** Xiaomi, Oppo, Vivo, Huawei and others: let the app start in the background. */
        AUTOSTART,

        /** Per-app battery saver: set to "No restrictions" or "Unrestricted". */
        BATTERY,

        /** Xiaomi: "Show on lock screen" and "Display pop-up windows while running in background". */
        LOCK_SCREEN_POPUP,
    }

    data class Step(val id: StepId, val intents: List<Intent>)

    val manufacturer: String get() = Build.MANUFACTURER.lowercase(Locale.ROOT)

    fun isAggressiveBrand(): Boolean = AGGRESSIVE.any { manufacturer.contains(it) }

    fun steps(context: Context): List<Step> {
        val pkg = context.packageName
        val details = appDetailsIntent(context)
        val m = manufacturer
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> listOf(
                Step(StepId.AUTOSTART, listOf(component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"), details)),
                Step(
                    StepId.BATTERY,
                    listOf(
                        component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                            .putExtra("package_name", pkg)
                            .putExtra("package_label", appLabel(context)),
                        details,
                    ),
                ),
                Step(
                    StepId.LOCK_SCREEN_POPUP,
                    listOf(
                        Intent("miui.intent.action.APP_PERM_EDITOR")
                            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                            .putExtra("extra_pkgname", pkg),
                        details,
                    ),
                ),
            )
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> listOf(
                Step(
                    StepId.AUTOSTART,
                    listOf(
                        component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                        component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                        component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                        component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                        details,
                    ),
                ),
                Step(StepId.BATTERY, listOf(details)),
            )
            m.contains("vivo") || m.contains("iqoo") -> listOf(
                Step(
                    StepId.AUTOSTART,
                    listOf(
                        component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                        component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                        component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                        details,
                    ),
                ),
                Step(
                    StepId.BATTERY,
                    listOf(
                        component("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
                        details,
                    ),
                ),
            )
            m.contains("huawei") || m.contains("honor") -> listOf(
                Step(
                    StepId.AUTOSTART,
                    listOf(
                        component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                        component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                        component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                        details,
                    ),
                ),
            )
            m.contains("samsung") -> listOf(
                Step(
                    StepId.BATTERY,
                    listOf(
                        component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                        component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                        details,
                    ),
                ),
            )
            m.contains("asus") -> listOf(
                Step(
                    StepId.AUTOSTART,
                    listOf(
                        component("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
                        component("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
                        details,
                    ),
                ),
            )
            m.contains("tecno") || m.contains("infinix") || m.contains("itel") -> listOf(
                Step(
                    StepId.AUTOSTART,
                    listOf(component("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"), details),
                ),
            )
            else -> emptyList()
        }
    }

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    private fun component(pkg: String, cls: String): Intent = Intent().setComponent(ComponentName(pkg, cls))

    private fun appLabel(context: Context): String =
        context.applicationInfo.loadLabel(context.packageManager).toString()

    private val AGGRESSIVE = listOf(
        "xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "iqoo",
        "huawei", "honor", "samsung", "asus", "tecno", "infinix", "itel",
    )
}
