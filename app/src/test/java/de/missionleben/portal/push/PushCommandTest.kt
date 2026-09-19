package de.missionleben.portal.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushCommandTest {
    @Test
    fun `parses a typed rich notification without accepting content`() {
        val result = PushCommand.parse(
            mapOf(
                "action" to "fetch_notification",
                "event_id" to "01JABCDEF0123456789XYZABCD",
                "event_type" to "open_calendar",
                "revision" to "7",
                "title" to "This value must be ignored",
            ),
        )

        assertEquals(
            PushCommand.Fetch("01JABCDEF0123456789XYZABCD", PushAction.OPEN_CALENDAR, "7"),
            result,
        )
    }

    @Test
    fun `rejects arbitrary event types and identifiers`() {
        assertNull(
            PushCommand.parse(
                mapOf(
                    "action" to "fetch_notification",
                    "event_id" to "../mail/1",
                    "event_type" to "open_mail",
                ),
            ),
        )
        assertNull(
            PushCommand.parse(
                mapOf(
                    "action" to "fetch_notification",
                    "event_id" to "01JABCDEF0123456789XYZABCD",
                    "event_type" to "https://evil.example",
                ),
            ),
        )
    }

    @Test
    fun `parses cancellation by opaque id`() {
        val result = PushCommand.parse(
            mapOf("action" to "cancel_notification", "event_id" to "01JABCDEF0123456789XYZABCD"),
        )
        assertTrue(result is PushCommand.Cancel)
    }

    @Test
    fun `parses login approval wake without accepting content or actions`() {
        val result = PushCommand.parse(
            mapOf(
                "action" to "fetch_login_approval",
                "request_id" to "approval_01JABCDEF0123456789",
                "application" to "Ignored",
                "approve" to "true",
            ),
        )

        assertEquals(PushCommand.LoginApproval("approval_01JABCDEF0123456789"), result)
        assertNull(
            PushCommand.parse(
                mapOf(
                    "action" to "fetch_login_approval",
                    "request_id" to "../approval",
                ),
            ),
        )
    }
}
