package de.missionleben.portal.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import de.missionleben.portal.MainActivity
import de.missionleben.portal.R

// Firebase Messaging 25 uses installation IDs through onRegistered; lint still checks the old token callback.
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class PortalFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        PushRegistrationStore(this).installationId = installationId
        sendBroadcast(Intent(ACTION_REGISTRATION_CHANGED).setPackage(packageName))
    }

    override fun onUnregistered(installationId: String) {
        val store = PushRegistrationStore(this)
        if (store.installationId == installationId) store.installationId = null
        sendBroadcast(Intent(ACTION_REGISTRATION_CHANGED).setPackage(packageName))
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val action = PushAction.fromWireName(message.data[DATA_ACTION]) ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_PUSH_ACTION, action.wireName)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            action.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, action.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(action.title)
            .setContentText(action.body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

        NotificationManagerCompat.from(this).notify(action.notificationId, notification)
    }

    companion object {
        const val EXTRA_PUSH_ACTION = "de.missionleben.portal.PUSH_ACTION"
        const val ACTION_REGISTRATION_CHANGED = "de.missionleben.portal.PUSH_REGISTRATION_CHANGED"
        private const val DATA_ACTION = "action"
    }
}
