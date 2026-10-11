package de.missionleben.portal.data

data class FavoriteContactPage(val ids: Set<String>, val contacts: List<EmployeeContact>)

object ContactFavorites {
    /** A favorite never grants visibility: every returned record comes from today's authorized search. */
    suspend fun resolve(
        references: List<ContactFavorite>,
        search: suspend (String, Int) -> ContactPage,
    ): FavoriteContactPage {
        val contacts = mutableListOf<EmployeeContact>()
        for (reference in references.distinctBy { it.id }.take(ContactFavoritesStore.MAX_FAVORITES)) {
            var offset = 0
            for (pageIndex in 0 until 3) {
                val page = search(reference.query, offset)
                val match = page.contacts.firstOrNull { it.id == reference.id }
                if (match != null) { contacts += match; break }
                val next = page.nextOffset ?: break
                if (next <= offset) break
                offset = next
            }
        }
        return FavoriteContactPage(references.take(ContactFavoritesStore.MAX_FAVORITES).map { it.id }.toSet(),
            contacts.distinctBy { it.id })
    }

    fun sorted(contacts: List<EmployeeContact>, favoriteIds: Set<String>): List<EmployeeContact> =
        contacts.distinctBy { it.id }.sortedBy { if (it.id in favoriteIds) 0 else 1 }
}
