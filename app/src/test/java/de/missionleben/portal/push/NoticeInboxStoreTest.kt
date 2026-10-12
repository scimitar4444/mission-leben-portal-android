package de.missionleben.portal.push

import android.app.Application
import android.content.Context
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NoticeInboxStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun store() = NoticeInboxStore(context)
    private val documentId = "document-event-0123456789"
    private val ticketId = "helpdesk-event-0123456789"

    @Before fun clear() {
        UnreadNotificationStore(context).clearAll()
        context.getSharedPreferences("mission_leben_push", Context.MODE_PRIVATE).edit().clear().commit()
        AppPreferences(context).apply {
            deviceMode = DeviceMode.PERSONAL
            deviceId = "synthetic-device"
            enrollmentState = EnrollmentState.TRUSTED
        }
    }

    @Test fun onlyDocumentAndTicketEventsEnterHistory() {
        PushAction.entries.forEach { store().record(it, "event-0123456789-${it.name}") }
        assertEquals(NoticeInboxStore.ACTIONS, store().notices().map { it.action }.toSet())
        store().record(PushAction.OPEN_DOCUMENTS, "bad id")
        assertEquals(2, store().notices().size)
    }

    @Test fun swipingAcknowledgesCounterButKeepsHistoryAndReplayDoesNotRestoreUnread() {
        PushEventDispatcher.recordUnread(context, PushAction.OPEN_HELPDESK, ticketId)
        assertEquals(1, UnreadNotificationStore(context).counts().helpdesk)
        PushEventDispatcher.acknowledgeDismissal(context, PushAction.OPEN_HELPDESK, ticketId)
        assertEquals(0, UnreadNotificationStore(context).counts().helpdesk)
        assertEquals(ticketId, store().notices().single().eventId)
        assertTrue(store().notices().single().read)
        PushEventDispatcher.recordUnread(context, PushAction.OPEN_HELPDESK, ticketId)
        assertTrue(store().notices().single().read)
        assertEquals(0, UnreadNotificationStore(context).counts().helpdesk)
    }

    @Test fun clearApplicationMarksOnlyItsHistoryReadAndLogoutRemovesEverything() {
        listOf(PushAction.OPEN_DOCUMENTS to documentId, PushAction.OPEN_HELPDESK to ticketId).forEach {
            PushEventDispatcher.recordUnread(context, it.first, it.second)
        }
        UnreadNotificationStore(context).clear(NotificationBadgeTarget.HELPDESK)
        assertTrue(store().notices().single { it.eventId == ticketId }.read)
        assertFalse(store().notices().single { it.eventId == documentId }.read)
        assertEquals(1, UnreadNotificationStore(context).counts().documents)
        UnreadNotificationStore(context).clearAll()
        assertTrue(store().notices().isEmpty())
        assertEquals(NotificationBadgeCounts(), UnreadNotificationStore(context).counts())
    }

    @Test fun serverCancellationRemovesDeletedEventButNotOtherHistory() {
        store().record(PushAction.OPEN_DOCUMENTS, documentId)
        store().record(PushAction.OPEN_HELPDESK, ticketId)
        PushEventDispatcher.dispatch(context, PushCommand.Cancel(ticketId))
        assertEquals(listOf(documentId), store().notices().map { it.eventId })
    }

    @Test fun dismissingWrongCategoryCannotMarkOtherHistoryRead() {
        PushEventDispatcher.recordUnread(context, PushAction.OPEN_DOCUMENTS, documentId)
        assertFalse(UnreadNotificationStore(context).cancel(PushAction.OPEN_HELPDESK, documentId))
        assertFalse(store().notices().single().read)
        assertEquals(1, UnreadNotificationStore(context).counts().documents)
    }

    @Test fun targetsAreIdentifiersOnlyAndNoMessageContentIsSaved() {
        store().record(PushAction.OPEN_HELPDESK, ticketId)
        store().rememberTarget(ticketId, PushAction.OPEN_HELPDESK, "42:84")
        assertEquals("42:84", store().notices().single().targetId)
        store().rememberTarget(ticketId, PushAction.OPEN_HELPDESK, "https://evil.invalid/private")
        assertEquals("42:84", store().notices().single().targetId)
        val json = JSONObject(JSONArray(context.getSharedPreferences("notice_inbox", Context.MODE_PRIVATE)
            .getString("events", "[]")).getJSONObject(0).toString())
        assertEquals(setOf("id", "action", "received", "read", "target"), json.keys().asSequence().toSet())
    }

    @Test fun retentionLimitsFutureDatesDuplicatesAndCorruptionAreFailClosed() {
        val now = System.currentTimeMillis()
        fun item(id: String, time: Long) = JSONObject().put("id", id).put("action", "open_helpdesk").put("received", time)
        val prefs = context.getSharedPreferences("notice_inbox", Context.MODE_PRIVATE)
        prefs.edit().putString("events", JSONArray().put(item(ticketId, now))
            .put(item(ticketId, now)).put(item(documentId, now - NoticeInboxStore.RETENTION_MILLIS - 1))
            .put(item("future-event-01234567", now + 1)).toString()).commit()
        assertEquals(listOf(ticketId), store().notices(now).map { it.eventId })
        prefs.edit().putString("events", "broken-json").commit()
        assertTrue(store().notices().isEmpty())
        repeat(110) { store().record(PushAction.OPEN_HELPDESK, "bounded-event-%03d-0123456".format(it)) }
        assertEquals(100, store().notices().size)
    }
}
