package de.missionleben.portal.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalApplicationFilterTest {
    @Test
    fun `accepts only the central Authentik mobile group`() {
        assertTrue(PortalApplicationFilter.isMobileGroup("Mobil erreichbar"))
        assertTrue(PortalApplicationFilter.isMobileGroup("  Mobil erreichbar  "))

        assertFalse(PortalApplicationFilter.isMobileGroup(null))
        assertFalse(PortalApplicationFilter.isMobileGroup(""))
        assertFalse(PortalApplicationFilter.isMobileGroup("Intern"))
        assertFalse(PortalApplicationFilter.isMobileGroup("mobil erreichbar"))
    }
}
