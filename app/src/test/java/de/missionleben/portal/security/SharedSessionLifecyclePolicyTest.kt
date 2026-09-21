package de.missionleben.portal.security

import de.missionleben.portal.model.DeviceMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedSessionLifecyclePolicyTest {
    @Test
    fun `shared session is invalidated when screen was turned off`() {
        assertTrue(
            SharedSessionLifecyclePolicy.shouldInvalidate(
                mode = DeviceMode.SHARED,
                screenTurnedOff = true,
            ),
        )
    }

    @Test
    fun `shared session stays active while app is only minimized`() {
        assertFalse(
            SharedSessionLifecyclePolicy.shouldInvalidate(
                mode = DeviceMode.SHARED,
                screenTurnedOff = false,
            ),
        )
    }

    @Test
    fun `personal session is never invalidated by shared tablet policy`() {
        assertFalse(
            SharedSessionLifecyclePolicy.shouldInvalidate(
                mode = DeviceMode.PERSONAL,
                screenTurnedOff = true,
            ),
        )
    }
}
