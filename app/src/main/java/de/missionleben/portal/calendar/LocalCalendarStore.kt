package de.missionleben.portal.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** The app owns exactly one local, read-only Zimbra calendar. It never touches other calendars. */
class LocalCalendarStore(private val context: Context) {
    companion object {
        // The UI and JobService can start an initial sync at the same time.
        private val calendarWriteLock = Any()
    }

    private val resolver = context.contentResolver
    private val accountName = context.packageName + ".zimbra"
    private val accountType = CalendarContract.ACCOUNT_TYPE_LOCAL
    private val calendarName = "mission-leben-zimbra"

    fun hasPermission(): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun apply(snapshot: CalendarSnapshot, days: Int) = synchronized(calendarWriteLock) {
        check(hasPermission()) { "Kalenderberechtigung fehlt." }
        val matchingCalendars = findCalendars()
        val calendarId = matchingCalendars.firstOrNull() ?: createCalendar()
        val selected = CalendarWindow.select(snapshot.events, days)
        val existing = mutableMapOf<String, Long>()
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events._SYNC_ID),
            "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events._SYNC_ID} LIKE ?",
            arrayOf(calendarId.toString(), "ml:%"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val syncId = cursor.getString(1).orEmpty().removePrefix("ml:")
                existing[syncId] = cursor.getLong(0)
            }
        }
        val wanted = selected.associateBy(CalendarEvent::id)
        for (event in selected) {
            val values = valuesFor(calendarId, event)
            val rowId = existing[event.id]
            if (rowId == null) resolver.insert(syncEventsUri(), values)
            else resolver.update(ContentUris.withAppendedId(syncEventsUri(), rowId), values, null, null)
        }
        for ((id, rowId) in existing) {
            if (id !in wanted) resolver.delete(ContentUris.withAppendedId(syncEventsUri(), rowId), null, null)
        }
        // Only our exact account/name is considered; remove old duplicates after the
        // surviving calendar has been populated successfully.
        matchingCalendars.drop(1).forEach { duplicateId ->
            resolver.delete(ContentUris.withAppendedId(syncCalendarsUri(), duplicateId), null, null)
        }
    }

    fun clear() {
        synchronized(calendarWriteLock) {
            if (!hasPermission()) return
            findCalendars().forEach { calendarId ->
                resolver.delete(ContentUris.withAppendedId(syncCalendarsUri(), calendarId), null, null)
            }
        }
    }

    private fun findCalendars(): List<Long> {
        val matches = mutableListOf<Long>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND ${CalendarContract.Calendars.NAME}=?",
            arrayOf(accountName, accountType, calendarName),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) matches += cursor.getLong(0)
        }
        return matches.sorted()
    }

    private fun createCalendar(): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
            put(CalendarContract.Calendars.NAME, calendarName)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Mission Leben · Zimbra")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF58143B.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_READ)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Calendars.MAX_REMINDERS, 0)
        }
        return resolver.insert(syncCalendarsUri(), values)?.let(ContentUris::parseId)
            ?: error("Lokaler Zimbra-Kalender konnte nicht angelegt werden.")
    }

    private fun valuesFor(calendarId: Long, event: CalendarEvent): ContentValues = ContentValues().apply {
        val (start, end) = if (event.allDay) {
            val zone = ZoneId.of("Europe/Berlin")
            val first = Instant.ofEpochMilli(event.startMillis).atZone(zone).toLocalDate()
            val last = Instant.ofEpochMilli(event.endMillis).atZone(zone).toLocalDate()
            first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to
                last.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else event.startMillis to event.endMillis
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events._SYNC_ID, "ml:${event.id}")
        put(CalendarContract.Events.TITLE, event.title)
        put(CalendarContract.Events.EVENT_LOCATION, event.location)
        put(CalendarContract.Events.DTSTART, start)
        put(CalendarContract.Events.DTEND, end.coerceAtLeast(start + 60_000))
        put(CalendarContract.Events.EVENT_TIMEZONE, if (event.allDay) "UTC" else ZoneId.systemDefault().id)
        put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
    }

    private fun syncCalendarsUri(): Uri = asSyncAdapter(CalendarContract.Calendars.CONTENT_URI)
    private fun syncEventsUri(): Uri = asSyncAdapter(CalendarContract.Events.CONTENT_URI)

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
        .build()
}
