package de.missionleben.portal.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityDisplayNameTest {
    @Test
    fun `prefers given name for the personal greeting`() {
        assertEquals(
            "Christian",
            IdentityDisplayName.select(
                givenName = "Christian",
                fullName = "Christian Thiele",
                preferredUsername = "cthiele",
                email = "christian@example.org",
                fallback = "Mitarbeiter:in",
            ),
        )
    }

    @Test
    fun `extracts given name from directory formatted full names`() {
        assertEquals(
            "Christian",
            IdentityDisplayName.select(null, "Thiele, Christian", "cthiele", null, "Mitarbeiter:in"),
        )
        assertEquals(
            "Christian",
            IdentityDisplayName.select(null, "Christian Thiele", "cthiele", null, "Mitarbeiter:in"),
        )
    }

    @Test
    fun `falls back through stable identity claims`() {
        assertEquals("cthiele", IdentityDisplayName.select("", null, "cthiele", "c@example.org", "Mitarbeiter:in"))
        assertEquals("Mitarbeiter:in", IdentityDisplayName.select(null, " ", "", null, "Mitarbeiter:in"))
    }
}
