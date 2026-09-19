package de.missionleben.portal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortalCapabilityTest {
    @Test
    fun knownCapabilitiesAreParsedAndUnknownValuesFailClosed() {
        assertEquals(
            PortalCapability.OPEN_TALK,
            PortalCapability.fromWireName("open_talk"),
        )
        assertEquals(
            PortalCapability.DEVICE_PROFILE_SWITCH,
            PortalCapability.fromWireName("device_profile_switch"),
        )
        assertNull(PortalCapability.fromWireName("admin"))
        assertNull(PortalCapability.fromWireName("OPEN_TALK"))
    }
}
