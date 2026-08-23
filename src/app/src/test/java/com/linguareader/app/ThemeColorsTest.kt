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

    @Test
    fun `snackbar neutral tone keeps the inverse ink container`() {
        // 中性提示沿用历史观感：日间墨色容器纸色字、夜间纸色容器墨色字。
        assertEquals(
            LightLinguaPalette.ink to LightLinguaPalette.paper,
            snackbarColorsFor(StatusTone.NEUTRAL, LightLinguaPalette)
        )
        assertEquals(
            DarkLinguaPalette.ink to DarkLinguaPalette.paper,
            snackbarColorsFor(StatusTone.NEUTRAL, DarkLinguaPalette)
        )
    }

    @Test
    fun `snackbar semantic tones use palette success danger with onAccent text`() {
        assertEquals(
            LightLinguaPalette.success to LightLinguaPalette.onAccent,
            snackbarColorsFor(StatusTone.SUCCESS, LightLinguaPalette)
        )
        assertEquals(
            DarkLinguaPalette.danger to DarkLinguaPalette.onAccent,
            snackbarColorsFor(StatusTone.DANGER, DarkLinguaPalette)
        )
    }

    @Test
    fun `every snackbar pairing keeps at least four point five to one contrast`() {
        // 米色纸底上「米色条」缺陷的防回归：所有语义×昼夜组合的容器/文字
        // 对比度必须 ≥4.5:1（WCAG AA 正文级）。
        fun contrast(container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color): Float {
            val lc = relativeLuminance(container)
            val lt = relativeLuminance(content)
            return (maxOf(lc, lt) + 0.05f) / (minOf(lc, lt) + 0.05f)
        }
        listOf(LightLinguaPalette, DarkLinguaPalette).forEach { palette ->
            StatusTone.entries.forEach { tone ->
                val (container, content) = snackbarColorsFor(tone, palette)
                val ratio = contrast(container, content)
                assertTrue(
                    "snackbar ${tone}/${if (palette.isDark) "dark" else "light"} contrast $ratio < 4.5",
                    ratio >= 4.5f
                )
            }
        }
    }

    private fun relativeLuminance(color: androidx.compose.ui.graphics.Color): Float {
        fun channel(value: Float): Float =
            if (value <= 0.03928f) value / 12.92f else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
    }
}
