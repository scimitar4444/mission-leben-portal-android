package de.missionleben.portal.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRefreshFailurePolicyTest {
    @Test
    fun `requires login for invalid or no longer authorized sessions`() {
        assertTrue(TokenRefreshFailurePolicy.requiresReauthentication("invalid_grant", stateAuthorized = true))
        assertTrue(TokenRefreshFailurePolicy.requiresReauthentication("invalid_token", stateAuthorized = true))
        assertTrue(TokenRefreshFailurePolicy.requiresReauthentication(null, stateAuthorized = false))
    }

    @Test
    fun `keeps session for transient refresh failures`() {
        assertFalse(TokenRefreshFailurePolicy.requiresReauthentication(null, stateAuthorized = true))
        assertFalse(TokenRefreshFailurePolicy.requiresReauthentication("temporarily_unavailable", stateAuthorized = true))
    }
}
