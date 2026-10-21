package de.missionleben.portal.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.missionleben.portal.model.AnnouncementItem
import de.missionleben.portal.model.UiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-rDE-w360dp-h760dp-xhdpi", application = Application::class)
class AnnouncementHistoryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun deletedOrExpiredPostRemainsNativeReadableWithoutAnActiveHomeEntry() {
        val item = AnnouncementItem(19, "Archivierter Testhinweis", "Der ursprüngliche Text bleibt lesbar.", "IT",
            System.currentTimeMillis() / 1000 - 3600, archived = true)
        val state = mutableStateOf(UiState(signedIn = true, announcementHistory = listOf(item),
            announcementHistoryLoaded = true, readAnnouncementIds = setOf(item.id)))
        compose.setContent { MaterialTheme {
            NoticeInboxScreen(state.value, {}, {}, { error("Must not open another application") }, {}, {})
        } }
        compose.onNodeWithText("Hier findest du neue und gelesene Meldungen der letzten 14 Tage.").assertIsDisplayed()
        compose.onNodeWithText("Archiviert").assertIsDisplayed()
        compose.onNodeWithText(item.subject).assertIsDisplayed().performClick()
        compose.onAllNodesWithText(item.message).assertCountEquals(2)
        // An open dialog must disappear too when authorization/refresh clears the history.
        compose.runOnIdle { state.value = state.value.copy(announcementHistory = emptyList(), announcementHistoryUnavailable = true) }
        compose.onNodeWithText(item.subject).assertDoesNotExist()
        compose.onAllNodesWithText(item.message).assertCountEquals(0)
        compose.onNodeWithText("Die Hinweishistorie kann gerade nicht geladen werden. Bitte erneut aktualisieren.").assertIsDisplayed()
    }
}
