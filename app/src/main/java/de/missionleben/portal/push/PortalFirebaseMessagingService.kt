package de.missionleben.portal.push

import android.annotation.SuppressLint
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import de.missionleben.portal.MissionLebenApplication

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
            is PushCommand.LoginApproval -> {
                if (!MissionLebenApplication.portalVisible) {
                    NotificationPresenter.showLoginApproval(this, command.requestId)
                }
                sendBroadcast(
                    Intent(ACTION_LOGIN_APPROVAL_CHANGED)
                        .setPackage(packageName)
                        .putExtra(EXTRA_LOGIN_APPROVAL_REQUEST_ID, command.requestId),
                )
            }
            null -> Unit
        }
    }

    companion object {
        const val EXTRA_PUSH_ACTION = "de.missionleben.portal.PUSH_ACTION"
        const val EXTRA_EVENT_ID = "de.missionleben.portal.EVENT_ID"
        const val EXTRA_LOGIN_APPROVAL_REQUEST_ID = "de.missionleben.portal.LOGIN_APPROVAL_REQUEST_ID"
        const val ACTION_REGISTRATION_CHANGED = "de.missionleben.portal.PUSH_REGISTRATION_CHANGED"
        const val ACTION_LOGIN_APPROVAL_CHANGED = "de.missionleben.portal.LOGIN_APPROVAL_CHANGED"
    }
}
