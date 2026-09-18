package de.missionleben.portal.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebNavigationPolicyTest {
    private val policy = WebNavigationPolicy(
        allowedHostSuffixes = "mission-leben.de, akademie-mission-leben.de, example.org",
        redirectUri = "de.missionleben.portal:/oauth2redirect",
    )

    @Test
    fun `accepts configured hosts and subdomains over https`() {
        assertTrue(policy.isTrustedWebUrl("https://cloud.mission-leben.de/call/abc"))
        assertTrue(policy.isTrustedWebUrl("https://mail.akademie-mission-leben.de/owa/"))
        assertTrue(policy.isTrustedWebUrl("https://example.org/"))
    }

    @Test
    fun `rejects suffix lookalikes cleartext and local files`() {
        assertFalse(policy.isTrustedWebUrl("https://mission-leben.de.attacker.example/"))
        assertFalse(policy.isTrustedWebUrl("https://akademie-mission-leben.de.attacker.example/"))
        assertFalse(policy.isTrustedWebUrl("http://cloud.mission-leben.de/"))
        assertFalse(policy.isTrustedWebUrl("file:///data/data/secrets"))
    }

    @Test
    fun `recognizes only the exact oidc callback`() {
        assertTrue(policy.isAuthorizationRedirect("de.missionleben.portal:/oauth2redirect?code=abc"))
        assertFalse(policy.isAuthorizationRedirect("de.missionleben.portal://attacker/oauth2redirect?code=abc"))
    }
}
