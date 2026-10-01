package de.missionleben.portal.web

import android.app.Application
import android.os.Looper
import android.webkit.CookieManager
import de.missionleben.portal.data.AppPreferences
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Web reset must not be a device reset or an erasure of the user's downloads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LoginContextClearanceTest {
    @Test fun acknowledgedCookieResetPreservesDeviceBindingAndDownloads() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = AppPreferences(context).apply {
            deviceMode = DeviceMode.PERSONAL
            enrollmentState = EnrollmentState.TRUSTED
            deviceId = "synthetic-device"
        }
        val download = File(context.filesDir, "synthetic-preserved-download.txt")
        download.writeText("Synthetic content, not a user file.")
        try {
            val cookies = CookieManager.getInstance()
            cookies.setCookie("https://example.invalid", "synthetic=value; Secure")
            assertTrue(cookies.hasCookies())
            val results = mutableListOf<Boolean>()
            PortalBrowserActivity.clearLoginContext(context) { results += it }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true), results)
            assertFalse(cookies.hasCookies())
            assertTrue(download.exists())
            assertEquals("Synthetic content, not a user file.", download.readText())
            assertEquals("synthetic-device", preferences.deviceId)
            assertEquals(EnrollmentState.TRUSTED, preferences.enrollmentState)
        } finally {
            download.delete()
        }
    }
}
