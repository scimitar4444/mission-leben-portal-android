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
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContactFavoritesStoreTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private fun store() = ContactFavoritesStore(RuntimeEnvironment.getApplication()) { key }
    private val favorite = ContactFavorite("record-example", "maria@example.invalid")
    @Before fun clear() { store().clearAll() }

    @Test fun preferencesPersistOnlyAuthenticatedCiphertextNotContactDetails() {
        store().write("person-a", "device-a", listOf(favorite))
        assertEquals(listOf(favorite), store().read("person-a", "device-a"))
        val values = RuntimeEnvironment.getApplication().getSharedPreferences("mission_leben_contact_favorites", Context.MODE_PRIVATE).all
        assertFalse(values.toString().contains("maria@example.invalid"))
        assertFalse(values.toString().contains("record-example"))
    }

    @Test fun theOtherAccountOrDeviceNeverGetsTheseFavorites() {
        store().write("person-a", "device-a", listOf(favorite))
        assertTrue(store().read("person-b", "device-a").isEmpty())
        assertTrue(store().read("person-a", "device-b").isEmpty())
        assertTrue(store().read("", "device-a").isEmpty())
    }

    @Test fun swappingTwoEncryptedRecordsFailsBecauseTheirScopesAreAuthenticated() {
        store().write("person-a", "device-a", listOf(favorite))
        store().write("person-b", "device-a", emptyList())
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("mission_leben_contact_favorites", Context.MODE_PRIVATE)
        val entries = preferences.all.entries.toList()
        preferences.edit().putString(entries[0].key, entries[1].value as String)
            .putString(entries[1].key, entries[0].value as String).commit()
        assertTrue(store().read("person-a", "device-a").isEmpty())
        assertTrue(store().read("person-b", "device-a").isEmpty())
    }

    @Test fun keyLossAndCorruptPayloadFailClosedAndClearingRemovesAllScopes() {
        store().write("person-a", "device-a", listOf(favorite))
        val otherKey = SecretKeySpec(ByteArray(32) { 42 }, "AES")
        assertTrue(ContactFavoritesStore(RuntimeEnvironment.getApplication()) { otherKey }.read("person-a", "device-a").isEmpty())
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("mission_leben_contact_favorites", Context.MODE_PRIVATE)
        preferences.edit().putString(preferences.all.keys.single(), "broken-json").commit()
        assertTrue(store().read("person-a", "device-a").isEmpty())
        store().clearAll()
        assertTrue(store().read("person-a", "device-a").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun favoriteListsRemainBounded() {
        store().write("person-a", "device-a", List(21) { ContactFavorite("id-$it", "query-$it") })
    }
}
