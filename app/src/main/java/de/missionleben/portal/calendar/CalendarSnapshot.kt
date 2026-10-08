package de.missionleben.portal.calendar

import org.json.JSONObject

data class CalendarEvent(
    val id: String,
    val title: String,
    val location: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
)

data class CalendarSnapshot(
    val events: List<CalendarEvent>,
    val fetchedAtSeconds: Long,
) {
    companion object {
        const val MAX_EVENTS = 200

        fun fromJson(root: JSONObject): CalendarSnapshot {
            val values = root.getJSONArray("events")
            require(values.length() <= MAX_EVENTS) { "Zu viele Termine im Kalenderabgleich." }
            val events = List(values.length()) { index ->
                val item = values.getJSONObject(index)
                val start = item.getLong("start_millis")
                val end = item.getLong("end_millis")
                require(end > start) { "Ungültiger Terminzeitraum." }
                CalendarEvent(
                    id = item.getString("id").also {
                        require(it.matches(Regex("[A-Za-z0-9_-]{16,128}")))
                    },
                    title = item.getString("title").take(200),
                    location = item.optString("location").take(200),
                    startMillis = start,
                    endMillis = end,
                    allDay = item.optBoolean("all_day"),
                )
            }
            require(events.map { it.id }.distinct().size == events.size) { "Doppelte Termin-ID." }
            return CalendarSnapshot(events, root.getLong("fetched_at"))
        }
    }
}

object CalendarSyncPolicy {
    val availableDays = listOf(1, 3, 7, 14)

    fun validDays(days: Int): Int = days.takeIf { it in availableDays } ?: 14

    fun zimbraVisible(slugs: Iterable<String>): Boolean =
        slugs.any { it.equals("zimbra-mail", ignoreCase = true) }
}
