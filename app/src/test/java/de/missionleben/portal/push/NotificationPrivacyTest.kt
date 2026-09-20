package de.missionleben.portal.push

import de.missionleben.portal.model.DeviceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationPrivacyTest {
    @Test
    fun `personal devices keep the selected privacy level`() {
        NotificationPrivacy.entries.forEach { privacy ->
            assertEquals(
                privacy,
                NotificationPrivacy.effective(DeviceMode.PERSONAL, privacy),
            )
        }
    }

    @Test
    fun `shared and uninitialized devices always use minimal privacy`() {
        assertEquals(
            NotificationPrivacy.MINIMAL,
            NotificationPrivacy.effective(DeviceMode.SHARED, NotificationPrivacy.DETAILED),
        )
        assertEquals(
            NotificationPrivacy.MINIMAL,
            NotificationPrivacy.effective(null, NotificationPrivacy.DETAILED),
        )
    }

    @Test
    fun `wire values accept only declared privacy levels`() {
        assertEquals(NotificationPrivacy.MINIMAL, NotificationPrivacy.fromWireName("minimal"))
        assertEquals(NotificationPrivacy.STANDARD, NotificationPrivacy.fromWireName("standard"))
        assertEquals(NotificationPrivacy.DETAILED, NotificationPrivacy.fromWireName("detailed"))
        assertNull(NotificationPrivacy.fromWireName("extended"))
        assertNull(NotificationPrivacy.fromWireName(null))
    }

    @Test
    fun `calendar reminder offers only supported lead times`() {
        assertEquals(listOf(5, 10, 15, 30), PushRegistrationStore.SUPPORTED_CALENDAR_REMINDER_MINUTES)
        assertEquals(15, PushRegistrationStore.DEFAULT_CALENDAR_REMINDER_MINUTES)
    }
}
