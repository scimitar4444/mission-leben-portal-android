package de.missionleben.portal.auth

import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentProfile
import de.missionleben.portal.model.EnrollmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReauthenticationPolicyTest {
    private val authenticatedAt = 1_700_000_000L
    private val deadline = authenticatedAt + 90L * 24L * 60L * 60L
    private fun expiryRequest(
        anchor: Long = authenticatedAt,
        now: Long = deadline,
        required: Boolean = true,
        fresh: Boolean = true,
        hint: String? = "person.example",
        profile: EnrollmentProfile? = EnrollmentProfile.PERSONAL_EMPLOYEE,
        mode: DeviceMode = DeviceMode.PERSONAL,
        state: EnrollmentState = EnrollmentState.TRUSTED,
        absoluteDeadline: Boolean = true,
    ) = ReauthenticationPolicy.request(mode, state, required, hint, fresh, profile, anchor, now, absoluteDeadline)

    @Test fun ninetyDayRoutingStartsExactlyAtTheAbsoluteDeadline() {
        assertFalse(expiryRequest(now = deadline - 1L).ninetyDayReauthentication)
        assertTrue(expiryRequest().ninetyDayReauthentication)
        assertTrue(expiryRequest(now = deadline + 1L).ninetyDayReauthentication)
        assertFalse(expiryRequest().forceLogin)
    }

    @Test fun genericTokenFailureBeforeNinetyDaysCannotSelectTotpOnly() {
        assertFalse(expiryRequest(now = authenticatedAt + 60L, required = true).ninetyDayReauthentication)
        assertFalse(expiryRequest(absoluteDeadline = false).ninetyDayReauthentication)
    }

    @Test fun explicitLogoutOrManualFallbackCannotSelectTotpOnlyEvenWithAnOldAnchor() {
        assertFalse(expiryRequest(required = false).ninetyDayReauthentication)
        assertFalse(expiryRequest(fresh = false).ninetyDayReauthentication)
    }

    @Test fun missingLegacyNegativeOrFutureAnchorFallsBackToPassword() {
        listOf(0L, -1L, deadline + 1L, Long.MAX_VALUE).forEach {
            assertFalse(expiryRequest(anchor = it).ninetyDayReauthentication)
        }
    }

    @Test fun sharedHandsetTabletUnknownProfileOrMissingHintCannotSelectPersonalTotp() {
        listOf(EnrollmentProfile.SHARED_ACCOUNT_HANDSET, EnrollmentProfile.FACILITY_TABLET, null).forEach {
            val request = expiryRequest(profile = it)
            assertFalse(request.ninetyDayReauthentication)
            assertNull(request.loginHint)
        }
        assertFalse(expiryRequest(mode = DeviceMode.SHARED).ninetyDayReauthentication)
        assertFalse(expiryRequest(hint = null).ninetyDayReauthentication)
        assertFalse(expiryRequest(hint = " ").ninetyDayReauthentication)
        assertFalse(expiryRequest(state = EnrollmentState.BLOCKED).ninetyDayReauthentication)
    }

    @Test fun isolatedExpiredSessionPrefillsUsernameWithoutASecondPromptLogin() {
        val request = ReauthenticationPolicy.request(
            DeviceMode.PERSONAL, EnrollmentState.TRUSTED, true, " person.example ",
            freshPersonalContext = true,
        )
        assertEquals("person.example", request.loginHint)
        assertFalse(request.forceLogin)
    }
    @Test fun `confirmed personal hint in isolated fallback does not force a prompt loop`() {
        val request = ReauthenticationPolicy.request(DeviceMode.PERSONAL, EnrollmentState.TRUSTED,
            false, " person.example ", freshPersonalContext = true)
        assertEquals("person.example", request.loginHint)
        assertFalse(request.forceLogin)
    }
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
    fun `current normal personal login neither prefills saved name nor forces fresh authentication`() {
        // A missing/failed local vault does not currently set reauthenticationRequired.
        // This flag combination is therefore also used after an unsealed session restart.
        val request = ReauthenticationPolicy.request(
            mode = DeviceMode.PERSONAL,
            enrollmentState = EnrollmentState.TRUSTED,
            reauthenticationRequired = false,
            storedLoginHint = "person.example",
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
            ReauthenticationPolicy.canOfferBoundDeviceReauthentication(
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

    @Test
    fun `remaining days round partial days up for a useful countdown`() {
        val authenticatedAt = 1_700_000_000L
        val oneSecondAfterLogin = authenticatedAt + 1L
        val oneSecondBeforeDeadline = authenticatedAt + (90L * 24L * 60L * 60L) - 1L

        assertEquals(90L, ReauthenticationPolicy.remainingDays(authenticatedAt, oneSecondAfterLogin))
        assertEquals(1L, ReauthenticationPolicy.remainingDays(authenticatedAt, oneSecondBeforeDeadline))
        assertEquals(0L, ReauthenticationPolicy.remainingDays(authenticatedAt, oneSecondBeforeDeadline + 1L))
    }
}
