package de.missionleben.portal.auth

import java.time.MonthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityBirthdayTest {
    @Test
    fun keepsOnlyMonthAndDayFromStandardBirthdateClaim() {
        assertEquals("--09-20", IdentityBirthday.normalizedMonthDay("1980-09-20"))
        assertEquals("--09-20", IdentityBirthday.normalizedMonthDay("--09-20"))
        assertNull(IdentityBirthday.normalizedMonthDay("20.09.1980"))
    }

    @Test
    fun greetingIsShownOnlyOnMatchingDay() {
        assertTrue(IdentityBirthday.isToday("--09-20", MonthDay.of(9, 20)))
        assertFalse(IdentityBirthday.isToday("--09-19", MonthDay.of(9, 20)))
        assertFalse(IdentityBirthday.isToday(null, MonthDay.of(9, 20)))
    }
}
