package de.missionleben.portal.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppHelpLayoutTest {
    @Test fun recognizesTheHelpAndItsChildGuidesOnTheExactNextcloudOrigin() {
        listOf(
            "https://nextcloud.mission-leben.de/apps/collectives/User-Guides-7",
            "https://nextcloud.mission-leben.de/apps/collectives/User-Guides-7/Android-App-95685099#h-startseite",
            "https://nextcloud.mission-leben.de:443/index.php/apps/collectives/User-Guides-7/Personliches-Handy-95685102",
        ).forEach { assertTrue(it, AppHelpLayout.isHelpPage(it)) }
    }

    @Test fun leavesOtherApplicationsCollectivesAndLoginPagesUntouched() {
        listOf(
            "https://nextcloud.mission-leben.de/apps/collectives/Other-8/Readme",
            "https://nextcloud.mission-leben.de/apps/collectives/User-Guides-70/Readme",
            "https://nextcloud.mission-leben.de/apps/spreed/",
            "https://nextcloud.mission-leben.de/login",
            "https://id.mission-leben.de/if/flow/mission-leben-android-authentication/",
            "http://nextcloud.mission-leben.de/apps/collectives/User-Guides-7/",
            "https://nextcloud.mission-leben.de:8443/apps/collectives/User-Guides-7/",
            "https://user@nextcloud.mission-leben.de/apps/collectives/User-Guides-7/",
            "https://nextcloud.mission-leben.de.evil.test/apps/collectives/User-Guides-7/",
            "https://nextcloud.mission-leben.de/not-apps/collectives/User-Guides-7/",
            "https://nextcloud.mission-leben.de/apps/collectives/%55ser-Guides-7/",
            "not a URL",
        ).forEach { assertFalse(it, AppHelpLayout.isHelpPage(it)) }
    }

    @Test fun hidesOnlyTheWebHeaderAndReclaimsItsSpaceWithReversibleSpaStyling() {
        val script = AppHelpLayout.SCRIPT
        assertTrue(script.contains("location.origin !== 'https://nextcloud.mission-leben.de'"))
        assertTrue(script.contains("window.top !== window"))
        assertTrue(script.contains("#header { display: none !important; }"))
        assertTrue(script.contains("--header-height: 0px"))
        assertTrue(script.contains("top: 0 !important; margin-top: 0 !important"))
        assertTrue(script.contains("root.removeAttribute('data-ml-app-help-embedded')"))
        assertTrue(script.contains("MutationObserver"))
        assertTrue(script.contains("addEventListener('popstate'"))
        assertTrue(script.contains("addEventListener('hashchange'"))
        listOf("fetch(", "XMLHttpRequest", "localStorage", "innerHTML", ".click(", "form.submit(",
            "#app-navigation", "#app-content-vue { display: none").forEach { assertFalse(it, script.contains(it)) }
    }
}
