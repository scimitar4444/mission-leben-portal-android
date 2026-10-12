package de.missionleben.portal.data

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApplicationFavoritesStoreTest {
    private fun store() = ApplicationFavoritesStore(RuntimeEnvironment.getApplication())
    @Before fun clear() { store().clear() }

    @Test fun favoritesPersistPerAuthenticatedSubjectAndNeverAddRights() {
        store().save("person-a", setOf("zimbra", "talk"))
        assertEquals(setOf("zimbra", "talk"), store().load("person-a"))
        assertTrue(store().load("person-b").isEmpty())
        assertTrue(store().load("").isEmpty())
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("application_favorites", Context.MODE_PRIVATE)
        assertFalse(preferences.all.toString().contains("person-a"))
    }

    @Test fun returnedSetsAreDefensiveAndInvalidEntriesAreIgnored() {
        store().save("person-a", setOf("zimbra", "", " ", "x".repeat(201)))
        val mutable = store().load("person-a").toMutableSet()
        mutable.add("other")
        assertEquals(setOf("zimbra"), store().load("person-a"))
        store().save("", setOf("not-authorized"))
        assertTrue(store().load("").isEmpty())
    }

    @Test fun profileResetClearsAllScopesAndListsAreBounded() {
        store().save("person-a", (1..120).map { "app-$it" }.toSet())
        store().save("person-b", setOf("talk"))
        assertEquals(100, store().load("person-a").size)
        store().clear()
        assertTrue(store().load("person-a").isEmpty())
        assertTrue(store().load("person-b").isEmpty())
    }
}
