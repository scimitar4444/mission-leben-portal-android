package de.missionleben.portal.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContactFavoritesTest {
    private fun contact(id: String, name: String = "Muster, Maria") =
        EmployeeContact(id, name, "$id@example.invalid", "", "", "", "", emptyList())
    private fun page(contacts: List<EmployeeContact>, next: Int? = null) =
        ContactPage(contacts, contacts.size, next, emptyList())

    @Test fun favoritesArePinnedOnceWithoutChangingTheOrderOfOtherContacts() {
        val a = contact("a"); val b = contact("b"); val c = contact("c")
        assertEquals(listOf(b, a, c), ContactFavorites.sorted(listOf(a, b, c, b), setOf("b")))
    }

    @Test fun referencesContainOnlyTheStableIdAndACompanyDirectorySearchKey() {
        assertEquals(ContactFavorite("a", "a@example.invalid"), ContactFavorite.from(contact("a")))
        assertEquals("Muster, Maria", ContactFavorite.from(contact("a").copy(email = "")).query)
    }

    @Test fun reopenedFavoritesUseFreshAuthorizedRecordsNotCachedNamesOrNumbers() = runBlocking {
        val fresh = contact("a", "Neuer, Name").copy(phone = "06151 100")
        var calls = 0
        val result = ContactFavorites.resolve(listOf(ContactFavorite("a", "a@example.invalid"))) { _, _ ->
            calls++; page(listOf(fresh))
        }
        assertEquals(listOf(fresh), result.contacts)
        assertEquals(1, calls)
    }

    @Test fun missingOrReassignedSearchResultsCannotSubstituteAnotherPersonsRecord() = runBlocking {
        val result = ContactFavorites.resolve(listOf(ContactFavorite("old-id", "same@example.invalid"))) { _, _ ->
            page(listOf(contact("different-id")))
        }
        assertTrue(result.contacts.isEmpty())
    }

    @Test fun lookupPaginationIsBoundedAndStopsImmediatelyAtTheMatchingId() = runBlocking {
        val offsets = mutableListOf<Int>()
        val result = ContactFavorites.resolve(listOf(ContactFavorite("b", "Muster"))) { _, offset ->
            offsets += offset
            if (offset == 0) page(listOf(contact("a")), 40) else page(listOf(contact("b")), 80)
        }
        assertEquals(listOf(0, 40), offsets)
        assertEquals(listOf("b"), result.contacts.map { it.id })
        var calls = 0
        ContactFavorites.resolve(listOf(ContactFavorite("missing", "Muster"))) { _, offset ->
            calls++; page(emptyList(), offset + 40)
        }
        assertEquals(3, calls)
    }

    @Test fun anAccessFailureDoesNotReturnPartialFavorites() = runBlocking {
        var calls = 0
        try {
            ContactFavorites.resolve(listOf(ContactFavorite("a", "a"), ContactFavorite("b", "b"))) { _, _ ->
                if (++calls == 1) page(listOf(contact("a"))) else throw ContactsAccessException()
            }
            fail("access failure must propagate")
        } catch (_: ContactsAccessException) { }
    }
}
