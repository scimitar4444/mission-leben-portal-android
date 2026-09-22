package de.missionleben.portal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class ContactActionsTest {
    @Test fun businessNumbersAreFormattedForDialler() {
        assertEquals("+49123456", ContactActions.dialable("+49 123/456"))
        assertEquals("06151456", ContactActions.dialable("(06151) 456"))
        assertEquals("1234", ContactActions.dialable("1234"))
    }

    @Test fun serviceCodesUrisAndPausesAreRejected() {
        listOf("*21*123#", "tel:1234", "123,456", "123;456", "abc", "---", "++49123", "123\n456")
            .forEach { assertNull(it, ContactActions.dialable(it)) }
    }

    @Test fun emailOpensModernZimbraComposerOnConfiguredOrigin() {
        assertEquals(
            "https://mail.example.org/modern/email/new?to=maria.muster%2540example.org",
            ContactActions.zimbraComposeUrl("https://mail.example.org/", "maria.muster@example.org"),
        )
    }

    @Test fun recipientSurvivesModernZimbrasTwoStageQueryDecoding() {
        listOf("maria.muster@example.org", "maria+team@example.org", "maria%team@example.org").forEach { address ->
            val url = requireNotNull(ContactActions.zimbraComposeUrl("https://mail.example.org", address))
            // getParsedSearch: decodeURIComponent(search), followed by query-string.parse.
            val decodedSearch = URLDecoder.decode(URI(url).rawQuery, StandardCharsets.UTF_8)
            val params = decodedSearch.split('&').associate {
                val (key, value) = it.split('=', limit = 2)
                key to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
            assertEquals(mapOf("to" to address), params)
        }
    }

    @Test fun recipientsCannotInjectHeadersOtherRecipientsOrUrls() {
        listOf("", "mailto:a@example.org", "a@example.org?bcc=b@example.org", "a@example.org&bcc=b@example.org",
            "a@example.org\r\nBcc:b@example.org", "a@example.org,b@example.org", "a@example.org;b@example.org",
            "Maria <a@example.org>", "a@example.org#x", "a@@example.org", "a @example.org")
            .forEach { assertNull(it, ContactActions.zimbraComposeUrl("https://mail.example.org", it)) }
    }

    @Test fun unsafeZimbraBaseCannotLaunchComposer() {
        listOf("http://mail.example.org", "javascript:alert(1)", "//mail.example.org", "https://user@mail.example.org",
            "https://mail.example.org?redirect=bad", "https://mail.example.org#other", "https://")
            .forEach { assertNull(it, ContactActions.zimbraComposeUrl(it, "a@example.org")) }
    }
}
