package de.missionleben.portal.calendar

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TodayAgendaTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private fun event(id: String, start: String, end: String, allDay: Boolean = false) =
        CalendarEvent(id, id, "", millis(start), millis(end), allDay)

    @Test fun finishedAndTomorrowAreHiddenOngoingAndUpcomingRemain() {
        val now = millis("2026-10-11T10:00:00Z")
        val events = listOf(
            event("finished", "2026-10-11T08:00:00Z", "2026-10-11T10:00:00Z"),
            event("upcoming", "2026-10-11T12:00:00Z", "2026-10-11T13:00:00Z"),
            event("ongoing", "2026-10-11T09:00:00Z", "2026-10-11T11:00:00Z"),
            event("tomorrow", "2026-10-12T08:00:00Z", "2026-10-12T09:00:00Z"),
        )
        assertEquals(listOf("ongoing", "upcoming"), TodayAgenda.select(events, now, zone).map { it.id })
    }

    @Test fun overnightEventAndAllDayAreIncludedWithoutDisplacingTheNextTimedAppointment() {
        val now = millis("2026-10-11T00:00:00Z")
        val allDay = event("all-day", "2026-10-10T22:00:00Z", "2026-10-11T22:00:00Z", true)
        val overnight = event("overnight", "2026-10-10T21:00:00Z", "2026-10-11T01:00:00Z")
        assertEquals(listOf("overnight", "all-day"), TodayAgenda.select(listOf(allDay, overnight), now, zone).map { it.id })
    }

    @Test fun autumnClockChangeUsesTheLocalDayRatherThanTwentyFourHours() {
        val now = millis("2026-10-25T00:30:00Z")
        val late = event("late", "2026-10-25T22:00:00Z", "2026-10-25T22:30:00Z")
        val tomorrow = event("tomorrow", "2026-10-25T23:00:00Z", "2026-10-26T00:00:00Z")
        assertEquals(listOf(late), TodayAgenda.select(listOf(tomorrow, late), now, zone))
    }

    @Test fun localTimezoneDeterminesTodayAndExpiredEventsDisappearAtEnd() {
        val event = event("edge", "2026-10-11T21:45:00Z", "2026-10-11T22:15:00Z")
        assertEquals(listOf(event), TodayAgenda.select(listOf(event), millis("2026-10-11T22:00:00Z"), zone))
        assertTrue(TodayAgenda.select(listOf(event), event.endMillis, zone).isEmpty())
    }

    @Test fun dayLinkUsesOnlyTheConfiguredHttpsOriginAndKeepsItsPort() {
        val event = event("opaque-id", "2026-10-11T12:00:00Z", "2026-10-11T13:00:00Z")
        assertEquals("https://mail.example.invalid:8443/modern/calendar/day/${event.startMillis}",
            TodayAgenda.calendarDayUrl("https://mail.example.invalid:8443/modern?foo=bar", event))
        assertNull(TodayAgenda.calendarDayUrl("http://mail.example.invalid", event))
        assertNull(TodayAgenda.calendarDayUrl("https://user:password@mail.example.invalid", event))
    }

    @Test fun dayNavigationRejectsForeignOriginsAndDecoratedLinks() {
        val base = "https://mail.example.invalid:8443"
        val target = "$base/modern/calendar/day/1791720000000"
        assertTrue(TodayAgenda.isCalendarDayUrl(base, target))
        assertFalse(TodayAgenda.isCalendarDayUrl(base, target.replace(":8443", "")))
        assertFalse(TodayAgenda.isCalendarDayUrl(base, target.replace("mail.example.invalid", "foreign.example.invalid")))
        assertFalse(TodayAgenda.isCalendarDayUrl(base, "$target?redirect=evil"))
        assertFalse(TodayAgenda.isCalendarDayUrl(base, "$target#fragment"))
        assertFalse(TodayAgenda.isCalendarDayUrl(base, target.replace("https://", "https://user@")))
        assertFalse(TodayAgenda.isCalendarDayUrl(base, "$base/modern/mail"))
    }
}
