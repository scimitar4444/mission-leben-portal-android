package de.missionleben.portal.push

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

data class PushReliabilityStatus(
    val hasSubscription: Boolean,
    val notificationsAllowed: Boolean,
    val batteryExempt: Boolean,
    val manufacturer: String,
) {
    val samsung: Boolean get() = manufacturer.equals("samsung", ignoreCase = true)
    val tcl: Boolean get() = manufacturer.equals("tcl", ignoreCase = true) ||
        manufacturer.equals("tct", ignoreCase = true)
}

object PushReliabilityPolicy {
    fun shouldPrompt(
        signedIn: Boolean,
        configured: Boolean,
        alreadyPrompted: Boolean,
        notificationPermissionRequested: Boolean,
        status: PushReliabilityStatus,
    ): Boolean = signedIn && configured && !alreadyPrompted &&
        ((!status.notificationsAllowed && notificationPermissionRequested) ||
            (status.hasSubscription && !status.batteryExempt))
}

class PushReliabilitySettings(private val context: Context) {
    private val preferences = context.getSharedPreferences("mission_leben_push", Context.MODE_PRIVATE)

    var promptShown: Boolean
        get() = preferences.getBoolean("reliability_prompt_shown", false)
        set(value) = preferences.edit().putBoolean("reliability_prompt_shown", value).apply()

    fun status(): PushReliabilityStatus {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return PushReliabilityStatus(
            hasSubscription = NtfyCredentialVault(context).load() != null,
            notificationsAllowed = notifications.areNotificationsEnabled() &&
                notifications.getNotificationChannel("connection")?.importance != NotificationManager.IMPORTANCE_NONE,
            batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
            manufacturer = Build.MANUFACTURER,
        )
    }

    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        startOrAppDetails(intent)
    }

    fun requestBatteryExemption() {
        // Android owns this confirmation. No setting is silently changed by the app.
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        startOrAppDetails(intent)
    }

    fun openManufacturerSettings() {
        val intent = if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
                .setPackage("com.samsung.android.lool")
                .putExtra("activity_type", 2) // Samsung: Never sleeping apps.
        } else {
            appDetailsIntent()
        }
        startOrAppDetails(intent)
    }

    private fun startOrAppDetails(intent: Intent) {
        if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        runCatching { context.startActivity(appDetailsIntent()) }
    }

    private fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
