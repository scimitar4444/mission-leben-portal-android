package de.missionleben.portal.calendar

import java.net.URI
import java.time.Instant
import java.time.ZoneId

/** A small foreground view of the existing signed snapshot, not a second calendar sync. */
object TodayAgenda {
    const val REFRESH_INTERVAL_MILLIS = 60_000L

    fun select(events: List<CalendarEvent>, nowMillis: Long, zone: ZoneId): List<CalendarEvent> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val begin = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return events.filter { it.endMillis > nowMillis && it.startMillis < end && it.endMillis > begin }
            .sortedWith(compareBy<CalendarEvent> { if (it.allDay) 1 else 0 }
                .thenBy { it.startMillis }.thenBy { it.id })
    }

    /** Snapshot IDs are opaque, so open the correct day rather than inventing an invitation ID. */
    fun calendarDayUrl(baseUrl: String, event: CalendarEvent): String? {
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        if (!base.scheme.equals("https", true) || base.host.isNullOrBlank() || base.rawUserInfo != null) return null
        return URI("https", base.rawAuthority, "/modern/calendar/day/${event.startMillis}", null, null)
            .toASCIIString()
    }

    fun isCalendarDayUrl(baseUrl: String, url: String): Boolean {
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return false
        val target = runCatching { URI(url) }.getOrNull() ?: return false
        return base.scheme.equals("https", true) && !base.host.isNullOrBlank() &&
            target.scheme.equals("https", true) && target.host.equals(base.host, true) &&
            target.port == base.port && target.rawUserInfo == null &&
            target.rawQuery == null && target.rawFragment == null &&
            target.path.orEmpty().matches(Regex("/modern/calendar/day/[0-9]+"))
    }
}
