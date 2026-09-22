package de.missionleben.portal.web

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZimbraContactComposeTest {
    @Test fun composerUsesGuardedMailtoWithoutNavigationOrSend() {
        val script = requireNotNull(ZimbraContactCompose.script("https://mail.example.org", "maria+team@example.org"))
        assertTrue(script.contains("location.origin !== 'https://mail.example.org'"))
        assertTrue(script.contains("encodeURIComponent(recipient)"))
        assertTrue(script.contains("event.preventDefault()"))
        assertTrue(script.contains("link.remove()"))
        assertTrue(script.contains("window.__mlContactComposeRecipient"))
    }

    @Test fun scriptRejectsUntrustedInterpolation() {
        assertNull(ZimbraContactCompose.script("https://mail.example.org", "x';alert(1);//@example.org"))
        assertNull(ZimbraContactCompose.script("http://mail.example.org", "maria@example.org"))
        assertNotNull(ZimbraContactCompose.script("https://mail.example.org", "maria@example.org"))
    }

    @Test fun originMatchesBrowserNormalization() {
        val script = requireNotNull(ZimbraContactCompose.script("https://MAIL.example.org:443", "maria@example.org"))
        assertTrue(script.contains("location.origin !== 'https://mail.example.org'"))
    }
}
