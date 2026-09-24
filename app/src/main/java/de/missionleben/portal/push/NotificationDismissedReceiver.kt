package de.missionleben.portal.push

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/** A user swipe acknowledges only the matching local badge entry. */
class NotificationDismissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISSED) return
        val action = PushAction.fromWireName(intent.getStringExtra(PushEventDispatcher.EXTRA_PUSH_ACTION))
            ?: return
        val eventId = intent.getStringExtra(PushEventDispatcher.EXTRA_EVENT_ID)
            ?.takeIf(PushCommand::validEventId) ?: return
        if (NotificationBadgeTarget.fromAction(action) == null) return
        val expectedData = dismissalUri(context, action, eventId)
        if (intent.data != expectedData) return
        PushEventDispatcher.acknowledgeDismissal(context, action, eventId)
    }

    companion object {
        private const val ACTION_DISMISSED = "de.missionleben.portal.NOTIFICATION_DISMISSED"

        fun pendingIntent(context: Context, action: PushAction, eventId: String): PendingIntent {
            val intent = Intent(context, NotificationDismissedReceiver::class.java).apply {
                setAction(ACTION_DISMISSED)
                data = dismissalUri(context, action, eventId)
                putExtra(PushEventDispatcher.EXTRA_PUSH_ACTION, action.wireName)
                putExtra(PushEventDispatcher.EXTRA_EVENT_ID, eventId)
            }
            return PendingIntent.getBroadcast(
                context,
                NotificationPresenter.notificationId(eventId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun dismissalUri(context: Context, action: PushAction, eventId: String): Uri =
            Uri.Builder()
                .scheme(context.packageName)
                .authority("notification-dismissed")
                .appendPath(action.wireName)
                .appendPath(eventId)
                .build()
    }
}
