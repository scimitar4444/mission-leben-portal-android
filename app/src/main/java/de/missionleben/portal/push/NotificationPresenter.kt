package de.missionleben.portal.push

import android.Manifest
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.missionleben.portal.MainActivity
import de.missionleben.portal.R

object NotificationPresenter {
    fun showGeneric(context: Context, action: PushAction, eventId: String? = null) {
        show(context, action, eventId, detail = null, privacy = NotificationPrivacy.MINIMAL)
    }

    fun showRich(
        context: Context,
        expectedAction: PushAction,
        eventId: String,
        detail: NotificationDetail,
        privacy: NotificationPrivacy,
    ) {
        if (detail.eventId != eventId || detail.action != expectedAction || detail.isExpired()) return
        show(context, expectedAction, eventId, detail, privacy)
    }

    fun cancel(context: Context, eventId: String) {
        val id = notificationId(eventId)
        context.getSystemService(JobScheduler::class.java).cancel(id)
        NotificationManagerCompat.from(context).cancel(id)
    }

    fun showLoginApproval(context: Context, requestId: String) {
        if (
            !PushCommand.validEventId(requestId) ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notificationId = loginApprovalNotificationId(requestId)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(PushEventDispatcher.EXTRA_LOGIN_APPROVAL_REQUEST_ID, requestId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = context.getString(R.string.login_approval_title)
        val body = context.getString(R.string.login_approval_body)
        val notification = NotificationCompat.Builder(context, "security")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, "security")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(body)
                    .build(),
            )
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun cancelLoginApproval(context: Context, requestId: String) {
        if (!PushCommand.validEventId(requestId)) return
        NotificationManagerCompat.from(context).cancel(loginApprovalNotificationId(requestId))
    }

    private fun show(
        context: Context,
        action: PushAction,
        eventId: String?,
        detail: NotificationDetail?,
        privacy: NotificationPrivacy,
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val rich = detail != null && privacy != NotificationPrivacy.MINIMAL
        val genericTitle = context.getString(action.titleRes)
        val genericBody = context.getString(action.bodyRes)
        val title = if (rich) detail.title.ifBlank { genericTitle } else genericTitle
        val summary = if (rich) detail.summary.ifBlank { genericBody } else genericBody
        val intent = Intent(context, MainActivity::class.java).apply {
            setAction(Intent.ACTION_VIEW)
            data = Uri.Builder()
                .scheme(context.packageName)
                .authority("notification")
                .appendPath(action.wireName)
                .appendPath(eventId ?: "latest")
                .build()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(PushEventDispatcher.EXTRA_PUSH_ACTION, action.wireName)
            if (eventId != null) putExtra(PushEventDispatcher.EXTRA_EVENT_ID, eventId)
        }
        val requestCode = eventId?.let(::notificationId) ?: action.notificationId
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = NotificationCompat.Builder(context, action.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(genericTitle)
            .setContentText(genericBody)
            .build()
        val builder = NotificationCompat.Builder(context, action.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summary)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(category(action))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setGroup("mission_leben_${action.channelId}")

        detail?.displayAtMillis?.let {
            builder.setWhen(it).setShowWhen(true)
        }
        if (rich && privacy == NotificationPrivacy.DETAILED && detail.preview.isNotBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(listOf(summary, detail.preview).filter(String::isNotBlank).joinToString("\n")))
        }

        NotificationManagerCompat.from(context).notify(
            eventId?.let(::notificationId) ?: action.notificationId,
            builder.build(),
        )
    }

    internal fun notificationId(eventId: String): Int = 1_000 + (eventId.hashCode() and 0x0fffffff)

    internal fun loginApprovalNotificationId(requestId: String): Int =
        300_000_000 + (requestId.hashCode() and 0x00ffffff)

    private fun category(action: PushAction): String = when (action) {
        PushAction.OPEN_MAIL, PushAction.OPEN_TALK -> NotificationCompat.CATEGORY_MESSAGE
        PushAction.OPEN_CALENDAR -> NotificationCompat.CATEGORY_EVENT
        PushAction.REFRESH_SECURITY_STATE -> NotificationCompat.CATEGORY_STATUS
    }
}
