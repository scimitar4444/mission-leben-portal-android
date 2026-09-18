package de.missionleben.portal.push

import android.annotation.SuppressLint
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

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
        when (val command = PushCommand.parse(message.data)) {
            is PushCommand.Legacy -> NotificationPresenter.showGeneric(this, command.action)
            is PushCommand.Fetch -> {
                NotificationPresenter.showGeneric(this, command.eventType, command.eventId)
                RichNotificationJobService.schedule(this, command)
            }
            is PushCommand.Cancel -> NotificationPresenter.cancel(this, command.eventId)
            null -> Unit
        }
    }

    companion object {
        const val EXTRA_PUSH_ACTION = "de.missionleben.portal.PUSH_ACTION"
        const val EXTRA_EVENT_ID = "de.missionleben.portal.EVENT_ID"
        const val ACTION_REGISTRATION_CHANGED = "de.missionleben.portal.PUSH_REGISTRATION_CHANGED"
    }
}
