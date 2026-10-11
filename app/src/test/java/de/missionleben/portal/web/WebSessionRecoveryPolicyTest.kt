package de.missionleben.portal.web

import de.missionleben.portal.auth.ReauthenticationPolicy
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSessionRecoveryPolicyTest {
    private val login = 1_700_000_000L
    private val deadline = login + ReauthenticationPolicy.SESSION_LIFETIME_DAYS * 24L * 60L * 60L

    private fun allowed(
        mode: DeviceMode? = DeviceMode.PERSONAL,
        state: EnrollmentState = EnrollmentState.TRUSTED,
        signedIn: Boolean = true,
        anchor: Long = login,
        verified: Boolean = true,
        now: Long = login + 60L,
    ) = WebSessionRecoveryPolicy.canPreservePersonalSession(mode, state, signedIn, anchor, verified, now)

    @Test fun validNativeSessionSurvivesAnIndependentBrowserLoginRedirect() {
        assertTrue(allowed())
        assertTrue(allowed(now = deadline - 1L))
    }

    @Test fun aBrowserRedirectCannotOverrideTheAbsoluteDeadline() {
        assertFalse(allowed(now = deadline))
        assertFalse(allowed(now = deadline + 1L))
    }

    @Test fun unverifiedNativeSessionAndSignedOutSessionsAreNeverPreserved() {
        assertFalse(allowed(verified = false))
        assertFalse(allowed(signedIn = false))
    }

    @Test fun missingNegativeAndFutureAuthenticationEvidenceFailClosed() {
        listOf(0L, -1L, login + 61L, Long.MAX_VALUE).forEach {
            assertFalse(allowed(anchor = it))
        }
    }

    @Test fun blockedPendingAndUnregisteredDevicesCannotRecoverWebOnly() {
        EnrollmentState.entries.filter { it != EnrollmentState.TRUSTED }.forEach {
            assertFalse(allowed(state = it))
        }
    }

    @Test fun sharedTabletsKeepTheirNonpersistentFailClosedBehavior() {
        assertFalse(allowed(mode = DeviceMode.SHARED))
        assertFalse(allowed(mode = null))
    }

    @Test fun missingAnchorRequiresLoginButCannotBeLabelledNinetyDaysExpired() {
        assertTrue(ReauthenticationPolicy.hasReachedAbsoluteDeadline(0L, login))
        assertFalse(ReauthenticationPolicy.hasKnownExpiredDeadline(0L, login))
        assertFalse(ReauthenticationPolicy.hasKnownExpiredDeadline(-1L, login))
        assertFalse(ReauthenticationPolicy.hasKnownExpiredDeadline(login, deadline - 1L))
        assertTrue(ReauthenticationPolicy.hasKnownExpiredDeadline(login, deadline))
    }
}
