package de.missionleben.portal.push

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.missionleben.portal.MainActivity
import de.missionleben.portal.R
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.model.EnrollmentState

object NotificationPresenter {
    @Synchronized
    fun showGeneric(context: Context, action: PushAction, eventId: String? = null) {
        if (eventId != null && !UnreadNotificationStore(context).isUnread(action, eventId)) return
        show(context, action, eventId, detail = null, privacy = NotificationPrivacy.MINIMAL)
    }

    @Synchronized
    fun showRich(
        context: Context,
        expectedAction: PushAction,
        eventId: String,
        detail: NotificationDetail,
        expectedDeviceId: String,
    ) {
        if (detail.eventId != eventId || detail.action != expectedAction || detail.isExpired()) return
        // ntfy may fetch details BEFORE showing a generic notification. Use the
        // acknowledged event state, not presence in Android's notification tray.
        if (!UnreadNotificationStore(context).isUnread(expectedAction, eventId)) return
        // Re-read AFTER the network fetch. A setting/profile change while the
        // request was in flight must never reintroduce an old, richer preview.
        val preferences = AppPreferences(context)
        val store = PushRegistrationStore(context)
        if (preferences.deviceId != expectedDeviceId ||
            preferences.enrollmentState != EnrollmentState.TRUSTED || !store.communicationAllowed()
        ) return
        val privacy = NotificationPrivacy.effective(preferences.deviceMode, store.personalPrivacy)
        show(context, expectedAction, eventId, detail, privacy)
    }

    @Synchronized
    fun setPersonalPrivacy(context: Context, privacy: NotificationPrivacy) {
        PushRegistrationStore(context).personalPrivacy = privacy
        reconcilePrivacy(context)
    }

    /** Also called after an OTA/process restart to sanitize pre-update notifications. */
    @Synchronized
    fun reconcilePrivacy(context: Context) {
        val privacy = NotificationPrivacy.effective(
            AppPreferences(context).deviceMode, PushRegistrationStore(context).personalPrivacy,
        )
        if (privacy == NotificationPrivacy.DETAILED) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            cancelCommunication(context)
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.forEach { active ->
            val old = active.notification
            val action = PushAction.entries.firstOrNull {
                it.channelId == old.channelId && it.channelId in COMMUNICATION_CHANNELS
            } ?: return@forEach
            if (old.extras.getString(EXTRA_PRIVACY) == privacy.wireName &&
                old.extras.getInt(EXTRA_CONTENT_POLICY) == CONTENT_POLICY_VERSION
            ) return@forEach

            val genericTitle = context.getString(action.titleRes)
            val genericBody = context.getString(action.bodyRes)
            val minimal = privacy == NotificationPrivacy.MINIMAL
            val title = if (minimal) genericTitle else
                old.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { genericTitle }
            // Old mail/calendar collapsed text was only subject/time+location.
            // Legacy Talk text mixed in its preview: do not reuse that string.
            val summary = if (minimal) genericBody else
                (old.extras.getString(EXTRA_SAFE_SUMMARY)
                    ?: if (action != PushAction.OPEN_TALK)
                        old.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() else null)
                    .orEmpty().ifBlank { genericBody }
            // Rebuild from an allowlist, NOT recoverBuilder: BigText/style extras
            // may still contain the old mail body even after setting plain text.
            val clean = NotificationCompat.Builder(context, action.channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title).setContentText(summary)
                .setContentIntent(old.contentIntent)
                .setWhen(old.`when`).setShowWhen(old.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, false))
                .setAutoCancel(true).setOnlyAlertOnce(true).setSilent(true)
                .setCategory(category(action)).setGroup("mission_leben_${action.channelId}")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(context, action.channelId)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(genericTitle).setContentText(genericBody).build())
                .addExtras(contentPolicyExtras(privacy, summary))
                .build()
            manager.notify(active.tag, active.id, clean)
        }
    }

    @Synchronized
    fun cancel(context: Context, eventId: String) {
        val id = notificationId(eventId)
        context.getSystemService(JobScheduler::class.java).cancel(id)
        NotificationManagerCompat.from(context).cancel(id)
        NotificationTargetStore(context).remove(eventId)
    }

    /** Only locally acknowledge this application; never change server read state. */
    @Synchronized
    fun dismissApplication(context: Context, target: NotificationBadgeTarget) {
        val unread = UnreadNotificationStore(context)
        val eventIds = unread.eventIds(target)
        unread.clear(target) // Keep deduplication history so replay cannot re-post it.
        val channels = PushAction.entries
            .filter { NotificationBadgeTarget.fromAction(it) == target }
            .map { it.channelId }.toSet()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications
            .filter { it.notification.channelId in channels }
            .forEach { manager.cancel(it.tag, it.id) }
        RichNotificationJobService.cancelForApplication(context, target)
        val targets = NotificationTargetStore(context)
        eventIds.forEach(targets::remove)
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

    fun cancelCommunication(context: Context) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.activeNotifications
            .filter { it.notification.channelId in COMMUNICATION_CHANNELS }
            .forEach { manager.cancel(it.id) }
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
        val preview = if (rich) privacy.visiblePreview(action, detail.preview) else ""
        val genericTitle = context.getString(action.titleRes)
        val genericBody = context.getString(action.bodyRes)
        val title = if (rich) detail.title.ifBlank { genericTitle } else genericTitle
        val contextSummary = if (rich) detail.summary.ifBlank { genericBody } else genericBody
        val summary = if (
            rich &&
            action == PushAction.OPEN_TALK &&
            preview.isNotBlank()
        ) {
            listOf(contextSummary, preview).filter(String::isNotBlank).joinToString(" · ")
        } else {
            contextSummary
        }
        if (eventId != null && detail?.targetId?.isNotBlank() == true) {
            NotificationTargetStore(context).put(eventId, action, detail.targetId)
        }
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
            .addExtras(contentPolicyExtras(privacy, contextSummary))

        detail?.displayAtMillis?.let {
            builder.setWhen(it).setShowWhen(true)
        }
        if (preview.isNotBlank()) {
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOf(contextSummary, preview).filter(String::isNotBlank).joinToString("\n"),
                ),
            )
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

    private val COMMUNICATION_CHANNELS = setOf("mail", "calendar", "talk")
    private const val EXTRA_PRIVACY = "de.missionleben.portal.notification_privacy"
    private const val EXTRA_SAFE_SUMMARY = "de.missionleben.portal.notification_summary"
    private const val EXTRA_CONTENT_POLICY = "de.missionleben.portal.notification_content_policy"
    private const val CONTENT_POLICY_VERSION = 1

    private fun contentPolicyExtras(privacy: NotificationPrivacy, summary: String) = Bundle().apply {
        putString(EXTRA_PRIVACY, privacy.wireName)
        putString(EXTRA_SAFE_SUMMARY, summary)
        putInt(EXTRA_CONTENT_POLICY, CONTENT_POLICY_VERSION)
    }
}
