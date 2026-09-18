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
}
