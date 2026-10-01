package de.missionleben.portal.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalAuthenticationPolicyTest {
    private val attempt = PersonalLoginAttempt(3, "device-example", 1_800_000_000L, true, "subject-example")
    private val evidence = AuthenticationEvidence("https://example.invalid/issuer/", setOf("client"), null,
        "subject-example", attempt.startedAtEpochSeconds + 1L, true)
    private fun allowed(
        login: PersonalLoginAttempt = attempt,
        proof: AuthenticationEvidence = evidence,
        epoch: Long = 3,
        device: String? = "device-example",
        trusted: Boolean = true,
        cleared: Boolean = true,
        locked: Boolean = false,
        now: Long = attempt.startedAtEpochSeconds + 10L,
    ) = PersonalAuthenticationPolicy.permitsFreshLogin(login, proof, "https://example.invalid/issuer/",
        "client", epoch, device, trusted, cleared, locked, now)

    @Test fun confirmedFreshBoundLoginMayProceedToLocalSeal() { assertTrue(allowed()) }
    @Test fun oldSsoAuthenticationTimeIsRejectedDespiteNewTokenIssuance() {
        assertFalse(allowed(proof = evidence.copy(authenticatedAtEpochSeconds = attempt.startedAtEpochSeconds - 31L)))
    }
    @Test fun missingOrZeroAuthenticationTimeNeverUsesIssuanceAsFallback() {
        assertFalse(allowed(proof = evidence.copy(authenticatedAtEpochSeconds = 0L)))
    }
    @Test fun futureAuthenticationTimeIsRejected() {
        assertFalse(allowed(proof = evidence.copy(authenticatedAtEpochSeconds = attempt.startedAtEpochSeconds + 41L)))
    }
    @Test fun smallClockToleranceHasExplicitBoundariesAndNeverReplacesIsolation() {
        val earliest = evidence.copy(authenticatedAtEpochSeconds = attempt.startedAtEpochSeconds - 30L)
        assertTrue(allowed(proof = earliest))
        assertFalse(allowed(proof = earliest, cleared = false))
        assertFalse(allowed(proof = earliest.copy(authenticatedAtEpochSeconds = earliest.authenticatedAtEpochSeconds - 1L)))
        val latest = evidence.copy(authenticatedAtEpochSeconds = attempt.startedAtEpochSeconds + 40L)
        assertTrue(allowed(proof = latest))
        assertFalse(allowed(proof = latest.copy(authenticatedAtEpochSeconds = latest.authenticatedAtEpochSeconds + 1L)))
    }
    @Test fun cookieTimeoutOrUnconfirmedResetCannotUnlock() { assertFalse(allowed(cleared = false)) }
    @Test fun screenOffOrBackgroundTimeoutRejectsLateCallback() { assertFalse(allowed(locked = true)) }
    @Test fun replacedAttemptOrDeviceRejectsLateCallback() {
        assertFalse(allowed(epoch = 4)); assertFalse(allowed(device = "other-device")); assertFalse(allowed(device = null))
    }
    @Test fun disabledDeviceAndChangedBoundSubjectAreRejected() {
        assertFalse(allowed(trusted = false)); assertFalse(allowed(proof = evidence.copy(subject = "other-user")))
    }
    @Test fun firstBoundIdentityStillNeedsFreshAuthAndNonemptySubject() {
        assertTrue(allowed(login = attempt.copy(boundSubject = null)))
        assertFalse(allowed(login = attempt.copy(boundSubject = null), proof = evidence.copy(subject = "")))
    }
    @Test fun issuerAndAudienceMustMatchExactConfiguredProvider() {
        assertFalse(allowed(proof = evidence.copy(issuer = "https://other.invalid/issuer/")))
        assertFalse(allowed(proof = evidence.copy(audiences = setOf("other-client"))))
        assertFalse(allowed(proof = evidence.copy(authorizedParty = "other-client")))
    }
    @Test fun multipleAudiencesRequireOurAuthorizedParty() {
        val multi = evidence.copy(audiences = setOf("client", "other"))
        assertFalse(allowed(proof = multi)); assertTrue(allowed(proof = multi.copy(authorizedParty = "client")))
    }
    @Test fun explicitVolatileSessionCannotReceiveRefreshToken() {
        assertFalse(allowed(login = attempt.copy(persistSession = false)))
        assertTrue(allowed(login = attempt.copy(persistSession = false), proof = evidence.copy(hasRefreshToken = false)))
    }
    @Test fun clockGoingBackAndInvalidAttemptFailClosed() {
        assertFalse(allowed(now = attempt.startedAtEpochSeconds - 1L))
        assertFalse(allowed(login = attempt.copy(startedAtEpochSeconds = 0L)))
        assertFalse(allowed(login = attempt.copy(deviceId = "")))
    }
}
