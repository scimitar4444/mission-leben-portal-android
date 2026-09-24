package de.missionleben.portal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentProfileTest {
    @Test
    fun `legacy portal responses preserve existing profiles`() {
        assertEquals(
            EnrollmentProfile.PERSONAL_EMPLOYEE,
            EnrollmentProfile.fromPortalResponse(null, DeviceMode.PERSONAL),
        )
        assertEquals(
            EnrollmentProfile.FACILITY_TABLET,
            EnrollmentProfile.fromPortalResponse(null, DeviceMode.SHARED),
        )
    }

    @Test
    fun `special profile is accepted only for personal app mode`() {
        assertEquals(
            EnrollmentProfile.SHARED_ACCOUNT_HANDSET,
            EnrollmentProfile.fromPortalResponse("shared-account-handset", DeviceMode.PERSONAL),
        )
        assertTrue(EnrollmentProfile.SHARED_ACCOUNT_HANDSET.matches(DeviceMode.PERSONAL))
        assertFalse(EnrollmentProfile.SHARED_ACCOUNT_HANDSET.matches(DeviceMode.SHARED))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `special profile cannot be assigned to facility tablet mode`() {
        EnrollmentProfile.fromPortalResponse("shared-account-handset", DeviceMode.SHARED)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown profile fails closed`() {
        EnrollmentProfile.fromPortalResponse("unexpected", DeviceMode.PERSONAL)
    }
}
