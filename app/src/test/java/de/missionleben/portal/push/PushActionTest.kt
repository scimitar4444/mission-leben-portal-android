package de.missionleben.portal.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushActionTest {
    @Test
    fun `accepts only known typed actions`() {
        assertEquals(PushAction.OPEN_MAIL, PushAction.fromWireName("open_mail"))
        assertEquals(PushAction.OPEN_CALENDAR, PushAction.fromWireName("open_calendar"))
        assertEquals(PushAction.OPEN_TALK, PushAction.fromWireName("open_talk"))
        assertEquals(PushAction.REFRESH_SECURITY_STATE, PushAction.fromWireName("refresh_security_state"))
    }

    @Test
    fun `rejects arbitrary urls and malformed actions`() {
        assertNull(PushAction.fromWireName("https://evil.example"))
        assertNull(PushAction.fromWireName("open_mail?url=https://evil.example"))
        assertNull(PushAction.fromWireName("OPEN_MAIL"))
        assertNull(PushAction.fromWireName(null))
    }

    @Test
    fun `notification copy is fixed in the client`() {
        assertEquals("Neue Mail", PushAction.OPEN_MAIL.title)
        assertEquals("Zimbra öffnen", PushAction.OPEN_MAIL.body)
        assertEquals("Ein Termin steht bevor.", PushAction.OPEN_CALENDAR.body)
        assertEquals("Neue Talk-Aktivität.", PushAction.OPEN_TALK.body)
    }
}
