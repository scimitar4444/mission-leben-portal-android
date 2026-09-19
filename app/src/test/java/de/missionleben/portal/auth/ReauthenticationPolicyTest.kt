package de.missionleben.portal.auth

import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReauthenticationPolicyTest {
    @Test
    fun `trusted personal device receives forced login with saved identity`() {
        val request = ReauthenticationPolicy.request(
            mode = DeviceMode.PERSONAL,
            enrollmentState = EnrollmentState.TRUSTED,
            reauthenticationRequired = true,
            storedLoginHint = " alex@example.org ",
        )

        assertEquals("alex@example.org", request.loginHint)
        assertTrue(request.forceLogin)
    }

    @Test
    fun `first login never skips username and password`() {
        val request = ReauthenticationPolicy.request(
            mode = DeviceMode.PERSONAL,
            enrollmentState = EnrollmentState.TRUSTED,
            reauthenticationRequired = false,
            storedLoginHint = "alex@example.org",
        )

        assertNull(request.loginHint)
        assertFalse(request.forceLogin)
    }

    @Test
    fun `shared or untrusted devices never receive abbreviated login`() {
        val shared = ReauthenticationPolicy.request(
            mode = DeviceMode.SHARED,
            enrollmentState = EnrollmentState.TRUSTED,
            reauthenticationRequired = true,
            storedLoginHint = "alex@example.org",
        )
        val untrusted = ReauthenticationPolicy.request(
            mode = DeviceMode.PERSONAL,
            enrollmentState = EnrollmentState.PENDING,
            reauthenticationRequired = true,
            storedLoginHint = "alex@example.org",
        )

        assertFalse(shared.forceLogin)
        assertNull(shared.loginHint)
        assertFalse(untrusted.forceLogin)
        assertNull(untrusted.loginHint)
    }

    @Test
    fun `missing identity always falls back to full login`() {
        assertFalse(
            ReauthenticationPolicy.canOfferTotpOnly(
                mode = DeviceMode.PERSONAL,
                enrollmentState = EnrollmentState.TRUSTED,
                loginHint = "",
            ),
        )
    }

    @Test
    fun `absolute session deadline is exactly ninety days`() {
        val authenticatedAt = 1_700_000_000L
        val oneSecondBefore = authenticatedAt + (90L * 24L * 60L * 60L) - 1L
        val exactDeadline = oneSecondBefore + 1L

        assertFalse(
            ReauthenticationPolicy.hasReachedAbsoluteDeadline(
                authenticatedAtEpochSeconds = authenticatedAt,
                nowEpochSeconds = oneSecondBefore,
            ),
        )
        assertTrue(
            ReauthenticationPolicy.hasReachedAbsoluteDeadline(
                authenticatedAtEpochSeconds = authenticatedAt,
                nowEpochSeconds = exactDeadline,
            ),
        )
    }

    @Test
    fun `missing authentication time fails closed`() {
        assertTrue(
            ReauthenticationPolicy.hasReachedAbsoluteDeadline(
                authenticatedAtEpochSeconds = 0L,
                nowEpochSeconds = 1_700_000_000L,
            ),
        )
    }
}
