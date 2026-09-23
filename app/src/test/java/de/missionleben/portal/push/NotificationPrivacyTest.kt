package de.missionleben.portal.push

import de.missionleben.portal.model.DeviceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationPrivacyTest {
    @Test
    fun `preview limits are enforced locally per action and privacy`() {
        val text = "x".repeat(400)
        PushAction.entries.forEach { action ->
            assertEquals("", NotificationPrivacy.MINIMAL.visiblePreview(action, text))
            assertEquals(if (action == PushAction.OPEN_TALK) "x".repeat(120) else "",
                NotificationPrivacy.STANDARD.visiblePreview(action, text))
            assertEquals("x".repeat(280), NotificationPrivacy.DETAILED.visiblePreview(action, text))
        }
    }

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

    @Test
    fun `disabled communication blocks delivery`() {
        assertEquals(
            false,
            NotificationDeliveryPolicy.shouldDeliver(false, false, 22 * 60, 6 * 60, 12 * 60),
        )
    }

    @Test
    fun `overnight quiet hours block both sides of midnight`() {
        assertEquals(
            false,
            NotificationDeliveryPolicy.shouldDeliver(true, true, 22 * 60, 6 * 60, 23 * 60),
        )
        assertEquals(
            false,
            NotificationDeliveryPolicy.shouldDeliver(true, true, 22 * 60, 6 * 60, 5 * 60),
        )
        assertEquals(
            true,
            NotificationDeliveryPolicy.shouldDeliver(true, true, 22 * 60, 6 * 60, 12 * 60),
        )
    }

    @Test
    fun `daytime quiet hours use an exclusive end time`() {
        assertEquals(
            false,
            NotificationDeliveryPolicy.shouldDeliver(true, true, 8 * 60, 17 * 60, 8 * 60),
        )
        assertEquals(
            true,
            NotificationDeliveryPolicy.shouldDeliver(true, true, 8 * 60, 17 * 60, 17 * 60),
        )
    }
}
