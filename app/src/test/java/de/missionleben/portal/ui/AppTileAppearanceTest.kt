package de.missionleben.portal.ui

import androidx.compose.ui.graphics.Color
import de.missionleben.portal.model.PortalApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.pow

class AppTileAppearanceTest {
    private fun appearance(slug: String, name: String = "Application") =
        AppTileAppearance.from(PortalApplication(name, slug, "https://example.invalid/"))

    @Test fun `known applications have distinct recognizable symbols`() {
        assertEquals(AppTileAppearance.CHAT, appearance("talk"))
        assertEquals(AppTileAppearance.CHAT, appearance("nextcloud-talk"))
        assertEquals(AppTileAppearance.MAIL, appearance("zimbra-mail"))
        assertEquals(AppTileAppearance.MAIL, appearance("exchange-owa"))
        assertEquals(AppTileAppearance.DOCUMENTS, appearance("projectsend-ml-dokumente-test"))
        assertEquals(AppTileAppearance.SECURITY, appearance("warden"))
        assertEquals(AppTileAppearance.SECURITY, appearance("vaultwarden"))
        assertEquals(AppTileAppearance.CLOUD, appearance("nextcloud-mission-leben"))
        assertEquals(AppTileAppearance.VIDEO, appearance("peertube"))
        assertEquals(AppTileAppearance.SUPPORT, appearance("otobo-mitarbeiter"))
        assertEquals(AppTileAppearance.PRINT, appearance("savapage"))
    }

    @Test fun `existing short labels also work in synthetic previews`() {
        assertEquals(AppTileAppearance.MAIL, appearance("app1", "Zimbra Mail"))
        assertEquals(AppTileAppearance.MAIL, appearance("app2", "OWA"))
        assertEquals(AppTileAppearance.CHAT, appearance("app0", "Talk"))
        assertEquals(AppTileAppearance.DOCUMENTS, appearance("app3", "ML Dokumente"))
    }

    @Test fun `unknown apps keep a labelled fallback without guessing from their url`() {
        val app = PortalApplication("Neue Anwendung", "future-app", "https://example.invalid/talk")
        assertEquals(AppTileAppearance.MONOGRAM, AppTileAppearance.from(app))
        assertEquals("N", AppTileAppearance.monogram(app.name))
        assertEquals(AppTileAppearance.MONOGRAM, appearance("ihm", "IHM"))
        assertEquals(AppTileAppearance.MONOGRAM, appearance("sigma", "Sigma"))
    }

    @Test fun `canonical slug survives renamed display name`() {
        assertEquals(AppTileAppearance.MAIL, appearance("zimbra-mail", "Meine Nachrichten"))
        assertEquals(AppTileAppearance.CHAT, appearance("nextcloud-talk", "Teamgespräche"))
    }

    @Test fun `appearance never changes application identity or destination`() {
        val app = PortalApplication("Mail", "zimbra-mail", "https://example.invalid/message/42")
        val before = app.copy()
        AppTileAppearance.from(app)
        assertEquals(before, app)
    }

    @Test fun `symbols and monograms have accessible contrast in both themes`() {
        AppTileAppearance.entries.forEach { style ->
            assertTrue("${style.name} light", contrast(style.lightForeground, style.lightBackground) >= 4.5)
            assertTrue("${style.name} dark", contrast(style.darkForeground, style.darkBackground) >= 4.5)
        }
    }

    @Test fun `fallback handles whitespace empty names and supplementary characters`() {
        assertEquals("I", AppTileAppearance.monogram("  IHM "))
        assertEquals("?", AppTileAppearance.monogram(" "))
        assertEquals("Ö", AppTileAppearance.monogram("Öffentlich"))
        assertEquals("📅", AppTileAppearance.monogram("📅 Termine"))
    }

    @Test fun `matching is independent of phone language`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(AppTileAppearance.PRINT, appearance("SAVAPAGE"))
            assertEquals("I", AppTileAppearance.monogram("ihm"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun contrast(foreground: Color, background: Color): Double {
        fun luminance(color: Color): Double {
            fun linear(value: Float): Double = if (value <= 0.04045f) value / 12.92 else
                ((value + 0.055) / 1.055).pow(2.4)
            return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
        }
        val a = luminance(foreground)
        val b = luminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
