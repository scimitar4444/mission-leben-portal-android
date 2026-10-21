package de.missionleben.portal.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnnouncementNotificationTest {
    private lateinit var context: Application
    private lateinit var manager: NotificationManager
    private val announcement = "announcement-event-0123456789"
    private val calendar = "calendar-event-0123456789"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        listOf("mission_leben_push", "mission_leben_settings", "mission_leben_unread_notifications",
            "notice_inbox", "notification_navigation_targets").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AppPreferences(context).apply {
            deviceMode = DeviceMode.PERSONAL
            deviceId = "synthetic-device"
            enrollmentState = EnrollmentState.TRUSTED
        }
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        PushManager.createChannels(context)
    }

    private fun receive(action: PushAction, id: String) =
        PushEventDispatcher.dispatch(context, PushCommand.Fetch(id, action, "1"))

    @Test fun announcementsHaveTheirOwnChannelAndDoNotReplaceAppointments() {
        receive(PushAction.OPEN_CALENDAR, calendar)
        receive(PushAction.OPEN_ANNOUNCEMENTS, announcement)
        assertEquals(setOf("calendar", "announcements"), manager.activeNotifications.map { it.notification.channelId }.toSet())
        assertEquals(1, UnreadNotificationStore(context).counts().zimbra)
        NotificationPresenter.dismissApplication(context, NotificationBadgeTarget.ANNOUNCEMENTS)
        assertEquals(listOf("calendar"), manager.activeNotifications.map { it.notification.channelId })
        assertEquals(1, UnreadNotificationStore(context).counts().zimbra)
    }

    @Test fun importantAnnouncementsBypassCommunicationOffButCannotDisableAndroidPermissions() {
        PushRegistrationStore(context).communicationNotificationsEnabled = false
        receive(PushAction.OPEN_MAIL, calendar)
        receive(PushAction.OPEN_ANNOUNCEMENTS, announcement)
        assertEquals(listOf("announcements"), manager.activeNotifications.map { it.notification.channelId })
        assertTrue(PushRegistrationStore(context).deliveryAllowed(PushAction.OPEN_ANNOUNCEMENTS))
        assertFalse(PushRegistrationStore(context).deliveryAllowed(PushAction.OPEN_TALK))
    }

    @Test fun acknowledgedAnnouncementCannotBeRepostedByReplay() {
        receive(PushAction.OPEN_ANNOUNCEMENTS, announcement)
        NotificationPresenter.dismissApplication(context, NotificationBadgeTarget.ANNOUNCEMENTS)
        receive(PushAction.OPEN_ANNOUNCEMENTS, announcement)
        assertTrue(manager.activeNotifications.isEmpty())
        // The bell already contains actual announcements, not a duplicate push-history entry.
        assertTrue(NoticeInboxStore(context).notices().isEmpty())
    }

    @Test fun announcementPrivacyCanBeReducedWithoutLeavingOldBodyInTray() {
        PushRegistrationStore(context).personalPrivacy = NotificationPrivacy.DETAILED
        PushEventDispatcher.recordUnread(context, PushAction.OPEN_ANNOUNCEMENTS, announcement)
        val detail = NotificationDetail(announcement, PushAction.OPEN_ANNOUNCEMENTS,
            "Important notice", "Synthetic maintenance", "PRIVATE-SYNTHETIC-BODY", "12", null, null)
        NotificationPresenter.showRich(context, detail.action, announcement, detail, "synthetic-device")
        assertTrue(manager.activeNotifications.single().notification.extras.toString().contains("PRIVATE-SYNTHETIC-BODY"))
        NotificationPresenter.setPersonalPrivacy(context, NotificationPrivacy.STANDARD)
        val notification = manager.activeNotifications.single().notification
        assertFalse(notification.extras.toString().contains("PRIVATE-SYNTHETIC-BODY"))
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertFalse(notification.publicVersion.extras.toString().contains("Synthetic maintenance"))
    }

    @Test fun announcementTargetsAreOnlyIdentifiersNeverLinks() {
        assertEquals("12", NotificationDetail.notificationTarget(PushAction.OPEN_ANNOUNCEMENTS, "12"))
        for (invalid in listOf("0", "-1", "https://evil.invalid/", "12/../mail", "999999999999")) {
            assertEquals("", NotificationDetail.notificationTarget(PushAction.OPEN_ANNOUNCEMENTS, invalid))
        }
    }

    @Test fun delayedDetailCannotResurrectAnAnnouncementReadOnTheHomeScreen() {
        val preferences = AppPreferences(context)
        preferences.rememberPersonalIdentity("synthetic-person", "person", 1L)
        receive(PushAction.OPEN_ANNOUNCEMENTS, announcement)
        preferences.markAnnouncementRead("synthetic-person", 12L)
        val detail = NotificationDetail(announcement, PushAction.OPEN_ANNOUNCEMENTS,
            "Important notice", "Already read", "Body", "12", null, null)
        NotificationPresenter.showRich(context, detail.action, announcement, detail, "synthetic-device")
        assertTrue(manager.activeNotifications.isEmpty())
        assertFalse(UnreadNotificationStore(context).isUnread(detail.action, announcement))
    }
}
