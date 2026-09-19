package de.missionleben.portal.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.R

object PushManager {
    val configured: Boolean
        get() = listOf(
            BuildConfig.FIREBASE_APPLICATION_ID,
            BuildConfig.FIREBASE_API_KEY,
            BuildConfig.FIREBASE_PROJECT_ID,
            BuildConfig.FIREBASE_SENDER_ID,
        ).all(String::isNotBlank)

    fun initialize(context: Context) {
        createChannels(context)
        if (!configured) return

        if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
            val options = FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(context, options)
        }

        FirebaseMessaging.getInstance().setAutoInitEnabled(false)
    }

    fun register() {
        if (!configured) return
        FirebaseMessaging.getInstance().register().addOnFailureListener { error ->
            Log.w(TAG, "FCM installation registration failed", error)
        }
    }

    fun unregister() {
        if (!configured) return
        FirebaseMessaging.getInstance().unregister().addOnFailureListener { error ->
            Log.w(TAG, "FCM installation unregistration failed", error)
        }
    }

    fun installationId(context: Context): String? = PushRegistrationStore(context).installationId

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
            ),
        )
    }

    private const val TAG = "PortalPush"
}
