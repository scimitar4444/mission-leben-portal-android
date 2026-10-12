package de.missionleben.portal.push

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import de.missionleben.portal.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PushReliabilitySettingsTest {
    @Test fun notificationsOpenAppWideSettingsWithoutAHelpdeskFilter() {
        val context = RuntimeEnvironment.getApplication()
        PushReliabilitySettings(context).openNotificationSettings()
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertFalse(intent.hasExtra(Settings.EXTRA_CHANNEL_ID))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun missingNotificationSettingsFallBackToThisAppsDetails() {
        val started = mutableListOf<Intent>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) {
                started += intent
                if (intent.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                    throw ActivityNotFoundException("Synthetic unsupported settings activity")
                }
            }
        }
        PushReliabilitySettings(context).openNotificationSettings()
        assertEquals(2, started.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.last().action)
        assertEquals(Uri.parse("package:${context.packageName}"), started.last().data)
    }

    @Test fun samsungBatterySettingsAreNotTheNotificationSettingsScreen() {
        val original = Build.MANUFACTURER
        try {
            ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "samsung")
            val context = RuntimeEnvironment.getApplication()
            PushReliabilitySettings(context).openManufacturerSettings()
            val intent = shadowOf(context).nextStartedActivity
            assertEquals("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY", intent.action)
            assertEquals("com.samsung.android.lool", intent.`package`)
            assertEquals(2, intent.getIntExtra("activity_type", -1))
            assertFalse(intent.hasExtra(Settings.EXTRA_CHANNEL_ID))
        } finally {
            ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", original)
        }
    }

    @Test fun tclBatteryEntryUsesTheAppsDetailsRatherThanAGuessedVendorActivity() {
        val original = Build.MANUFACTURER
        try {
            ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "TCL")
            val context = RuntimeEnvironment.getApplication()
            PushReliabilitySettings(context).openManufacturerSettings()
            val intent = shadowOf(context).nextStartedActivity
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
            assertEquals(Uri.parse("package:${context.packageName}"), intent.data)
        } finally {
            ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", original)
        }
    }

    @Test fun everyLanguageDistinguishesNotificationsFromBatterySettings() {
        val application = RuntimeEnvironment.getApplication()
        for (language in listOf("de", "en", "es", "fr", "hi", "pl", "ro", "tr", "uk")) {
            val configuration = Configuration(application.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val context = application.createConfigurationContext(configuration)
            val notifications = context.getString(R.string.push_reliability_open_notifications)
            val battery = context.getString(R.string.push_reliability_open_manufacturer)
            assertTrue(language, notifications.isNotBlank() && battery.isNotBlank())
            assertNotEquals(language, notifications, battery)
            if (language != "de") {
                assertNotEquals(language, "Benachrichtigungen einstellen", notifications)
                assertNotEquals(language, "Akku-Einstellungen öffnen", battery)
            }
        }
    }

    @Test fun phoneSettingsExplainTheirMissionLebenAppScopeInEveryLanguage() {
        val application = RuntimeEnvironment.getApplication()
        for (language in listOf("de", "en", "es", "fr", "hi", "pl", "ro", "tr", "uk")) {
            val context = application.createConfigurationContext(
                Configuration(application.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                },
            )
            val title = context.getString(R.string.push_reliability_title)
            val description = context.getString(R.string.push_reliability_description)
            assertTrue(language, title.isNotBlank())
            assertTrue(language, description.contains("Mission Leben Zentral"))
            if (language != "de") assertNotEquals(language, "App-Einstellungen auf dem Handy", title)
        }
    }
}
