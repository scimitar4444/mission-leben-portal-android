package de.missionleben.portal.data

import android.app.Application
import android.content.Context
import de.missionleben.portal.model.DeviceMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppPreferencesPrivacyTest {
    private lateinit var preferences: AppPreferences

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("mission_leben_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        preferences = AppPreferences(context)
    }

    @Test fun consumingSharedScreenStateDoesNotErasePersonalScreenshotChoice() {
        preferences.deviceMode = DeviceMode.PERSONAL
        preferences.allowPersonalScreenshots = true
        assertFalse(preferences.consumeSharedSessionScreenTurnedOff())
        assertTrue(preferences.allowPersonalScreenshots)
    }

    @Test fun clearingProfileRemovesScreenshotChoice() {
        preferences.deviceMode = DeviceMode.PERSONAL
        preferences.allowPersonalScreenshots = true
        preferences.clearProfile()
        preferences.deviceMode = DeviceMode.PERSONAL
        assertFalse(preferences.allowPersonalScreenshots)
    }
}
