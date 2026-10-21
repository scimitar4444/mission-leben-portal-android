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
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.missionleben.portal.model.AnnouncementItem
import de.missionleben.portal.model.DeviceMode
import de.missionleben.portal.model.EnrollmentState
import de.missionleben.portal.model.NewsItem
import de.missionleben.portal.model.PortalApplication
import de.missionleben.portal.model.PortalCapability
import de.missionleben.portal.model.UiState
import de.missionleben.portal.model.UserIdentity
import de.missionleben.portal.model.VaultRequest
import de.missionleben.portal.push.NotificationBadgeCounts
import de.missionleben.portal.calendar.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private var loginStarts = 0
    private var quickUnlockRetries = 0
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
                            onStartLogin = { loginStarts++ }, onRetryQuickUnlock = { quickUnlockRetries++ },
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
        (0..5).forEach { compose.onNodeWithTag("app-app$it").assertHeightIsEqualTo(72.dp) }
        (0..5).forEach {
            compose.onNodeWithTag("app-symbol-app$it", useUnmergedTree = true)
                .assertWidthIsEqualTo(32.dp).assertHeightIsEqualTo(32.dp)
        }
        compose.onNodeWithText("IHM wird gewartet").assertIsDisplayed()
        compose.onNodeWithText("Neu").assertIsDisplayed()
        compose.onNodeWithText(MESSAGE).assertDoesNotExist()
        assertEquals(emptyList<Long>(), marked)
        screenshot("six-apps-notice-light")
        compose.onNodeWithTag("app-app1").performClick()
        assertEquals(listOf("https://example.invalid/app1"), openedUrls)
    }

    @Test fun documentSymbolAndLargeBadgePreserveTileSizeAndNavigation() {
        state.value = state.value.copy(
            applications = listOf(PortalApplication("ML Dokumente", "projectsend-ml-dokumente-test",
                "https://example.invalid/documents")),
            unreadNotificationBadges = NotificationBadgeCounts(documents = 101),
        )
        show()
        compose.onNodeWithText("99+").assertIsDisplayed()
        compose.onNodeWithTag("app-projectsend-ml-dokumente-test")
            .assertHeightIsEqualTo(72.dp).performClick()
        assertEquals(listOf("https://example.invalid/documents"), openedUrls)
        screenshot("document-symbol-and-badge")
    }

    @Test fun sixDifferentSymbolsWithNotice() {
        state.value = state.value.copy(applications = listOf(
            PortalApplication("Talk", "talk", "https://example.invalid/talk"),
            PortalApplication("Zimbra Mail", "zimbra-mail", "https://example.invalid/mail"),
            PortalApplication("ML Dokumente", "projectsend-ml-dokumente-test", "https://example.invalid/documents"),
            PortalApplication("Warden", "warden", "https://example.invalid/warden"),
            PortalApplication("Nextcloud", "nextcloud", "https://example.invalid/cloud"),
            PortalApplication("Video Zentral", "peertube", "https://example.invalid/video"),
        ))
        show()
        state.value.applications.forEach {
            compose.onNodeWithTag("app-" + it.slug).assertIsDisplayed().assertHeightIsEqualTo(72.dp)
        }
        screenshot("six-symbols-light")
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
        compose.onNodeWithContentDescription("Kontakte").performClick()
        assertEquals(1, contactsOpened)
        screenshot("three-apps")
    }

    @Test @Config(qualifiers = "de-rDE-w320dp-h680dp-xhdpi")
    fun contactsHeaderSitsAboveGreetingWithAFullTouchTargetEvenForLongNames() {
        state.value = state.value.copy(user = state.value.user?.copy(displayName = "Alexandra-Maria Mustermann"))
        show()
        val greeting = compose.onNodeWithTag("greeting-copy", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val contacts = compose.onNodeWithTag("contacts-header").assertIsDisplayed().fetchSemanticsNode()
        assertTrue(contacts.boundsInRoot.bottom <= greeting.top)
        // IconButton's visible icon area is 40dp, but its expanded touch target must remain 48dp.
        val minimumTouchSize = with(compose.density) { 48.dp.toPx() }
        assertTrue(contacts.touchBoundsInRoot.width >= minimumTouchSize)
        assertTrue(contacts.touchBoundsInRoot.height >= minimumTouchSize)
        compose.onNodeWithTag("contacts-header").performClick()
        assertEquals(1, contactsOpened)
        screenshot("compact-contact-header-long-name-320")
    }

    @Test fun pendingFirstNoticeDoesNotShowAnEmptyWarningCardButLoadedNoticeStillAppears() {
        val notices = state.value.announcements
        state.value = state.value.copy(announcements = emptyList(), announcementsLoading = true)
        show()
        compose.onNodeWithTag("announcements-panel").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(announcements = notices) }
        compose.onNodeWithTag("announcements-panel").assertIsDisplayed()
        compose.onNodeWithText("IHM wird gewartet").assertIsDisplayed()
    }

    @Test fun collapsingDoesNotMarkReadAndExplicitReadRemovesNoticeFromHome() {
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
        compose.onNodeWithTag("announcements-panel").assertDoesNotExist()
        assertEquals(7L, state.value.announcements.single().id) // Kept for the bell, not deleted.
    }

    @Test fun readAnnouncementRemainsReadableInTheBell() {
        state.value = state.value.copy(readAnnouncementIds = setOf(7L))
        compose.setContent {
            MissionLebenTheme {
                NoticeInboxScreen(state.value, onBack = {}, onRefresh = {}, onOpenNotice = {},
                    onMarkAllRead = {}, onMarkAnnouncementRead = {})
            }
        }
        compose.onNodeWithText("IHM wird gewartet").assertIsDisplayed().performClick()
        compose.onAllNodesWithText(MESSAGE).assertCountEquals(2) // List preview and opened full-text dialog.
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

    @Test fun pendingSealCannotExposeEvenPreviouslyLoadedProtectedTiles() {
        state.value = state.value.copy(signedIn = false, user = null, busy = true, vaultRequest = VaultRequest.SEAL)
        show()
        (0..5).forEach { compose.onNodeWithTag("app-app$it").assertDoesNotExist() }
        compose.onNodeWithText("Kontakte").assertDoesNotExist()
    }

    @Test fun cancelledSealKeepsPersonalOverviewLocked() {
        state.value = state.value.copy(signedIn = false, user = null, busy = false,
            vaultRequest = VaultRequest.NONE, message = "Schnellzugang wurde abgebrochen.")
        show()
        (0..5).forEach { compose.onNodeWithTag("app-app$it").assertDoesNotExist() }
        compose.onNodeWithText("Kontakte").assertDoesNotExist()
        assertEquals(0, loginStarts)
    }

    @Test fun personalWithoutQuickUnlockOffersManualAuthentikLogin() {
        state.value = state.value.copy(
            signedIn = false, user = null, quickUnlockEnabled = false,
            message = "Bitte Biometrie oder eine sichere Displaysperre einrichten.",
        )
        show()
        // Current behavior: a setup failure does not start the browser automatically.
        assertEquals(0, loginStarts)
        compose.onNodeWithText("Mit Authentik anmelden").assertIsDisplayed().performClick()
        assertEquals(1, loginStarts)
        assertEquals(0, quickUnlockRetries)
        compose.onNodeWithTag("app-app0").assertDoesNotExist()
    }

    @Test fun savedVaultAfterSetupFailureStillOffersRetryAndSeparateAuthentikButton() {
        state.value = state.value.copy(
            signedIn = false, user = null, quickUnlockEnabled = true,
            message = "Bitte Biometrie oder eine sichere Displaysperre einrichten.",
        )
        show()
        // A removed screen lock can leave the old vault present: current UI still
        // offers retry, but the separate Authentik action remains accessible.
        compose.onNodeWithText("Mit Authentik anmelden").assertIsDisplayed().performClick()
        assertEquals(1, loginStarts)
        assertEquals(0, quickUnlockRetries)
    }

    private fun withToday(): UiState {
        val now = System.currentTimeMillis()
        return state.value.copy(calendarAvailable = true, calendarSyncEnabled = false,
            todayFetchedAtMillis = now,
            todayEvents = listOf(
                CalendarEvent("example-one", "Besprechung im Team", "", now, now + 3_600_000L, false),
                CalendarEvent("example-two", "Übergabe", "", now + 60_000L, now + 3_600_000L, false)),
            applications = listOf(
                PortalApplication("Talk", "talk", "https://example.invalid/talk"),
                PortalApplication("E-Mail", "zimbra-mail", "https://example.invalid/mail"),
                PortalApplication("IT-Service", "otobo-mitarbeiter", "https://it-service.mission-leben.de:8443/otobo/customer.pl"),
                PortalApplication("IHM", "ihm", "https://example.invalid/ihm"),
                PortalApplication("Sigma", "sigma", "https://example.invalid/sigma"),
                PortalApplication("Warden", "warden", "https://example.invalid/warden")))
    }

    @Test @Config(qualifiers = "de-rDE-w320dp-h680dp-xhdpi")
    fun todayAndMaintenanceKeepSixAppTilesCompact() {
        state.value = withToday()
        show()
        compose.onNodeWithTag("today-agenda").assertIsDisplayed()
        state.value.applications.forEach {
            compose.onNodeWithTag("app-" + it.slug).assertIsDisplayed().assertHeightIsEqualTo(72.dp)
        }
        compose.onNodeWithText("Übergabe").assertDoesNotExist()
        screenshot("today-six-apps-helpdesk-320")
        compose.onNodeWithTag("app-otobo-mitarbeiter").performClick()
        assertEquals(listOf("https://it-service.mission-leben.de:8443/otobo/customer.pl"), openedUrls)
    }

    @Test fun todayExpandsCollapsesAndOpensTheCorrectCalendarDayWithoutEnablingExport() {
        state.value = withToday()
        val event = state.value.todayEvents[1]
        show()
        compose.onNodeWithTag("today-toggle").performClick()
        compose.onNodeWithText("Übergabe").assertIsDisplayed().performClick()
        assertEquals(listOf(de.missionleben.portal.BuildConfig.ZIMBRA_WEB_BASE_URL.trimEnd('/') +
            "/modern/calendar/day/" + event.startMillis), openedUrls)
        compose.onNodeWithTag("today-toggle").performClick()
        compose.onNodeWithText("Übergabe").assertDoesNotExist()
        assertEquals(false, state.value.calendarSyncEnabled)
    }

    @Test fun hidingNoticeNeverHidesAppointmentsOrResetsTheirExpandedState() {
        state.value = withToday()
        show()
        compose.onNodeWithTag("today-toggle").performClick()
        compose.onNodeWithText("Übergabe").assertIsDisplayed()
        compose.onNodeWithTag("announcement-toggle").performClick()
        compose.onNodeWithText("Übergabe").assertIsDisplayed()
        compose.onNodeWithTag("announcement-toggle").performClick()
        compose.onNodeWithTag("announcement-mark-read").performClick()
        compose.onNodeWithTag("announcements-panel").assertDoesNotExist()
        compose.onNodeWithTag("today-agenda").assertIsDisplayed()
        compose.onNodeWithText("Besprechung im Team").assertIsDisplayed()
        compose.onNodeWithText("Übergabe").assertIsDisplayed()
        screenshot("appointments-remain-after-notice-read")
    }

    @Test fun lateAnnouncementDoesNotReplaceAnAlreadyExpandedAgenda() {
        state.value = withToday().copy(announcements = emptyList())
        show()
        compose.onNodeWithTag("today-toggle").performClick()
        compose.runOnIdle { state.value = state.value.copy(announcements = exampleState().announcements) }
        compose.onNodeWithTag("today-agenda").assertIsDisplayed()
        compose.onNodeWithText("Übergabe").assertIsDisplayed()
        compose.onNodeWithTag("announcements-panel").assertIsDisplayed()
        compose.onNodeWithTag("announcement-mark-read").assertIsDisplayed()
        screenshot("separate-appointments-and-notice")
    }

    @Test fun todayDisappearsForRevokedZimbraSharedDevicesAndSignedOutUsers() {
        state.value = withToday()
        val allowed = state.value
        show()
        compose.onNodeWithTag("today-agenda").assertIsDisplayed()
        listOf(
            allowed.copy(applications = allowed.applications.filterNot { it.slug == "zimbra-mail" }),
            allowed.copy(calendarAvailable = false),
            allowed.copy(mode = DeviceMode.SHARED),
            allowed.copy(signedIn = false),
            allowed.copy(enrollmentState = EnrollmentState.PENDING),
            allowed.copy(todayFetchedAtMillis = System.currentTimeMillis() - 3 * 3_600_000L),
        ).forEach { denied ->
            compose.runOnIdle { state.value = denied }
            compose.onNodeWithTag("today-agenda").assertDoesNotExist()
        }
    }

    @Test @Config(qualifiers = "de-rDE-w320dp-h680dp-night-xhdpi")
    fun largeFontTodayStillAllowsScrollingToAllSixApps() {
        state.value = withToday()
        show(fontScale = 1.5f)
        compose.onNodeWithTag("today-agenda").assertIsDisplayed()
        state.value.applications.forEach {
            compose.onNodeWithTag("app-" + it.slug).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(72.dp)
        }
        screenshot("today-large-font-dark-320")
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
