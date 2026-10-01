package de.missionleben.portal.data

import android.app.Application
import android.content.Context
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private fun boundPreferences(): AppPreferences = AppPreferences(RuntimeEnvironment.getApplication()).apply {
        deviceMode = DeviceMode.PERSONAL; enrollmentState = EnrollmentState.TRUSTED; deviceId = "device-example"
        rememberPersonalIdentity("subject-example", " person.example ")
    }

    @Test fun confirmedNameIsBoundToTheSameTrustedPersonalDevice() {
        val preferences = boundPreferences()
        assertEquals("person.example", preferences.boundPersonalLoginHint)
        assertEquals("subject-example", preferences.boundPersonalSubject)
        preferences.deviceId = "other-device"
        assertNull(preferences.boundPersonalLoginHint); assertNull(preferences.boundPersonalSubject)
    }
    @Test fun logoutMayKeepConfirmedNameButNoAuthenticationFlag() {
        val preferences = boundPreferences()
        preferences.reauthenticationRequired = true
        preferences.absoluteDeadlineReauthenticationRequired = true
        preferences.clearReauthentication(keepBoundPersonalIdentity = true)
        assertEquals("person.example", preferences.boundPersonalLoginHint)
        assertFalse(preferences.reauthenticationRequired)
        assertFalse(preferences.absoluteDeadlineReauthenticationRequired)
    }
    @Test fun sharedOrBlockedModeNeverUsesThePreviousName() {
        val preferences = boundPreferences()
        preferences.deviceMode = DeviceMode.SHARED
        assertNull(preferences.boundPersonalLoginHint)
        preferences.deviceMode = DeviceMode.PERSONAL; preferences.enrollmentState = EnrollmentState.BLOCKED
        assertNull(preferences.boundPersonalLoginHint)
    }
    @Test fun legacyUnboundNameIsNotTreatedAsAConfirmedIdentity() {
        val preferences = AppPreferences(RuntimeEnvironment.getApplication())
        preferences.deviceMode = DeviceMode.PERSONAL; preferences.enrollmentState = EnrollmentState.TRUSTED
        preferences.deviceId = "device-example"; preferences.reauthenticationHint = "old.example"
        assertNull(preferences.boundPersonalLoginHint); assertNull(preferences.boundPersonalSubject)
    }
    @Test fun profileResetForgetsBoundIdentity() {
        val preferences = boundPreferences(); preferences.clearProfile()
        assertNull(preferences.boundPersonalLoginHint); assertNull(preferences.boundPersonalSubject)
    }
    @Test fun missingUsernameDoesNotEraseSubjectBindingOrReuseOldHint() {
        val preferences = boundPreferences()
        preferences.rememberPersonalIdentity("subject-example", "")
        assertEquals("subject-example", preferences.boundPersonalSubject)
        assertNull(preferences.boundPersonalLoginHint)
    }
    @Test fun authenticationAnchorSurvivesReloadOnlyForTheSameTrustedBinding() {
        val preferences = boundPreferences()
        preferences.rememberPersonalIdentity("subject-example", "person.example", 1_700_000_000L)
        assertEquals(1_700_000_000L, AppPreferences(RuntimeEnvironment.getApplication())
            .boundPersonalAuthenticatedAtEpochSeconds)
        preferences.deviceId = "other-device"
        assertEquals(0L, preferences.boundPersonalAuthenticatedAtEpochSeconds)
    }
    @Test fun profileResetAndFullIdentityClearRemoveTheAuthenticationAnchor() {
        val preferences = boundPreferences()
        preferences.rememberPersonalIdentity("subject-example", "person.example", 1_700_000_000L)
        preferences.clearReauthentication()
        assertEquals(0L, preferences.boundPersonalAuthenticatedAtEpochSeconds)
        preferences.rememberPersonalIdentity("subject-example", "person.example", 1_700_000_000L)
        preferences.clearProfile()
        assertEquals(0L, preferences.boundPersonalAuthenticatedAtEpochSeconds)
    }
    @Test fun oldOrInvalidIdentityRecordNeverInventsAnAuthenticationAnchor() {
        val preferences = boundPreferences()
        assertEquals(0L, preferences.boundPersonalAuthenticatedAtEpochSeconds)
        preferences.rememberPersonalIdentity("subject-example", "person.example", -1L)
        assertEquals(0L, preferences.boundPersonalAuthenticatedAtEpochSeconds)
    }
    @Test fun identityPreservingLogoutClearsReauthenticationWithoutMovingTheAnchor() {
        val preferences = boundPreferences()
        preferences.rememberPersonalIdentity("subject-example", "person.example", 1_700_000_000L)
        preferences.reauthenticationRequired = true
        preferences.clearReauthentication(keepBoundPersonalIdentity = true)
        assertFalse(preferences.reauthenticationRequired)
        assertEquals(1_700_000_000L, preferences.boundPersonalAuthenticatedAtEpochSeconds)
    }
    @Test fun absoluteDeadlineReasonCannotEscapeItsTrustedDeviceBinding() {
        val preferences = boundPreferences()
        preferences.absoluteDeadlineReauthenticationRequired = true
        assertTrue(AppPreferences(RuntimeEnvironment.getApplication()).absoluteDeadlineReauthenticationRequired)
        preferences.enrollmentState = EnrollmentState.BLOCKED
        assertFalse(preferences.absoluteDeadlineReauthenticationRequired)
        preferences.enrollmentState = EnrollmentState.TRUSTED
        preferences.deviceMode = DeviceMode.SHARED
        assertFalse(preferences.absoluteDeadlineReauthenticationRequired)
        preferences.deviceMode = DeviceMode.PERSONAL
        preferences.deviceId = "other-device"
        assertFalse(preferences.absoluteDeadlineReauthenticationRequired)
    }
    @Test fun completedHumanLoginClearsBothOldReauthenticationReasons() {
        val preferences = boundPreferences()
        preferences.reauthenticationRequired = true
        preferences.absoluteDeadlineReauthenticationRequired = true
        preferences.rememberPersonalIdentity("subject-example", "person.example", 1_700_000_000L)
        assertFalse(preferences.reauthenticationRequired)
        assertFalse(preferences.absoluteDeadlineReauthenticationRequired)
        assertEquals(1_700_000_000L, preferences.boundPersonalAuthenticatedAtEpochSeconds)
    }
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

    @Test fun savedPersonalLoginNameSurvivesPreferenceReload() {
        val context = RuntimeEnvironment.getApplication()
        AppPreferences(context).reauthenticationHint = "person.example"
        assertEquals("person.example", AppPreferences(context).reauthenticationHint)
    }

    @Test fun currentLogoutCleanupAlsoForgetsTheSavedLoginName() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = AppPreferences(context)
        preferences.reauthenticationHint = "person.example"
        preferences.reauthenticationRequired = true
        preferences.clearReauthentication()
        assertNull(AppPreferences(context).reauthenticationHint)
        assertFalse(AppPreferences(context).reauthenticationRequired)
    }
}
