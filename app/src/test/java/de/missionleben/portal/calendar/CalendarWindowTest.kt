package de.missionleben.portal.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

class CalendarWindowTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), zone)

    private fun event(id: String, time: String): CalendarEvent {
        val start = Instant.parse(time).toEpochMilli()
        return CalendarEvent(id, id, "", start, start + 3_600_000, false)
    }

    @Test fun `one day means all of today and fourteen days remains rolling`() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try {
            val today = event("today", "2026-09-29T07:00:00Z")
            val tomorrow = event("tomorrow", "2026-09-30T08:00:00Z")
            val dayFourteen = event("day14", "2026-10-12T08:00:00Z")
            val outside = event("outside", "2026-10-13T08:00:00Z")
            val values = listOf(outside, tomorrow, today, dayFourteen)
            assertEquals(listOf("today"), CalendarWindow.select(values, 1, clock).map { it.id })
            assertEquals(listOf("today", "tomorrow"), CalendarWindow.select(values, 3, clock).map { it.id })
            assertEquals(listOf("today", "tomorrow", "day14"), CalendarWindow.select(values, 14, clock).map { it.id })
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test fun `Zimbra slug and period choices are exact`() {
        assertTrue(CalendarSyncPolicy.zimbraVisible(listOf("zimbra-mail")))
        assertFalse(CalendarSyncPolicy.zimbraVisible(listOf("talk", "owa-mail")))
        assertEquals(listOf(1, 3, 7, 14), CalendarSyncPolicy.availableDays)
        assertEquals(14, CalendarSyncPolicy.validDays(42))
    }
}
