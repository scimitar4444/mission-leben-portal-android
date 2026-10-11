package de.missionleben.portal.web

import org.junit.Assert.*
import org.junit.Test

class HelpdeskContainerLayoutTest {
    @Test fun onlyTheExactCustomerPortalAndTheTwoApprovedPortsMatch() {
        assertTrue(HelpdeskContainerLayout.isHelpdeskPage("https://it-service.mission-leben.de:8443/otobo/customer.pl?Action=CustomerDashboard"))
        assertTrue(HelpdeskContainerLayout.isHelpdeskPage("https://it-service.mission-leben.de/otobo/customer.pl"))
        assertTrue(HelpdeskContainerLayout.isHelpdeskPage("https://it-service.mission-leben.de:443/otobo/customer.pl"))
        listOf("http://it-service.mission-leben.de/otobo/customer.pl",
            "https://it-service.mission-leben.de:8444/otobo/customer.pl",
            "https://it-service.mission-leben.de.evil.test/otobo/customer.pl",
            "https://someone@it-service.mission-leben.de/otobo/customer.pl",
            "https://it-service.mission-leben.de/otobo/index.pl",
            "https://id.mission-leben.de/if/flow/login/", "not a url").forEach {
            assertFalse(it, HelpdeskContainerLayout.isHelpdeskPage(it))
        }
    }

    @Test fun onlyVisibilityAndTheKnownDashboardLabelChangeNotThemeOrTicketData() {
        val script = HelpdeskContainerLayout.SCRIPT
        assertTrue(script.contains("window.top !== window"))
        assertTrue(script.contains("location.pathname !== '/otobo/customer.pl'"))
        assertTrue(script.contains("getElementById('ml-helpdesk-container-layout')"))
        assertTrue(script.contains(".oooTile_TicketList"))
        assertTrue(script.contains("Action=Logout"))
        assertTrue(script.contains("Action=CustomerPreferences"))
        assertTrue(script.contains(".oooTicketListItem"))
        assertTrue(script.contains("grid-template-rows: auto 24px"))
        assertTrue(script.contains("#oooHeader h1 { font-size: 20px"))
        assertTrue(script.contains(".oooTIDTitle { font-size: 16px"))
        assertTrue(script.contains("heading.textContent.trim() === 'IT-Service – Mission Leben'"))
        assertTrue(script.contains("heading.textContent = 'IT Hilfe'"))
        listOf("fetch(", "XMLHttpRequest", "localStorage", "innerHTML", "location.href =", "form.submit(",
            "background-color:", "color:", "input.value", "SessionID", "ChallengeToken").forEach {
            assertFalse(it, script.contains(it))
        }
    }

    @Test fun compactSubtitleNeverShrinksTheTicketSearchMagnifier() {
        val script = HelpdeskContainerLayout.SCRIPT
        assertTrue(script.contains("#oooHeader .oooWithSub h3:not(.ooog) { font-size: 13px"))
        assertFalse(script.contains("#oooHeader h3 {"))
        assertTrue(script.contains("#oooSearchBox h3.ooog { position: relative"))
        assertTrue(script.contains("font-size: 20px !important; line-height: 20px !important; padding: 0"))
        assertTrue(script.contains("top: 50% !important; left: 10px !important; transform: translateY(-50%)"))
        assertTrue(script.contains("#oooSearch { padding-left: 36px"))
    }
}
