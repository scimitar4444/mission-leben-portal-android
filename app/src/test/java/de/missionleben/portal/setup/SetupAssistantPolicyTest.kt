package de.missionleben.portal.setup

import de.missionleben.portal.model.DeviceMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupAssistantPolicyTest {
    @Test fun `personal Zimbra user sees push and calendar only when access is available`() {
        assertEquals(
            listOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY, SetupStep.MESSAGES, SetupStep.CALENDAR),
            SetupAssistantPolicy.available(true, DeviceMode.PERSONAL, true, true, true, true),
        )
        assertEquals(
            listOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY, SetupStep.MESSAGES),
            SetupAssistantPolicy.available(true, DeviceMode.PERSONAL, true, true, true, false),
        )
    }

    @Test fun `shared tablet and Talk-only access never expose personal calendar setup`() {
        assertEquals(
            listOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY),
            SetupAssistantPolicy.available(true, DeviceMode.SHARED, true, true, true, true),
        )
        assertEquals(
            listOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY, SetupStep.MESSAGES),
            SetupAssistantPolicy.available(true, DeviceMode.PERSONAL, true, true, false, true),
        )
        assertEquals(
            emptyList<SetupStep>(),
            SetupAssistantPolicy.available(false, DeviceMode.PERSONAL, true, true, true, true),
        )
        assertEquals(
            listOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY),
            SetupAssistantPolicy.available(true, DeviceMode.PERSONAL, true, false, false, false),
        )
    }

    @Test fun `a new optional capability is offered without repeating completed steps`() {
        val available = listOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY, SetupStep.MESSAGES, SetupStep.CALENDAR)
        assertEquals(
            listOf(SetupStep.CALENDAR),
            SetupAssistantPolicy.pending(available, setOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY, SetupStep.MESSAGES)),
        )
        assertEquals(emptyList<SetupStep>(), SetupAssistantPolicy.pending(available, available.toSet()))
    }
}
