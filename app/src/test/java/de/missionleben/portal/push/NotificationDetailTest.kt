package de.missionleben.portal.push

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationDetailTest {
    @Test
    fun `sanitizes control characters and bounds notification text`() {
        val value = "  Team\n\tIT\u0000   Besprechung  " + "x".repeat(200)
        val sanitized = NotificationDetail.sanitize(value, 32)

        assertEquals(32, sanitized.length)
        assertEquals("Team IT Besprechung " + "x".repeat(12), sanitized)
    }

    @Test
    fun `accepts only action specific notification targets`() {
        assertEquals("42", NotificationDetail.notificationTarget(PushAction.OPEN_MAIL, "42"))
        assertEquals("account_1:42", NotificationDetail.notificationTarget(PushAction.OPEN_MAIL, "account_1:42"))
        assertEquals("room_1", NotificationDetail.notificationTarget(PushAction.OPEN_TALK, "room_1"))
        assertEquals("", NotificationDetail.notificationTarget(PushAction.OPEN_MAIL, "../../42"))
        assertEquals("", NotificationDetail.notificationTarget(PushAction.OPEN_TALK, "https://evil.invalid"))
    }
}
