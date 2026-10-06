package de.missionleben.portal.ui

import android.app.Application
import android.content.res.Configuration
import de.missionleben.portal.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GreetingMotivationTest {
    private fun messages(language: String): List<String> {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        return application.createConfigurationContext(configuration)
            .resources.getStringArray(R.array.motivation_messages).toList()
    }

    @Test fun germanGreetingsAreShortAndDistinct() {
        val messages = messages("de")
        assertEquals(20, messages.size)
        assertEquals(20, messages.toSet().size)
        assertTrue(messages.all { it.isNotBlank() && it.length <= 45 })
        assertEquals("Schön, dass du da bist.", messages.first())
        assertTrue(messages.contains("Du darfst auch an dich denken."))
    }

    @Test fun theSharedArrayResolvesEverySupportedLanguage() {
        val german = messages("de")
        for (language in listOf("en", "es", "fr", "hi", "pl", "ro", "tr", "uk")) {
            val translated = messages(language)
            assertEquals(language, german.size, translated.size)
            assertEquals(language, translated.size, translated.toSet().size)
            assertTrue(language, translated.all { it.isNotBlank() })
            assertFalse("German fallback in $language", translated.any { it in german })
        }
    }
}
