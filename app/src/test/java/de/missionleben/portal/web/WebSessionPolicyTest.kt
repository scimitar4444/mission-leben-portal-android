package de.missionleben.portal.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSessionPolicyTest {
    private val policy = WebSessionPolicy(
        authentikBaseUrl = "https://id.mission-leben.de",
        authenticationFlowSlugs = "default-authentication-flow,mission-leben-android-authentication,mission-leben-browser-authentication",
    )

    @Test
    fun `recognizes interactive authentik flows`() {
        assertTrue(
            policy.isInteractiveAuthentication(
                "https://id.mission-leben.de/if/flow/default-authentication-flow/?next=%2Fapplication%2Fo%2Fauthorize%2F",
            ),
        )
        assertTrue(
            policy.isInteractiveAuthentication(
                "https://id.mission-leben.de/if/flow/mission-leben-android-authentication/",
            ),
        )
        assertTrue(
            policy.isInteractiveAuthentication(
                "https://id.mission-leben.de/if/flow/mission-leben-browser-authentication/",
            ),
        )
    }

    @Test
    fun `does not mistake silent sso endpoints for an expired session`() {
        assertFalse(
            policy.isInteractiveAuthentication(
                "https://id.mission-leben.de/application/o/authorize/?client_id=nextcloud",
            ),
        )
        assertFalse(policy.isInteractiveAuthentication("https://id.mission-leben.de/application/saml/zimbra/init/"))
        assertFalse(
            policy.isInteractiveAuthentication(
                "https://id.mission-leben.de/if/flow/default-provider-authorization-implicit-consent/",
            ),
        )
        assertFalse(policy.isInteractiveAuthentication("https://id.mission-leben.de/if/flow/"))
    }

    @Test
    fun `rejects lookalike hosts and non https urls`() {
        assertFalse(policy.isInteractiveAuthentication("https://id.mission-leben.de.attacker.example/if/flow/login/"))
        assertFalse(policy.isInteractiveAuthentication("http://id.mission-leben.de/if/flow/login/"))
        assertFalse(policy.isInteractiveAuthentication("https://cloud.mission-leben.de/if/flow/login/"))
    }
}
