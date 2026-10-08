package de.missionleben.portal.calendar

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CalendarSnapshotTest {
    private fun snapshot(count: Int): JSONObject = JSONObject()
        .put("fetched_at", 1_800_000_000L)
        .put("events", JSONArray().apply {
            repeat(count) { index ->
                put(JSONObject()
                    .put("id", "event-${index.toString().padStart(26, '0')}")
                    .put("title", "Termin")
                    .put("location", "Raum")
                    .put("start_millis", 1_800_000_000_000L)
                    .put("end_millis", 1_800_003_600_000L)
                    .put("all_day", false))
            }
        })

    @Test fun acceptsEmptyLegacyAndExtendedCalendarsIncludingExactlyTwoHundred() {
        assertEquals(200, CalendarSnapshot.MAX_EVENTS)
        listOf(0, 100, 105, 199, 200).forEach { count ->
            assertEquals(count, CalendarSnapshot.fromJson(snapshot(count)).events.size)
        }
    }

    @Test fun rejectsTwoHundredAndOneWithoutReturningPartialEvents() {
        assertThrows(IllegalArgumentException::class.java) {
            CalendarSnapshot.fromJson(snapshot(201))
        }
    }

    @Test fun stillRejectsDuplicateIdsAndInvalidTimes() {
        val duplicate = snapshot(2)
        duplicate.getJSONArray("events").getJSONObject(1)
            .put("id", duplicate.getJSONArray("events").getJSONObject(0).getString("id"))
        assertThrows(IllegalArgumentException::class.java) { CalendarSnapshot.fromJson(duplicate) }
        val invalid = snapshot(1)
        invalid.getJSONArray("events").getJSONObject(0).put("end_millis", 0L)
        assertThrows(IllegalArgumentException::class.java) { CalendarSnapshot.fromJson(invalid) }
    }
}
