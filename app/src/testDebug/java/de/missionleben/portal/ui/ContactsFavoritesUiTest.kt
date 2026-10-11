package de.missionleben.portal.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.missionleben.portal.data.ContactPage
import de.missionleben.portal.data.ContactFacility
import de.missionleben.portal.data.EmployeeContact
import de.missionleben.portal.data.FavoriteContactPage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Synthetic directory only; no real contacts, network, credentials or authorization bypass. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-rDE-w360dp-h760dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactsFavoritesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val maria = contact("maria", "Maria Muster")
    private val anna = contact("anna", "Anna Beispiel")
    private val toggled = mutableListOf<String>()
    private val composed = mutableListOf<String>()
    private var saved = setOf("maria")
    private val favoriteFilters = mutableListOf<Pair<Boolean, String>>()

    private fun show(persistent: Boolean = true, favoriteInMine: Boolean = true) {
        compose.setContent {
            MissionLebenTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ContactsScreen(
                        search = { query, mine, _, facility, _ ->
                            // Maria is beyond the first house page, but the scoped lookup finds her.
                            val candidates = if (mine || facility.isNotBlank()) listOf(anna) else listOf(anna, maria)
                            val matches = candidates.filter { it.name.contains(query.trim(), true) }
                            ContactPage(matches, matches.size, null, listOf("01 – Zentrale"),
                                listOf(ContactFacility("ORG_ML_H001", "01 – Zentrale"),
                                    ContactFacility("ORG_ML_H015", "15 – Anderes Haus")))
                        },
                        loadFavorites = { mine, facility, _ ->
                            favoriteFilters += mine to facility
                            FavoriteContactPage(saved, listOf(maria).filter {
                                it.id in saved && facility != "ORG_ML_H015" && (!mine || favoriteInMine)
                            })
                        },
                        toggleFavorite = {
                            toggled += it.id
                            saved = if (it.id in saved) saved - it.id else saved + it.id
                            true
                        },
                        onBack = {}, onEmail = { composed += it }, busy = false, message = null,
                        favoritesPersistent = persistent,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    @Test fun favoritesAppearBeforeOtherContactsAndStarsToggleInPlace() {
        show()
        compose.onNodeWithText("Favoriten").assertIsDisplayed()
        compose.onNodeWithText("Weitere Kontakte").assertIsDisplayed()
        val favoriteTop = compose.onNodeWithText("Maria Muster").fetchSemanticsNode().boundsInRoot.top
        val ordinaryTop = compose.onNodeWithText("Anna Beispiel").fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue(favoriteTop < ordinaryTop)
        compose.onNodeWithTag("favorite-maria").performClick()
        compose.onNodeWithText("Favoriten").assertDoesNotExist()
        compose.onNodeWithContentDescription("Maria Muster als Favorit speichern").assertExists()
        assertEquals(listOf("maria"), toggled)
        compose.onNodeWithTag("favorite-anna").performClick()
        compose.onNodeWithText("Favoriten").assertIsDisplayed()
        assertEquals(listOf("maria", "anna"), toggled)
        screenshot("contact-favorites")
    }

    @Test fun searchDoesNotPinAnUnrelatedFavoriteAndEmailKeepsItsExistingAction() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("Anna")
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.onNodeWithText("Anna Beispiel").assertIsDisplayed()
        compose.onNodeWithText("Maria Muster").assertDoesNotExist()
        compose.onNodeWithText("Favoriten").assertDoesNotExist()
        compose.onNodeWithText("anna@example.invalid").performClick()
        assertEquals(listOf("anna@example.invalid"), composed)
    }

    @Test fun sharedTabletExplainsThatFavoritesAreSessionOnly() {
        show(persistent = false)
        compose.onNodeWithText("Nur für diese Anmeldung gespeichert.").assertIsDisplayed()
    }

    @Test fun myHousePinsItsFavoriteEvenWhenItIsNotOnTheFirstResultPage() {
        show()
        compose.onNodeWithText("Meine Einrichtung").performClick()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.onNodeWithText("Favoriten").assertIsDisplayed()
        compose.onNodeWithContentDescription("Maria Muster aus Favoriten entfernen").assertExists()
        assertEquals(true to "", favoriteFilters.last())
        org.junit.Assert.assertTrue(compose.onNodeWithText("Maria Muster").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText("Anna Beispiel").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun nonMatchingMyHouseHidesTheFavoriteWithoutDeletingItsAllHouseReference() {
        // Use the same server-scoped house path without a platform Dialog/IME in this JVM fixture.
        // A non-matching house must not pin Maria from the All lookup, but must retain her reference.
        show(favoriteInMine = false)
        compose.onNodeWithText("Meine Einrichtung").performClick()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.onNodeWithText("Favoriten").assertDoesNotExist()
        compose.onNodeWithText("Maria Muster").assertDoesNotExist()
        assertEquals(true to "", favoriteFilters.last())
        compose.onNodeWithText("Alle", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Maria Muster aus Favoriten entfernen").assertExists()
    }

    private fun screenshot(name: String) {
        val file = File("build/reports/home-layout/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun contact(id: String, name: String) = EmployeeContact(
        id, name, "$id@example.invalid", "06151 123456", "", "", "", listOf("01 – Zentrale"))
}
