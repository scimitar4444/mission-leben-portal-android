package de.missionleben.portal.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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

/** Synthetic notifications only; no network, production identity or token. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationPresenterTest {
    private lateinit var context: Application
    private lateinit var manager: NotificationManager
    private lateinit var store: PushRegistrationStore
    private lateinit var preferences: AppPreferences
    private val event = "event-notification-0123456789"
    private val device = "synthetic-device"
    private val secret = "PRIVATE-MAIL-BODY-NEVER-IN-STANDARD"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        context.getSharedPreferences("mission_leben_push", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("mission_leben_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("mission_leben_unread_notifications", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("notification_navigation_targets", Context.MODE_PRIVATE).edit().clear().commit()
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        listOf("mail", "calendar", "talk", "security", "connection").forEach {
            manager.createNotificationChannel(NotificationChannel(it, it, NotificationManager.IMPORTANCE_DEFAULT))
        }
        store = PushRegistrationStore(context)
        preferences = AppPreferences(context).apply {
            deviceMode = DeviceMode.PERSONAL
            deviceId = device
            enrollmentState = EnrollmentState.TRUSTED
        }
    }

    private fun detail(action: PushAction = PushAction.OPEN_MAIL, preview: String = secret) = NotificationDetail(
        event, action, "Sender Example", "Example subject", preview, "42", null, null,
    )

    private fun show(value: NotificationDetail = detail()) {
        PushEventDispatcher.recordUnread(context, value.action, event)
        NotificationPresenter.showRich(context, value.action, event, value, device)
    }

    private fun notification() = manager.activeNotifications.single { it.id == NotificationPresenter.notificationId(event) }.notification

    private fun assertNoPreview(value: Notification = notification()) {
        assertNull(value.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertFalse(value.extras.toString().contains(secret))
        assertFalse(value.publicVersion.extras.toString().contains(secret))
    }

    @Test fun standardStripsUnexpectedServerMailPreviewEvenWhenExpanded() {
        store.personalPrivacy = NotificationPrivacy.STANDARD
        show()
        assertEquals("Sender Example", notification().extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("Example subject", notification().extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNoPreview()
    }

    @Test fun detailedAllowsMailPreviewButPublicLockScreenStaysGeneric() {
        store.personalPrivacy = NotificationPrivacy.DETAILED
        show()
        assertTrue(notification().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains(secret))
        assertFalse(notification().publicVersion.extras.toString().contains(secret))
    }

    @Test fun changingToStandardSanitizesAlreadyDisplayedMailAndPreservesClickTarget() {
        store.personalPrivacy = NotificationPrivacy.DETAILED
        show()
        val target = notification().contentIntent
        NotificationPresenter.setPersonalPrivacy(context, NotificationPrivacy.STANDARD)
        assertNoPreview()
        assertEquals("Sender Example", notification().extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("Example subject", notification().extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(target, notification().contentIntent)
        assertEquals(NotificationPrivacy.STANDARD, store.personalPrivacy)
    }

    @Test fun lateDetailedResponseReadsCurrentPrivacyRatherThanPreFetchValue() {
        store.personalPrivacy = NotificationPrivacy.DETAILED
        val inFlightResponse = detail()
        NotificationPresenter.setPersonalPrivacy(context, NotificationPrivacy.STANDARD)
        show(inFlightResponse)
        assertNoPreview()
    }

    @Test fun downgradeToMinimalRemovesSenderSubjectAndPreview() {
        store.personalPrivacy = NotificationPrivacy.DETAILED
        show()
        NotificationPresenter.setPersonalPrivacy(context, NotificationPrivacy.MINIMAL)
        assertNoPreview()
        assertFalse(notification().extras.toString().contains("Sender Example"))
        assertFalse(notification().extras.toString().contains("Example subject"))
    }

    @Test fun sharedDeviceNeverDisplaysPersonalDetails() {
        preferences.deviceMode = DeviceMode.SHARED
        store.personalPrivacy = NotificationPrivacy.DETAILED
        show()
        assertNoPreview()
        assertFalse(notification().extras.toString().contains("Sender Example"))
    }

    @Test fun responseForReplacedDeviceOrRevokedEnrollmentIsNotPosted() {
        preferences.deviceId = "replacement"
        show()
        assertTrue(manager.activeNotifications.isEmpty())
        preferences.deviceId = device
        preferences.enrollmentState = EnrollmentState.NOT_ENROLLED
        show()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun standardTalkKeepsOnlyItsExistingShortPreviewContract() {
        store.personalPrivacy = NotificationPrivacy.STANDARD
        show(detail(PushAction.OPEN_TALK, "t".repeat(120) + secret))
        assertEquals("Example subject\n" + "t".repeat(120),
            notification().extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertFalse(notification().extras.toString().contains(secret))
    }

    @Test fun standardCalendarDoesNotShowUnexpectedPreview() {
        store.personalPrivacy = NotificationPrivacy.STANDARD
        show(detail(PushAction.OPEN_CALENDAR))
        assertNoPreview()
    }

    @Test fun startupSanitizesLegacyNotificationWithoutCopyingOldStyleExtras() {
        manager.notify(NotificationPresenter.notificationId(event),
            NotificationCompat.Builder(context, "mail").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Sender Example").setContentText("Example subject")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Example subject\n$secret")).build())
        NotificationPresenter.reconcilePrivacy(context)
        assertNoPreview()
        assertEquals("Example subject", notification().extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test fun privacyChangeDoesNotRemoveLoginRequestsBackgroundConnectionOrUnreadCounts() {
        store.personalPrivacy = NotificationPrivacy.DETAILED
        show()
        UnreadNotificationStore(context).record(PushAction.OPEN_MAIL, event)
        val before = UnreadNotificationStore(context).counts()
        manager.notify(501, NotificationCompat.Builder(context, "security").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Login request").build())
        manager.notify(502, NotificationCompat.Builder(context, "connection").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Background connection").build())
        NotificationPresenter.setPersonalPrivacy(context, NotificationPrivacy.STANDARD)
        assertEquals(setOf(501, 502, NotificationPresenter.notificationId(event)), manager.activeNotifications.map { it.id }.toSet())
        assertEquals(before, UnreadNotificationStore(context).counts())
        assertNoPreview()
    }

    @Test fun detailRenderingRechecksDisabledCommunication() {
        store.communicationNotificationsEnabled = false
        show()
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
