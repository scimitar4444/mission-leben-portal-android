package de.missionleben.portal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginApprovalRequestTest {
    private val request = LoginApprovalRequest(
        requestId = "approval_01JABCDEF0123456789",
        application = "Test",
        domain = "id.mission-leben.de",
        requestedAtEpochSeconds = 1_000L,
        expiresAtEpochSeconds = 1_060L,
    )

    @Test
    fun `counts down to the server supplied expiry`() {
        assertEquals(60L, request.remainingSeconds(1_000L))
        assertEquals(1L, request.remainingSeconds(1_059L))
        assertFalse(request.isExpired(1_059L))
    }

    @Test
    fun `never reports negative time after expiry`() {
        assertEquals(0L, request.remainingSeconds(1_060L))
        assertEquals(0L, request.remainingSeconds(1_100L))
        assertTrue(request.isExpired(1_060L))
    }
}
