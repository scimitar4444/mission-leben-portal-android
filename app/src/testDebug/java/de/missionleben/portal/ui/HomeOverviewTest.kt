package de.missionleben.portal.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.Density
import de.missionleben.portal.model.AnnouncementItem
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.model.NewsItem
import de.missionleben.portal.model.PortalApplication
import de.missionleben.portal.model.PortalCapability
import de.missionleben.portal.model.UiState
import de.missionleben.portal.model.UserIdentity
import de.missionleben.portal.push.NotificationBadgeCounts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Synthetic data only. No network, account, enrollment or authentication bypass. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-rDE-w360dp-h760dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeOverviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf(exampleState())
    private val marked = mutableListOf<Long>()
    private var settingsOpened = 0
    private var contactsOpened = 0
    private val openedUrls = mutableListOf<String>()

    private fun show(fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MissionLebenTheme {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        HomeOverview(
                            state = state.value,
                            onSettings = { settingsOpened++ },
                            onContacts = { contactsOpened++ },
                            onStartLogin = {}, onRetryQuickUnlock = {},
                            onOpenUrl = { openedUrls += it }, onOpenPublicUrl = { openedUrls += it },
                            onReloadApplications = {},
                            onMarkAnnouncementRead = {
                                marked += it
                                state.value = state.value.copy(readAnnouncementIds = setOf(it))
                            },
                            onSelfEnrollment = {}, onRefreshDeviceStatus = {}, onEnableQuickUnlock = {},
                            onTalkHandoff = {}, onDismissMessage = {},
                        )
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        val file = File("build/reports/home-layout/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        compose.runOnIdle {
            // Draw the synthetic activity with native graphics. PixelCopy's forceRedraw
            // waits for a real display frame and times out under Robolectric.
            val view = compose.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun sixAppsAndNoticeFitWithoutScrolling() {
        show()
        (0..5).forEach { compose.onNodeWithTag("app-app$it").assertIsDisplayed() }
        compose.onNodeWithText("IHM wird gewartet").assertIsDisplayed()
        compose.onNodeWithText("Neu").assertIsDisplayed()
        compose.onNodeWithText(MESSAGE).assertDoesNotExist()
        assertEquals(emptyList<Long>(), marked)
        screenshot("six-apps-notice-light")
        compose.onNodeWithTag("app-app1").performClick()
        assertEquals(listOf("https://example.invalid/app1"), openedUrls)
    }

    @Test @Config(qualifiers = "de-rDE-w320dp-h680dp-night-xhdpi")
    fun smallDarkScreenStillShowsAllSixApps() {
        show()
        (0..5).forEach { compose.onNodeWithTag("app-app$it").assertIsDisplayed() }
        screenshot("six-apps-notice-dark-320")
    }

    @Test fun threeAppsAndFooter() {
        state.value = state.value.copy(applications = state.value.applications.take(3), announcements = emptyList())
        show()
        compose.onNodeWithTag("account-summary").assertIsDisplayed().performClick()
        assertEquals(1, settingsOpened)
        compose.onNodeWithText("Kontakte").performClick()
        assertEquals(1, contactsOpened)
        screenshot("three-apps")
    }

    @Test fun collapsingDoesNotMarkReadAndReadNoticeCanBeReopened() {
        show()
        compose.onNodeWithTag("announcement-toggle").performClick()
        compose.onNodeWithText(MESSAGE).assertIsDisplayed()
        screenshot("notice-expanded")
        compose.onNodeWithTag("announcement-toggle").performClick()
        assertEquals(emptyList<Long>(), marked)
        compose.onNodeWithTag("announcement-toggle").performClick()
        compose.onNodeWithText("Gelesen").performClick()
        assertEquals(listOf(7L), marked)
        compose.onNodeWithText(MESSAGE).assertDoesNotExist()
        compose.onNodeWithTag("announcement-toggle").performClick()
        compose.onNodeWithText(MESSAGE).assertIsDisplayed()
        compose.onNodeWithText("Zuklappen").performClick()
        compose.onNodeWithText(MESSAGE).assertDoesNotExist()
    }

    @Test fun cachedWarningRemainsVisibleWhileCollapsed() {
        state.value = state.value.copy(announcementsStale = true)
        show()
        compose.onNodeWithText("Zwischengespeicherter Stand", substring = true).assertIsDisplayed()
        compose.onNodeWithText(MESSAGE).assertDoesNotExist()
        screenshot("cached-notice")
    }

    @Test @Config(qualifiers = "de-rDE-w320dp-h680dp-xhdpi")
    fun largeFontStaysScrollableWithWorkingActions() {
        show(fontScale = 1.5f)
        compose.onNodeWithContentDescription("Einstellungen").assertIsDisplayed()
        screenshot("large-font-top")
        (0..5).forEach { compose.onNodeWithTag("app-app$it").performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag("home-overview").performScrollToNode(hasTestTag("account-summary"))
        compose.onNodeWithTag("account-summary").performClick()
        assertEquals(1, settingsOpened)
        screenshot("large-font-bottom")
    }

    @Test fun privilegedToolIsHiddenWithoutCapability() {
        show()
        compose.onNodeWithText("Talk auf Raum öffnen").assertDoesNotExist()
    }

    @Test fun privilegedToolRemainsAvailableWithCapability() {
        state.value = state.value.copy(capabilities = setOf(PortalCapability.OPEN_TALK))
        show()
        compose.onNodeWithText("Talk auf Raum öffnen").performScrollTo().assertIsDisplayed()
    }

    @Test fun newNoticeStartsCollapsedAndUnread() {
        show()
        compose.onNodeWithTag("announcement-toggle").performClick()
        compose.onNodeWithText("Gelesen").performClick()
        compose.runOnIdle {
            state.value = state.value.copy(announcements = listOf(
                AnnouncementItem(8L, "Ein neuer Hinweis", MESSAGE, "IT", 0L),
            ))
        }
        compose.onNodeWithText("Neu").assertIsDisplayed()
        compose.onNodeWithText(MESSAGE).assertDoesNotExist()
        assertEquals(listOf(7L), marked)
    }

    @Test fun signedOutStateDoesNotShowAppsOrContacts() {
        state.value = state.value.copy(signedIn = false)
        show()
        compose.onNodeWithTag("app-app0").assertDoesNotExist()
        compose.onNodeWithText("Kontakte").assertDoesNotExist()
    }

    companion object {
        private const val MESSAGE = "Heute bis voraussichtlich 12:00 Uhr.\nIHM steht während der Wartung nicht zur Verfügung.\nDie anderen Anwendungen kannst du weiter nutzen."
        private fun exampleState() = UiState(
            mode = DeviceMode.PERSONAL, signedIn = true, enrollmentState = EnrollmentState.TRUSTED,
            quickUnlockEnabled = true,
            unreadNotificationBadges = NotificationBadgeCounts(zimbra = 2, talk = 3),
            user = UserIdentity("example", "Maria", "maria@example.invalid", "example",
                System.currentTimeMillis() / 1000 - 2 * 86400),
            applications = listOf("Talk", "Zimbra Mail", "OWA", "IHM", "Sigma", "Warden")
                .mapIndexed { i, name -> PortalApplication(name, "app$i", "https://example.invalid/app$i") },
            announcements = listOf(AnnouncementItem(7L, "IHM wird gewartet", MESSAGE, "IT", 0L)),
            news = listOf(NewsItem("Gemeinsam aktiv: Neues aus unseren Einrichtungen", "https://example.invalid/news", 0L)),
        )
    }
}
