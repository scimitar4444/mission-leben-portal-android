package de.missionleben.portal.ui

import android.app.Application
import android.content.res.Configuration
import de.missionleben.portal.R
import de.missionleben.portal.web.WebNavigationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.URI
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppHelpTest {
    @Test fun guideUsesTheExistingAndroidCollectivesPageWithoutCredentials() {
        val url = RuntimeEnvironment.getApplication().getString(R.string.help_guide_url)
        val uri = URI(url)
        assertEquals("https", uri.scheme)
        assertEquals("nextcloud.mission-leben.de", uri.host)
        assertEquals("/apps/collectives/User-Guides-7/Android-App-95685099", uri.path)
        assertEquals(null, uri.userInfo)
        assertEquals(null, uri.query)
        assertTrue(WebNavigationPolicy("mission-leben.de", "de.missionleben.portal:/oauth2redirect").isTrustedWebUrl(url))
    }

    @Test fun helpLabelIsLocalizedForAllSupportedLanguages() {
        val application = RuntimeEnvironment.getApplication()
        for (language in listOf("de", "en", "es", "fr", "hi", "pl", "ro", "tr", "uk")) {
            val configuration = Configuration(application.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val label = application.createConfigurationContext(configuration).getString(R.string.help_and_guide)
            assertTrue(language, label.isNotBlank())
            if (language != "de") assertFalse("German fallback in $language", label == "Hilfe & Anleitung")
        }
    }
}
