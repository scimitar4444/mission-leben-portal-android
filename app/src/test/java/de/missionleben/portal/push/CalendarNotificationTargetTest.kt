package de.missionleben.portal.push

import org.junit.Assert.*
import org.junit.Test

class CalendarNotificationTargetTest {
    private val base = "https://mail.example.invalid"
    private val target = "123-456|20260923T070000Z|1790146800000|1790150400000"

    @Test fun `recurring appointment opens details for exactly that occurrence`() {
        assertEquals(
            "$base/modern/calendar/event/details/123-456?utcRecurrenceId=20260923T070000Z&start=1790146800000&end=1790150400000",
            NotificationNavigation.resolve(PushAction.OPEN_CALENDAR, target, "https://id.example.invalid/launch", base),
        )
        assertEquals(target, NotificationDetail.notificationTarget(PushAction.OPEN_CALENDAR, target))
    }

    @Test fun `single appointment omits missing recurrence parameter`() {
        assertEquals(
            "$base/modern/calendar/event/details/123-456?start=1790146800000&end=1790150400000",
            NotificationNavigation.resolve(PushAction.OPEN_CALENDAR, target.replace("20260923T070000Z", ""), "", base),
        )
        assertNotNull(CalendarNotificationTarget.parse(target.replace("20260923T070000Z", "20260923")))
    }

    @Test fun `invalid calendar identities cannot inject routes or query strings`() {
        listOf(
            "https://evil.invalid", target.replace("123-456", "../edit"),
            target.replace("123-456", "other-account:123-456"),
            target.replace("20260923T070000Z", "20260230T070000Z"),
            target.replace("20260923T070000Z", "20260230"),
            target.replace("20260923T070000Z", "20260923T250000Z"),
            target.replace("1790150400000", "1790146800000"),
            target.replace("1790150400000", "1790146799999"),
            target + "&redirect=https://evil.invalid", target.replace("123-456", "123"),
        ).forEach { invalid ->
            assertNull(invalid, CalendarNotificationTarget.parse(invalid))
            assertEquals("", NotificationDetail.notificationTarget(PushAction.OPEN_CALENDAR, invalid))
            assertEquals("$base/modern/calendar", NotificationNavigation.resolve(PushAction.OPEN_CALENDAR, invalid, "", base))
        }
    }

    @Test fun `calendar fallback is overview instead of inbox`() {
        assertEquals("$base/modern/calendar", NotificationNavigation.resolve(PushAction.OPEN_CALENDAR, "", "", base))
        assertEquals("approved", NotificationNavigation.resolve(PushAction.OPEN_CALENDAR, target, "approved", "http://mail.example.invalid"))
    }

    @Test fun `calendar details get the appointment day as back target`() {
        val details = NotificationNavigation.resolve(PushAction.OPEN_CALENDAR, target, "", base)
        val overview = "$base/modern/calendar/day/1790146800000"
        assertEquals(overview, NotificationNavigation.zimbraCalendarOverviewUrl(details, base))
        assertTrue(NotificationNavigation.isZimbraCalendarOverviewUrl(overview, base))
        assertTrue(NotificationNavigation.isZimbraCalendarOverviewUrl("$base/modern/calendar/", base))
        assertFalse(NotificationNavigation.isZimbraCalendarOverviewUrl(details, base))
        assertNull(NotificationNavigation.zimbraCalendarOverviewUrl(details.replace("/details/", "/edit/"), base))
        assertNull(NotificationNavigation.zimbraCalendarOverviewUrl(details.replace("mail.example.invalid", "evil.invalid"), base))
        assertNull(NotificationNavigation.zimbraCalendarOverviewUrl(details.replace("https:", "http:"), base))
        assertNull(NotificationNavigation.zimbraCalendarOverviewUrl(details.replace("https://", "https://user@"), base))
        assertFalse(NotificationNavigation.isZimbraCalendarOverviewUrl(overview.replace("mail.example.invalid", "evil.invalid"), base))
    }
}
