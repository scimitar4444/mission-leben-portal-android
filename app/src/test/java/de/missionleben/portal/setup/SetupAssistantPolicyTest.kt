package de.missionleben.portal.setup

import de.missionleben.portal.model.DeviceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SetupAssistantPolicyTest {
    @Test fun `personal Zimbra user sees push and calendar only when access is available`() {
        assertEquals(
            listOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY, SetupStep.MESSAGES, SetupStep.CALENDAR),
            SetupAssistantPolicy.available(true, DeviceMode.PERSONAL, true, true, true, true, talkVisible = false),
        )
        assertEquals(
            listOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY, SetupStep.MESSAGES),
            SetupAssistantPolicy.available(true, DeviceMode.PERSONAL, true, true, true, false, talkVisible = false),
        )
    }

    @Test fun `shared tablet and Talk-only access never expose personal calendar setup`() {
        assertEquals(
            listOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY),
            SetupAssistantPolicy.available(true, DeviceMode.SHARED, true, true, true, true, talkVisible = false),
        )
        assertEquals(
            listOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY, SetupStep.MESSAGES),
            SetupAssistantPolicy.available(true, DeviceMode.PERSONAL, true, true, false, true, talkVisible = false),
        )
        assertEquals(
            emptyList<SetupStep>(),
            SetupAssistantPolicy.available(false, DeviceMode.PERSONAL, true, true, true, true, talkVisible = false),
        )
        assertEquals(
            listOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY),
            SetupAssistantPolicy.available(true, DeviceMode.PERSONAL, true, false, false, false, talkVisible = false),
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

    @Test fun `Talk is an independent step without push or Zimbra`() {
        for (mode in DeviceMode.entries) {
            assertEquals(
                listOf(SetupStep.TALK),
                SetupAssistantPolicy.available(true, mode, false, true, false, false, talkVisible = true),
            )
        }
    }

    @Test fun `Talk access is required even when communication notifications exist`() {
        assertEquals(
            emptyList<SetupStep>(),
            SetupAssistantPolicy.available(true, DeviceMode.PERSONAL, false, true, false, false, talkVisible = false),
        )
        assertEquals(
            emptyList<SetupStep>(),
            SetupAssistantPolicy.available(false, DeviceMode.PERSONAL, true, true, true, true, talkVisible = true),
        )
    }

    @Test fun `Talk is appended without renumbering the previous setup steps`() {
        assertEquals(
            listOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY, SetupStep.MESSAGES, SetupStep.CALENDAR, SetupStep.TALK),
            SetupAssistantPolicy.available(true, DeviceMode.PERSONAL, true, true, true, true, talkVisible = true),
        )
        assertEquals(
            listOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY, SetupStep.TALK),
            SetupAssistantPolicy.available(true, DeviceMode.SHARED, true, true, true, true, talkVisible = true),
        )
    }

    @Test fun `existing users are only offered the new Talk choice once`() {
        val completed = setOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY, SetupStep.MESSAGES, SetupStep.CALENDAR)
        val available = SetupAssistantPolicy.available(true, DeviceMode.PERSONAL, true, true, true, true, talkVisible = true)
        assertEquals(listOf(SetupStep.TALK), SetupAssistantPolicy.pending(available, completed))
        assertEquals(emptyList<SetupStep>(), SetupAssistantPolicy.pending(available, completed + SetupStep.TALK))
    }

    @Test fun `revoked Talk access removes even an unfinished Talk step`() {
        val withoutTalk = SetupAssistantPolicy.available(true, DeviceMode.PERSONAL, true, true, true, true, talkVisible = false)
        assertEquals(
            emptyList<SetupStep>(),
            SetupAssistantPolicy.pending(withoutTalk, withoutTalk.toSet()),
        )
        assertFalse(SetupStep.TALK in withoutTalk)
    }
}
