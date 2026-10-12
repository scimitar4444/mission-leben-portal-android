package de.missionleben.portal.model

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class ApplicationShortcutsTest {
    private val helpdesk = PortalApplication("IT Hilfe", "otobo-mitarbeiter",
        "https://it-service.mission-leben.de:8443/otobo/customer.pl")
    private val mail = PortalApplication("E-Mail", "zimbra", "https://mail.mission-leben.de/modern")

    @Test fun favoritesMoveForwardWithoutChangingCatalogOrRelativeOrder() {
        val apps = listOf(mail, helpdesk, mail.copy(slug = "calendar"), helpdesk.copy(slug = "other"))
        assertEquals(listOf("otobo-mitarbeiter", "other", "zimbra", "calendar"),
            ApplicationShortcuts.ordered(apps, setOf("other", "not-authorized", "otobo-mitarbeiter")).map { it.slug })
        assertEquals(apps, ApplicationShortcuts.ordered(apps, emptySet()))
        assertTrue(ApplicationShortcuts.ordered(emptyList(), setOf("not-authorized")).isEmpty())
    }

    @Test fun urlMatchingUsesExactAuthorityAndLongestCatalogPath() {
        val root = mail.copy(slug = "root", launchUrl = "https://mail.mission-leben.de/")
        assertEquals(mail, ApplicationShortcuts.applicationForUrl(listOf(root, mail), "${mail.launchUrl}/email/Inbox?view=x"))
        assertEquals(helpdesk, ApplicationShortcuts.applicationForUrl(listOf(helpdesk), "${helpdesk.launchUrl}?Action=CustomerTicketZoom"))
        assertNull(ApplicationShortcuts.applicationForUrl(listOf(helpdesk), helpdesk.launchUrl.replace(":8443", "")))
        assertEquals(mail, ApplicationShortcuts.applicationForUrl(listOf(mail), "https://mail.mission-leben.de:443/modern"))
    }

    @Test fun lookalikesAndUntrustedUrlsDoNotMatch() {
        listOf("http://mail.mission-leben.de/modern", "https://mail.mission-leben.de.evil.invalid/modern",
            "https://x@mail.mission-leben.de/modern", "https://mail.mission-leben.de/modernish",
            "https:/modern", "not a URI", "javascript:alert(1)").forEach {
            assertNull(it, ApplicationShortcuts.applicationForUrl(listOf(mail), it))
        }
    }

    @Test fun problemFormPrefillsPlainTextWithoutSubmittingOrCarryingSourceUrl() {
        val subject = "Problem mit E-Mail & Kalender"
        val body = "Betroffene Anwendung: E-Mail\n\nBitte beschreibe dein Problem: äöü"
        val result = requireNotNull(ApplicationShortcuts.problemReportUrl(helpdesk, mail, subject, body))
        val uri = URI(result)
        val query = uri.rawQuery.split('&').associate {
            val parts = it.split('=', limit = 2)
            parts[0] to URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals("it-service.mission-leben.de", uri.host)
        assertEquals(8443, uri.port)
        assertEquals(mapOf("Action" to "CustomerTicketMessage", "Subject" to subject, "Body" to body), query)
        assertFalse(result.contains("Subaction"))
        assertFalse(result.contains("mail.mission-leben.de"))
    }

    @Test fun formRejectsWrongHelpdeskAndBoundsText() {
        listOf("https://evil.invalid/otobo/customer.pl", "http://it-service.mission-leben.de/otobo/customer.pl",
            "https://user@it-service.mission-leben.de/otobo/customer.pl",
            "https://it-service.mission-leben.de:9999/otobo/customer.pl",
            "https://it-service.mission-leben.de/otobo/index.pl").forEach {
            assertNull(ApplicationShortcuts.problemReportUrl(helpdesk.copy(launchUrl = it), mail, "test", "test"))
        }
        assertNull(ApplicationShortcuts.problemReportUrl(helpdesk.copy(slug = "fake"), mail, "test", "test"))
        assertNull(ApplicationShortcuts.problemReportUrl(helpdesk, mail.copy(slug = ""), "test", "test"))
        val query = URI(ApplicationShortcuts.problemReportUrl(helpdesk, mail, "a".repeat(200), "b".repeat(600))).rawQuery
        assertTrue(query.contains("Subject=" + "a".repeat(160) + "&Body=" + "b".repeat(500)))
    }
}
