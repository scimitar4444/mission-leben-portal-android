package de.missionleben.portal.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.R

object PushManager {
    const val CONNECTION_NOTIFICATION_ID = 290_104_010

    val configured: Boolean
        get() = BuildConfig.NTFY_PUBLIC_BASE_URL.startsWith("https://")

    fun initialize(context: Context) {
        createChannels(context)
        NotificationPresenter.reconcilePrivacy(context)
        start(context)
    }

    fun start(context: Context) {
        if (
            !configured ||
            NtfyCredentialVault(context).load() == null ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NtfySubscriberService::class.java),
            )
        }
    }

    fun stop(context: Context, clearCredentials: Boolean = true) {
        context.stopService(Intent(context, NtfySubscriberService::class.java))
        if (clearCredentials) NtfyCredentialVault(context).clear()
    }

    private fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel("mail", context.getString(R.string.channel_mail_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.channel_mail_description)
                },
                NotificationChannel("calendar", context.getString(R.string.channel_calendar_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_calendar_description)
                },
                NotificationChannel("talk", context.getString(R.string.channel_talk_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.channel_talk_description)
                },
                NotificationChannel("security", context.getString(R.string.channel_security_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_security_description)
                },
                NotificationChannel("connection", context.getString(R.string.channel_connection_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.channel_connection_description)
                    enableVibration(false)
                    setSound(null, null)
                    setShowBadge(false)
                },
            ),
        )
    }
}
