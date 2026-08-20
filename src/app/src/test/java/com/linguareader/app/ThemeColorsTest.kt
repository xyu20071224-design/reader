package com.linguareader.app

import com.linguareader.app.data.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外壳配色规则：跟随正文阅读主题，未设置时跟随系统深色。
 */
class ThemeColorsTest {

    @Test
    fun `chrome follows the reading theme`() {
        assertTrue(chromeIsDark(ReaderTheme.DARK, systemDark = false))
        assertFalse(chromeIsDark(ReaderTheme.PAPER, systemDark = true))
        assertFalse(chromeIsDark(ReaderTheme.WHITE, systemDark = true))
        assertFalse(chromeIsDark(ReaderTheme.SEPIA, systemDark = true))
    }

    @Test
    fun `without a reading theme the system decides`() {
        assertTrue(chromeIsDark(null, systemDark = true))
        assertFalse(chromeIsDark(null, systemDark = false))
    }

    @Test
    fun `palette matches the decision`() {
        assertEquals(DarkLinguaPalette, paletteFor(ReaderTheme.DARK, systemDark = false))
        assertEquals(LightLinguaPalette, paletteFor(ReaderTheme.PAPER, systemDark = true))
        assertTrue(DarkLinguaPalette.isDark)
        assertFalse(LightLinguaPalette.isDark)
    }

    @Test
    fun `dark palette keeps text readable on the accent colour`() {
        // 深色下强调色提亮，白字对比度不足，所以强调色上的文字改成墨色。
        assertEquals(DarkLinguaPalette.onAccent.value, DarkLinguaPalette.onAccent.value)
        assertTrue(relativeLuminance(DarkLinguaPalette.onAccent) < relativeLuminance(DarkLinguaPalette.accent))
        assertTrue(relativeLuminance(LightLinguaPalette.onAccent) > relativeLuminance(LightLinguaPalette.accent))
        // 夜间底色与正文「夜间」主题一致，避免打开弹层时白屏闪光。
        assertEquals(ReaderTheme.DARK.background.lowercase(), "#171717")
        assertTrue(relativeLuminance(DarkLinguaPalette.paper) < 0.05f)
        assertTrue(relativeLuminance(DarkLinguaPalette.ink) > 0.6f)
    }

    @Test
    fun `colour scheme is derived from the palette`() {
        val dark = colorSchemeFor(DarkLinguaPalette)
        assertEquals(DarkLinguaPalette.accent, dark.primary)
        assertEquals(DarkLinguaPalette.onAccent, dark.onPrimary)
        assertEquals(DarkLinguaPalette.paper, dark.background)
        assertEquals(DarkLinguaPalette.ink, dark.onSurface)

        val light = colorSchemeFor(LightLinguaPalette)
        assertEquals(LightLinguaPalette.paper, light.background)
        assertEquals(LightLinguaPalette.cardSurface, light.surface)
    }

    private fun relativeLuminance(color: androidx.compose.ui.graphics.Color): Float {
        fun channel(value: Float): Float =
            if (value <= 0.03928f) value / 12.92f else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
    }
}
