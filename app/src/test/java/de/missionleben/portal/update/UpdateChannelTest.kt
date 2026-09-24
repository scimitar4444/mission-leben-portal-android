package de.missionleben.portal.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelTest {
    @Test
    fun recognizedFdroidInstallersManageUpdates() {
        assertTrue(UpdateChannel.isFdroidInstaller("org.fdroid.fdroid"))
        assertTrue(UpdateChannel.isFdroidInstaller("org.fdroid.basic"))
    }

    @Test
    fun otherInstallersKeepInAppUpdates() {
        assertFalse(UpdateChannel.isFdroidInstaller(null))
        assertFalse(UpdateChannel.isFdroidInstaller("com.android.packageinstaller"))
        assertFalse(UpdateChannel.isFdroidInstaller("de.missionleben.portal"))
    }
}
