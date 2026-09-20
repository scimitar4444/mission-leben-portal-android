package de.missionleben.portal.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtfySubscriptionPolicyTest {
    private val token = "tk_" + "r".repeat(29)

    @Test
    fun acceptsPinnedMissionLebenEndpoint() {
        assertTrue(
            NtfySubscriptionPolicy.accepts(
                "https://push.mission-leben.de",
                "ml-device-topic-0123456789",
                token,
                "https://push.mission-leben.de",
            ),
        )
    }

    @Test
    fun rejectsDifferentEndpointAndInvalidCredentials() {
        assertFalse(
            NtfySubscriptionPolicy.accepts(
                "https://attacker.invalid",
                "ml-device-topic-0123456789",
                token,
                "https://push.mission-leben.de",
            ),
        )
        assertFalse(
            NtfySubscriptionPolicy.accepts(
                "https://push.mission-leben.de",
                "too-short",
                "invalid-token",
                "https://push.mission-leben.de",
            ),
        )
    }

    @Test
    fun acceptsNtfyMessageIdsWithoutConfusingThemWithBridgeEventIds() {
        assertTrue(NtfySubscriptionPolicy.validMessageId("iQeOlR9rCfai"))
        assertFalse(NtfySubscriptionPolicy.validMessageId("short"))
    }
}
