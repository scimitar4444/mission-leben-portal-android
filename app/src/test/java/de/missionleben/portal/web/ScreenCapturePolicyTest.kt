package de.missionleben.portal.web

import de.missionleben.portal.model.DeviceMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCapturePolicyTest {
    @Test fun personalAppContentNeedsExplicitOptIn() {
        assertTrue(ScreenCapturePolicy.protect(DeviceMode.PERSONAL, false, true, false))
        assertFalse(ScreenCapturePolicy.protect(DeviceMode.PERSONAL, true, true, false))
    }

    @Test fun sharedAndUnknownModesRemainProtected() {
        assertTrue(ScreenCapturePolicy.protect(DeviceMode.SHARED, true, true, false))
        assertTrue(ScreenCapturePolicy.protect(null, true, true, false))
    }

    @Test fun AuthentikAndEnrollmentRemainProtected() {
        assertTrue(ScreenCapturePolicy.protect(DeviceMode.PERSONAL, true, true, true))
        assertTrue(ScreenCapturePolicy.protect(DeviceMode.PERSONAL, true, false, false))
    }
}
