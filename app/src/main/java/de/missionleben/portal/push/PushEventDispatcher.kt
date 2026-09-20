package de.missionleben.portal.push

import android.content.Context
import android.content.Intent
import de.missionleben.portal.MissionLebenApplication

object PushEventDispatcher {
    fun dispatch(context: Context, command: PushCommand) {
        when (command) {
            is PushCommand.Legacy -> {
                if (command.action == PushAction.REFRESH_SECURITY_STATE) {
                    DeviceSecurityRefreshJobService.schedule(context)
                } else {
                    NotificationPresenter.showGeneric(context, command.action)
                }
            }
            is PushCommand.Fetch -> {
                NotificationPresenter.showGeneric(context, command.eventType, command.eventId)
                RichNotificationJobService.schedule(context, command)
            }
            is PushCommand.Cancel -> NotificationPresenter.cancel(context, command.eventId)
            is PushCommand.LoginApproval -> {
                if (!MissionLebenApplication.portalVisible) {
                    NotificationPresenter.showLoginApproval(context, command.requestId)
                }
                context.sendBroadcast(
                    Intent(ACTION_LOGIN_APPROVAL_CHANGED)
                        .setPackage(context.packageName)
                        .putExtra(EXTRA_LOGIN_APPROVAL_REQUEST_ID, command.requestId),
                )
            }
        }
    }

    const val EXTRA_PUSH_ACTION = "de.missionleben.portal.PUSH_ACTION"
    const val EXTRA_EVENT_ID = "de.missionleben.portal.EVENT_ID"
    const val EXTRA_LOGIN_APPROVAL_REQUEST_ID = "de.missionleben.portal.LOGIN_APPROVAL_REQUEST_ID"
    const val EXTRA_ENROLLMENT_STATE = "de.missionleben.portal.ENROLLMENT_STATE"
    const val ACTION_REGISTRATION_CHANGED = "de.missionleben.portal.PUSH_REGISTRATION_CHANGED"
    const val ACTION_LOGIN_APPROVAL_CHANGED = "de.missionleben.portal.LOGIN_APPROVAL_CHANGED"
    const val ACTION_SECURITY_STATE_CHANGED = "de.missionleben.portal.SECURITY_STATE_CHANGED"
}
