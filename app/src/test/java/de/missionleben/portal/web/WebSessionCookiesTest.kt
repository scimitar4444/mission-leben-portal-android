package de.missionleben.portal.web

import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.web.WebSessionDiagnostics.CookiePresence
import de.missionleben.portal.web.WebSessionDiagnostics.Event
import org.junit.Assert.*
import org.junit.Test

class WebSessionCookiesTest {
    @Test fun cookieNamesAreExactAndValuesNeverEnterDiagnostics() {
        assertEquals(CookiePresence.ABSENT, WebSessionDiagnostics.cookiePresence(null))
        assertEquals(CookiePresence.ABSENT, WebSessionDiagnostics.cookiePresence("other=authentik_session"))
        assertEquals(CookiePresence.ABSENT, WebSessionDiagnostics.cookiePresence("authentik_session_extra=secret"))
        assertEquals(CookiePresence.PRESENT, WebSessionDiagnostics.cookiePresence("csrf=private; authentik_session=secret=="))
        val lines = mutableListOf<String>()
        val session = WebSessionCookies("https://id.example.invalid", { "authentik_session=SECRET" }, {},
            { "155.0.8059.43" }, lines::add)
        session.persistPersonal(DeviceMode.PERSONAL, Event.AUTHORIZATION_RETURN,
            "https://name:PRIVATE@id.example.invalid/if/flow/android-login/?code=TOKEN#FRAGMENT")
        assertEquals(3, lines.size)
        assertTrue(lines.last().contains("PERSIST_COMPLETED"))
        assertTrue(lines.all { it.contains("authentik_session=PRESENT") })
        for (forbidden in listOf("SECRET", "PRIVATE", "TOKEN", "FRAGMENT", "code=", "name:")) {
            assertFalse(forbidden, lines.any { it.contains(forbidden) })
        }
    }

    @Test fun onlyPersonalModePersistsAndNormalLockDoesNotDeleteCookies() {
        var header = "authentik_session=synthetic"
        var flushes = 0
        val session = WebSessionCookies("https://example.invalid", { header }, { flushes++ }, { "155.0" }, {})
        for (mode in DeviceMode.entries.filter { it != DeviceMode.PERSONAL } + listOf(null)) {
            assertNull(session.persistPersonal(mode, Event.PERSONAL_PAUSE, "https://example.invalid/"))
        }
        assertEquals(0, flushes)
        assertEquals(true, session.persistPersonal(DeviceMode.PERSONAL, Event.PERSONAL_PAUSE, "https://example.invalid/"))
        assertEquals(1, flushes)
        assertEquals("authentik_session=synthetic", header)
        header = "" // The real clear routine removes cookies; flushing cannot resurrect them.
        assertTrue(session.persist())
        assertEquals("", header)
    }

    @Test fun failuresAreReportedWithoutExceptionContentAndMissingCookieIsNotSuccessProof() {
        val lines = mutableListOf<String>()
        val session = WebSessionCookies("https://example.invalid", { throw IllegalStateException("COOKIE_SECRET") },
            { throw IllegalStateException("FLUSH_SECRET") }, { "bad\nVERSION_SECRET" }, lines::add)
        assertFalse(session.persist())
        assertTrue(lines.last().contains("PERSIST_FAILED"))
        assertTrue(lines.all { it.contains("authentik_session=UNAVAILABLE") && it.contains("webview=unavailable") })
        assertFalse(lines.any { "SECRET" in it })
        val absent = mutableListOf<String>()
        val emptySession = WebSessionCookies("https://example.invalid", { null }, {}, { null }, absent::add)
        assertTrue(emptySession.persist()) // API returned; NOT a claim that a login cookie exists.
        assertTrue(absent.last().contains("authentik_session=ABSENT"))
    }

    @Test fun arbitraryPathsAndVersionsCannotLeakPersonalDataOrBreakLogLines() {
        val line = WebSessionDiagnostics.line(Event.WEB_REAUTHENTICATION, CookiePresence.ABSENT,
            "155.0\nINJECTED", "https://id.example.invalid/install/REGISTRATION_SECRET?token=PRIVATE")
        assertTrue(line.contains("path=/[redacted]"))
        assertFalse(line.contains("SECRET") || line.contains("PRIVATE") || line.contains("INJECTED") || line.contains('\n'))
    }
}
