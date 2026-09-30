package de.missionleben.portal.device

import android.app.Application
import android.content.Context
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentProfile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceStatusProfileSyncTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = AppPreferences(context)

    @Before fun reset() {
        context.getSharedPreferences("mission_leben_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun accept(marker: String, deviceId: String = "own-device", mode: DeviceMode = DeviceMode.PERSONAL) =
        DeviceStatusProfileSync.accept(
            context, """{"device_id":"$deviceId","enrollment_profile":$marker}""", "own-device", mode,
        )

    @Test fun `confirmed employee status repairs old registration without re-enrollment`() {
        assertNull(preferences.enrollmentProfile)
        assertTrue(accept("\"personal-employee\""))
        assertEquals(EnrollmentProfile.PERSONAL_EMPLOYEE, preferences.enrollmentProfile)
    }

    @Test fun `server shared account or tablet profile is never elevated to employee`() {
        preferences.enrollmentProfile = EnrollmentProfile.PERSONAL_EMPLOYEE
        assertTrue(accept("\"shared-account-handset\""))
        assertEquals(EnrollmentProfile.SHARED_ACCOUNT_HANDSET, preferences.enrollmentProfile)
        assertTrue(accept("\"facility-tablet\"", mode = DeviceMode.SHARED))
        assertEquals(EnrollmentProfile.FACILITY_TABLET, preferences.enrollmentProfile)
    }

    @Test fun `missing legacy server marker cannot grant employee export`() {
        assertTrue(DeviceStatusProfileSync.accept(context, """{"device_id":"own-device"}""", "own-device", DeviceMode.PERSONAL))
        assertNull(preferences.enrollmentProfile)
        preferences.enrollmentProfile = EnrollmentProfile.SHARED_ACCOUNT_HANDSET
        assertTrue(accept("null"))
        assertEquals(EnrollmentProfile.SHARED_ACCOUNT_HANDSET, preferences.enrollmentProfile)
    }

    @Test fun `foreign invalid or contradictory responses cannot change local profile`() {
        preferences.enrollmentProfile = EnrollmentProfile.SHARED_ACCOUNT_HANDSET
        assertFalse(accept("\"personal-employee\"", deviceId = "foreign-device"))
        assertFalse(accept("\"unexpected\""))
        assertFalse(accept("\"\""))
        assertFalse(accept("\"facility-tablet\""))
        assertFalse(accept("\"personal-employee\"", mode = DeviceMode.SHARED))
        assertFalse(DeviceStatusProfileSync.accept(context, "invalid JSON", "own-device", DeviceMode.PERSONAL))
        assertEquals(EnrollmentProfile.SHARED_ACCOUNT_HANDSET, preferences.enrollmentProfile)
    }
}
