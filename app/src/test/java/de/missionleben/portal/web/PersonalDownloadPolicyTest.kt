package de.missionleben.portal.web

import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentProfile
import de.missionleben.portal.model.EnrollmentState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalDownloadPolicyTest {
    private fun allowed(
        browserMode: DeviceMode? = DeviceMode.PERSONAL,
        registeredMode: DeviceMode? = DeviceMode.PERSONAL,
        profile: EnrollmentProfile? = EnrollmentProfile.PERSONAL_EMPLOYEE,
        state: EnrollmentState = EnrollmentState.TRUSTED,
        applicationContent: Boolean = true,
        reauthenticationRequired: Boolean = false,
        locked: Boolean = false,
    ) = PersonalDownloadPolicy.allowsExport(
        browserMode, registeredMode, profile, state, applicationContent,
        reauthenticationRequired, locked,
    )

    @Test fun `only unlocked trusted personal employees may export`() {
        assertTrue(allowed())
        assertFalse(allowed(browserMode = DeviceMode.SHARED))
        assertFalse(allowed(registeredMode = DeviceMode.SHARED))
        assertFalse(allowed(profile = EnrollmentProfile.SHARED_ACCOUNT_HANDSET))
        assertFalse(allowed(profile = EnrollmentProfile.FACILITY_TABLET))
        assertFalse(allowed(profile = null))
        assertFalse(allowed(applicationContent = false))
        assertFalse(allowed(reauthenticationRequired = true))
        assertFalse(allowed(locked = true))
        EnrollmentState.entries.filter { it != EnrollmentState.TRUSTED }.forEach {
            assertFalse(allowed(state = it))
        }
    }
}
