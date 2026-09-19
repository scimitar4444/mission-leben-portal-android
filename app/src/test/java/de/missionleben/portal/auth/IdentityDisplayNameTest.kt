package de.missionleben.portal.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityDisplayNameTest {
    @Test
    fun `prefers given name for the personal greeting`() {
        assertEquals(
            "Alex",
            IdentityDisplayName.select(
                givenName = "Alex",
                fullName = "Alex Muster",
                preferredUsername = "a.muster",
                email = "alex@example.org",
                fallback = "Mitarbeiter:in",
            ),
        )
    }

    @Test
    fun `falls back through stable identity claims`() {
        assertEquals("a.muster", IdentityDisplayName.select("", null, "a.muster", "c@example.org", "Mitarbeiter:in"))
        assertEquals("Mitarbeiter:in", IdentityDisplayName.select(null, " ", "", null, "Mitarbeiter:in"))
    }
}
