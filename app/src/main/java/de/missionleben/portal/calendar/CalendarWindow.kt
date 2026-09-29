package de.missionleben.portal.calendar

import java.time.Clock
import java.time.ZoneId

object CalendarWindow {
    fun select(
        events: List<CalendarEvent>,
        days: Int,
        clock: Clock = Clock.systemDefaultZone(),
    ): List<CalendarEvent> {
        val zone = ZoneId.systemDefault()
        val firstDay = java.time.LocalDate.now(clock.withZone(zone))
        val begin = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = firstDay.plusDays(CalendarSyncPolicy.validDays(days).toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()
        return events.filter { it.endMillis > begin && it.startMillis < end }
            .sortedWith(compareBy(CalendarEvent::startMillis, CalendarEvent::id))
    }
}
