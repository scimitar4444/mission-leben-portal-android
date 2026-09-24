package de.missionleben.portal.push

import android.content.Context
import android.content.Intent
import android.app.job.JobScheduler
import de.missionleben.portal.MissionLebenApplication

object PushEventDispatcher {
    fun dispatch(context: Context, command: PushCommand) {
        when (command) {
            is PushCommand.Legacy -> synchronized(NotificationPresenter) {
                if (command.action == PushAction.REFRESH_SECURITY_STATE) {
                    DeviceSecurityRefreshJobService.schedule(context)
                } else if (PushRegistrationStore(context).communicationAllowed()) {
                    val eventId = "legacy-${command.action.wireName}-${System.nanoTime()}"
                    recordUnread(context, command.action, eventId)
                    NotificationPresenter.showGeneric(context, command.action, eventId)
                }
            }
            // Serialize receive+record+post+schedule with application dismissal
            // and late rich responses; do not leave a job behind after clearing.
            is PushCommand.Fetch -> synchronized(NotificationPresenter) {
                if (!PushRegistrationStore(context).communicationAllowed()) return@synchronized
                recordUnread(context, command.eventType, command.eventId)
                if (!UnreadNotificationStore(context).isUnread(command.eventType, command.eventId)) {
                    return@synchronized
                }
                NotificationPresenter.showGeneric(context, command.eventType, command.eventId)
                RichNotificationJobService.schedule(context, command)
            }
            is PushCommand.Cancel -> synchronized(NotificationPresenter) {
                NotificationPresenter.cancel(context, command.eventId)
                if (UnreadNotificationStore(context).cancel(command.eventId)) notifyUnreadChanged(context)
            }
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

    fun recordUnread(context: Context, action: PushAction, eventId: String) = synchronized(NotificationPresenter) {
        if (UnreadNotificationStore(context).record(action, eventId)) notifyUnreadChanged(context)
    }

    fun acknowledgeDismissal(context: Context, action: PushAction, eventId: String) = synchronized(NotificationPresenter) {
        if (UnreadNotificationStore(context).cancel(action, eventId)) {
            context.getSystemService(JobScheduler::class.java)
                .cancel(NotificationPresenter.notificationId(eventId))
            NotificationTargetStore(context).remove(eventId)
            notifyUnreadChanged(context)
        }
    }

    private fun notifyUnreadChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_UNREAD_CHANGED).setPackage(context.packageName))
    }

    const val EXTRA_PUSH_ACTION = "de.missionleben.portal.PUSH_ACTION"
    const val EXTRA_EVENT_ID = "de.missionleben.portal.EVENT_ID"
    const val EXTRA_LOGIN_APPROVAL_REQUEST_ID = "de.missionleben.portal.LOGIN_APPROVAL_REQUEST_ID"
    const val EXTRA_ENROLLMENT_STATE = "de.missionleben.portal.ENROLLMENT_STATE"
    const val ACTION_REGISTRATION_CHANGED = "de.missionleben.portal.PUSH_REGISTRATION_CHANGED"
    const val ACTION_LOGIN_APPROVAL_CHANGED = "de.missionleben.portal.LOGIN_APPROVAL_CHANGED"
    const val ACTION_SECURITY_STATE_CHANGED = "de.missionleben.portal.SECURITY_STATE_CHANGED"
    const val ACTION_UNREAD_CHANGED = "de.missionleben.portal.UNREAD_CHANGED"
}
