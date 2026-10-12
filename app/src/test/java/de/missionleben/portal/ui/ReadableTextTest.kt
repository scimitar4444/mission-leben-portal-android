package de.missionleben.portal.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ReadableTextTest {
    private fun resolve(color: Color = Color.Unspecified, style: Color = Color.Unspecified,
        content: Color = Ink, primary: Color = Coral) = readableTextColor(color, style, content, primary)

    @Test fun explicitLightAccentUsesDarkerForeground() {
        assertEquals(CoralText, resolve(color = Coral))
    }

    @Test fun inheritedButtonAndStyleAccentUseTheSameReadableColor() {
        assertEquals(CoralText, resolve(content = Coral))
        assertEquals(CoralText, resolve(style = Coral))
    }

    @Test fun normalTextAndFilledButtonLabelsKeepTheirOriginalColors() {
        assertEquals(Ink, resolve())
        assertEquals(Color.White, resolve(content = Color.White))
        assertEquals(Color.White, resolve(color = Color.White, style = Coral, content = Coral))
    }

    @Test fun explicitAndStyleColorsKeepMaterialPrecedence() {
        assertEquals(Color.Blue, resolve(color = Color.Blue, style = Coral, content = Coral))
        assertEquals(Color.Blue, resolve(style = Color.Blue, content = Coral))
    }

    @Test fun disabledAlphaAndUnrelatedColorsAreNotAltered() {
        val disabled = Ink.copy(alpha = 0.38f)
        assertEquals(disabled, resolve(content = disabled))
        assertEquals(Coral.copy(alpha = 0.38f), resolve(color = Coral.copy(alpha = 0.38f)))
        assertEquals(Success, resolve(color = Success))
    }

    @Test fun darkThemeTextIsUnchanged() {
        val darkAccent = Color(0xFFFF8B93)
        assertEquals(darkAccent, resolve(content = darkAccent, primary = darkAccent))
        assertEquals(darkAccent, resolve(color = darkAccent, primary = darkAccent))
        assertEquals(Coral, resolve(color = Coral, primary = darkAccent))
    }

    @Test fun textAccentHasAtLeastFourPointFiveContrastOnEveryNativeLightPanel() {
        for (background in listOf(Color.White, Canvas, Lavender, Color(0xFFFFDADD))) {
            assertTrue("Foreground against $background", contrast(CoralText, background) >= 4.5)
        }
    }

    @Test fun originalBrandPaletteIsNotChanged() {
        assertEquals(Color(0xFFFD4B5A), Coral)
        assertEquals(Color(0xFFD62E43), CoralDark)
        assertEquals(Color(0xFFFCF9FD), Canvas)
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
