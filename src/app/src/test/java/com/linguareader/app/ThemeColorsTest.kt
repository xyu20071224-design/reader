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
        assertTrue(chromeIsDark(ReaderTheme.AMOLED, systemDark = false))
        assertFalse(chromeIsDark(ReaderTheme.PAPER, systemDark = true))
        assertFalse(chromeIsDark(ReaderTheme.WHITE, systemDark = true))
        assertFalse(chromeIsDark(ReaderTheme.SEPIA, systemDark = true))
        assertFalse(chromeIsDark(ReaderTheme.GREEN, systemDark = true))
        assertFalse(chromeIsDark(ReaderTheme.MORANDI, systemDark = true))
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

    /**
     * 第四轮审查 5-2/5-3/5-9 的护栏：**当正文/标签用的语义色**在三种表面上都要 ≥4.5:1。
     *
     * 报告实测：浅色 `inkFaint` 2.48–2.93:1、`gold` 2.09:1、`success` 4.47:1，深色
     * `inkFaint` 在 cardSurface 上 4.45:1 —— 断言先落地，改值才有护栏（§9 P0 第 4 项
     * 原话：「建议先加断言再改值」）。
     *
     * `gold` **不在此列**：报告只把它当"统计数字/徽章文字"的点状问题，且 gold 是品牌色；
     * 是否把它降为纯图形色或补一个 on-paper 变体属视觉决策，留给人工（见待确认项）。
     */
    @Test
    fun `text palette colors keep at least four point five to one on every surface`() {
        val failures = mutableListOf<String>()
        listOf(LightLinguaPalette, DarkLinguaPalette).forEach { palette ->
            val surfaces = mapOf(
                "paper" to palette.paper,
                "paperDeep" to palette.paperDeep,
                "cardSurface" to palette.cardSurface
            )
            val textColors = mapOf(
                "ink" to palette.ink,
                "inkSoft" to palette.inkSoft,
                "inkFaint" to palette.inkFaint,
                "accent" to palette.accent,
                "accentDeep" to palette.accentDeep,
                "success" to palette.success,
                "danger" to palette.danger
            )
            textColors.forEach { (name, color) ->
                surfaces.forEach { (surfaceName, surface) ->
                    val ratio = contrastRatio(color, surface)
                    if (ratio < 4.5f) {
                        failures += "${if (palette.isDark) "dark" else "light"}.$name on $surfaceName = ${"%.2f".format(ratio)}"
                    }
                }
            }
        }
        assertTrue(
            "以下语义色未达 4.5:1，需调值或改用途：\n  " + failures.joinToString("\n  "),
            failures.isEmpty()
        )
    }

    private fun contrastRatio(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun relativeLuminance(color: androidx.compose.ui.graphics.Color): Float {
        fun channel(value: Float): Float =
            if (value <= 0.03928f) value / 12.92f else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
    }
}
