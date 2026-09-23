package de.missionleben.portal.push

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import de.missionleben.portal.R
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Local Android notification/job state only, no network or real account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationDismissalTest {
    private lateinit var context: Application
    private lateinit var manager: NotificationManager
    private lateinit var scheduler: JobScheduler
    private lateinit var unread: UnreadNotificationStore
    private val mail = "mail-dismissal-0123456789"
    private val calendar = "calendar-dismissal-0123456789"
    private val talk = "talk-dismissal-0123456789"
    private val device = "synthetic-device"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        listOf("mission_leben_unread_notifications", "mission_leben_push",
            "mission_leben_settings", "notification_navigation_targets").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()
        listOf("mail", "calendar", "talk", "security", "connection").forEach {
            manager.createNotificationChannel(NotificationChannel(it, it, NotificationManager.IMPORTANCE_DEFAULT))
        }
        AppPreferences(context).apply {
            deviceMode = DeviceMode.PERSONAL
            deviceId = device
            enrollmentState = EnrollmentState.TRUSTED
        }
        unread = UnreadNotificationStore(context)
    }

    private fun receive(action: PushAction, eventId: String) {
        PushEventDispatcher.dispatch(context, PushCommand.Fetch(eventId, action, "1"))
    }

    private fun activeChannels() = manager.activeNotifications.map { it.notification.channelId }.toSet()
    private fun jobIds() = scheduler.allPendingJobs.map { it.id }.toSet()

    private fun showUnrelated() {
        manager.notify(501, NotificationCompat.Builder(context, "security")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Login request").build())
        manager.notify(502, NotificationCompat.Builder(context, "connection")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Push connection").build())
    }

    @Test fun openingZimbraClearsMailAndCalendarButKeepsTalkAndSecurity() {
        receive(PushAction.OPEN_MAIL, mail)
        receive(PushAction.OPEN_CALENDAR, calendar)
        receive(PushAction.OPEN_TALK, talk)
        showUnrelated()
        NotificationTargetStore(context).put(mail, PushAction.OPEN_MAIL, "42")
        NotificationTargetStore(context).put(talk, PushAction.OPEN_TALK, "room1234")

        NotificationPresenter.dismissApplication(context, NotificationBadgeTarget.ZIMBRA)

        assertEquals(setOf("talk", "security", "connection"), activeChannels())
        assertEquals(NotificationBadgeCounts(zimbra = 0, talk = 1), unread.counts())
        assertEquals(setOf(NotificationPresenter.notificationId(talk)), jobIds())
        assertEquals("", NotificationTargetStore(context).get(mail, PushAction.OPEN_MAIL))
        assertEquals("room1234", NotificationTargetStore(context).get(talk, PushAction.OPEN_TALK))
    }

    @Test fun openingTalkKeepsZimbraAndSecurityNotifications() {
        receive(PushAction.OPEN_MAIL, mail)
        receive(PushAction.OPEN_CALENDAR, calendar)
        receive(PushAction.OPEN_TALK, talk)
        showUnrelated()
        NotificationPresenter.dismissApplication(context, NotificationBadgeTarget.TALK)
        assertEquals(setOf("mail", "calendar", "security", "connection"), activeChannels())
        assertEquals(NotificationBadgeCounts(zimbra = 2, talk = 0), unread.counts())
        assertEquals(setOf(NotificationPresenter.notificationId(mail), NotificationPresenter.notificationId(calendar)), jobIds())
    }

    @Test fun legacyAndTaggedNotificationsAreRemovedEvenWithoutBadgeEntries() {
        NotificationPresenter.showGeneric(context, PushAction.OPEN_MAIL)
        manager.notify("legacy-summary", 503, NotificationCompat.Builder(context, "mail")
            .setSmallIcon(R.drawable.ic_notification).setGroup("mission_leben_mail").setGroupSummary(true).build())
        assertEquals(0, unread.counts().zimbra)
        NotificationPresenter.dismissApplication(context, NotificationBadgeTarget.ZIMBRA)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun inFlightRichResponseCannotRestoreDismissedApplicationNotification() {
        receive(PushAction.OPEN_MAIL, mail)
        val response = NotificationDetail(mail, PushAction.OPEN_MAIL, "Sender", "Subject", "Body", "42", null, null)
        NotificationPresenter.dismissApplication(context, NotificationBadgeTarget.ZIMBRA)
        NotificationPresenter.showRich(context, PushAction.OPEN_MAIL, mail, response, device)
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(0, unread.counts().zimbra)
        assertEquals("", NotificationTargetStore(context).get(mail, PushAction.OPEN_MAIL))
    }

    @Test fun replayOfAcknowledgedEventDoesNotRepostOrScheduleAnotherJob() {
        receive(PushAction.OPEN_TALK, talk)
        NotificationPresenter.dismissApplication(context, NotificationBadgeTarget.TALK)
        receive(PushAction.OPEN_TALK, talk)
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(scheduler.allPendingJobs.isEmpty())
        assertEquals(0, UnreadNotificationStore(context).counts().talk)
    }

    @Test fun newMessagesStillNotifyAndReturningFromApplicationClearsThemAgain() {
        receive(PushAction.OPEN_TALK, talk)
        NotificationPresenter.dismissApplication(context, NotificationBadgeTarget.TALK)
        receive(PushAction.OPEN_TALK, "new-talk-after-open-0123456789")
        assertEquals(setOf("talk"), activeChannels())
        assertEquals(1, unread.counts().talk)
        NotificationPresenter.dismissApplication(context, NotificationBadgeTarget.TALK)
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(0, unread.counts().talk)
    }

    @Test fun jobsOfOtherComponentsAreNeverCancelled() {
        receive(PushAction.OPEN_MAIL, mail)
        val unrelatedId = 700
        scheduler.schedule(JobInfo.Builder(unrelatedId, ComponentName(context, DeviceSecurityRefreshJobService::class.java))
            .setExtras(PersistableBundle().apply { putString("event_type", PushAction.OPEN_MAIL.wireName) })
            .setMinimumLatency(1000).build())
        NotificationPresenter.dismissApplication(context, NotificationBadgeTarget.ZIMBRA)
        assertEquals(setOf(unrelatedId), jobIds())
    }

    @Test fun lateNtfyGenericFallbackAndRetryCannotRestoreDismissedApplicationNotification() {
        receive(PushAction.OPEN_MAIL, mail)
        NotificationPresenter.dismissApplication(context, NotificationBadgeTarget.ZIMBRA)
        NotificationPresenter.showGeneric(context, PushAction.OPEN_MAIL, mail)
        assertFalse(RichNotificationJobService.schedule(context, PushCommand.Fetch(mail, PushAction.OPEN_MAIL, "1")))
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test fun ntfyRichFirstPathStillDisplaysNewEventWithoutGenericPlaceholder() {
        PushEventDispatcher.recordUnread(context, PushAction.OPEN_MAIL, mail)
        assertTrue(manager.activeNotifications.isEmpty())
        NotificationPresenter.showRich(context, PushAction.OPEN_MAIL, mail,
            NotificationDetail(mail, PushAction.OPEN_MAIL, "Sender", "Subject", "Body", "42", null, null), device)
        assertEquals(1, manager.activeNotifications.size)
        assertEquals("Sender", manager.activeNotifications.single().notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
    }

    @Test fun ntfyRichFirstResponseAfterApplicationVisitIsDiscarded() {
        PushEventDispatcher.recordUnread(context, PushAction.OPEN_TALK, talk)
        NotificationPresenter.dismissApplication(context, NotificationBadgeTarget.TALK)
        NotificationPresenter.showRich(context, PushAction.OPEN_TALK, talk,
            NotificationDetail(talk, PushAction.OPEN_TALK, "Sender", "Chat", "Body", "room1234", null, null), device)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun unreadDuplicateCanStillEnrichTheExistingNotification() {
        receive(PushAction.OPEN_MAIL, mail)
        receive(PushAction.OPEN_MAIL, mail)
        NotificationPresenter.showRich(context, PushAction.OPEN_MAIL, mail,
            NotificationDetail(mail, PushAction.OPEN_MAIL, "Sender", "Subject", "Body", "42", null, null), device)
        assertEquals(1, manager.activeNotifications.size)
        assertEquals(1, unread.counts().zimbra)
        assertEquals("Sender", manager.activeNotifications.single().notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
    }
}
