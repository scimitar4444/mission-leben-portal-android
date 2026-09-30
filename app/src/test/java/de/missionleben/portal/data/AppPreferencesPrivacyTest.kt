package de.missionleben.portal.data

import android.app.Application
import android.content.Context
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
    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("mission_leben_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun legacyScreenshotOptInIsRemovedOnUpgrade() {
        val context = RuntimeEnvironment.getApplication()
        val settings = context.getSharedPreferences("mission_leben_settings", Context.MODE_PRIVATE)
        settings.edit().putBoolean("allow_personal_screenshots", true).commit()
        AppPreferences(context)
        assertFalse(settings.contains("allow_personal_screenshots"))
    }

    @Test fun downloadAutoOpenDefaultsToOnAndSurvivesReload() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = AppPreferences(context)
        assertTrue(preferences.downloadsAutoOpenEnabled)
        preferences.downloadsAutoOpenEnabled = false
        assertFalse(AppPreferences(context).downloadsAutoOpenEnabled)
        preferences.downloadsAutoOpenEnabled = true
        assertTrue(AppPreferences(context).downloadsAutoOpenEnabled)
    }

    @Test fun clearingTheDeviceProfileResetsDownloadAutoOpenPreference() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = AppPreferences(context)
        preferences.downloadsAutoOpenEnabled = false
        preferences.clearProfile()
        assertTrue(AppPreferences(context).downloadsAutoOpenEnabled)
    }
}
