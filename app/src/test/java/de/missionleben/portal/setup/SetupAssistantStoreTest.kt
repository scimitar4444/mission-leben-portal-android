package de.missionleben.portal.setup

import android.app.Application
import android.content.Context
import de.missionleben.portal.model.DeviceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SetupAssistantStoreTest {
    private val completedBeforeTalk = setOf(
        SetupStep.NOTIFICATIONS, SetupStep.BATTERY, SetupStep.MESSAGES, SetupStep.CALENDAR,
    )

    @Before fun resetProgress() {
        RuntimeEnvironment.getApplication().getSharedPreferences(
            "mission_leben_setup_assistant", Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    @Test fun oldProgressOffersOnlyTalkAfterReload() {
        val context = RuntimeEnvironment.getApplication()
        val store = SetupAssistantStore(context)
        val scope = requireNotNull(store.scope(DeviceMode.PERSONAL, "device-example", "subject-example"))
        store.markSeen(scope, completedBeforeTalk)
        val seen = SetupAssistantStore(context).seen(scope, legacyPushHandled = true, calendarAlreadyEnabled = true)
        assertEquals(completedBeforeTalk, seen)
        assertEquals(
            listOf(SetupStep.TALK),
            SetupAssistantPolicy.pending(completedBeforeTalk.toList() + SetupStep.TALK, seen),
        )
    }

    @Test fun legacyPermissionMigrationDoesNotMarkTheNewTalkStepSeen() {
        val store = SetupAssistantStore(RuntimeEnvironment.getApplication())
        val scope = requireNotNull(store.scope(DeviceMode.PERSONAL, "device-example", "subject-example"))
        val seen = store.seen(scope, legacyPushHandled = true, calendarAlreadyEnabled = true)
        assertEquals(completedBeforeTalk, seen)
        assertFalse(SetupStep.TALK in seen)
    }

    @Test fun completedTalkChoiceStaysCompletedOnTheSameDeviceOnly() {
        val context = RuntimeEnvironment.getApplication()
        val store = SetupAssistantStore(context)
        val scope = requireNotNull(store.scope(DeviceMode.PERSONAL, "device-example", "subject-example"))
        store.markSeen(scope, completedBeforeTalk + SetupStep.TALK)
        val reloaded = SetupAssistantStore(context)
        assertEquals(
            completedBeforeTalk + SetupStep.TALK,
            reloaded.seen(scope, legacyPushHandled = false, calendarAlreadyEnabled = false),
        )
        val otherScope = requireNotNull(store.scope(DeviceMode.PERSONAL, "other-device", "subject-example"))
        assertFalse(SetupStep.TALK in reloaded.seen(otherScope, legacyPushHandled = true, calendarAlreadyEnabled = true))
    }
}
