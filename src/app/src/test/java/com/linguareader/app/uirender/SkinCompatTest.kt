package com.linguareader.app.uirender

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.linguareader.app.CardShape
import com.linguareader.app.DarkLinguaPalette
import com.linguareader.app.LightLinguaPalette
import com.linguareader.app.LinguaPalette
import com.linguareader.app.PillShape
import com.linguareader.app.SmallShape
import com.linguareader.app.skin.SkinRegistry
import com.linguareader.app.skin.paletteForSkin
import com.linguareader.shared.ui.skin.SkinBuiltIns
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **兼容层护栏**（Lead 的验收点 1）：旧 API 必须一行不改继续用，且颜色逐值不变。
 *
 * 这些断言写死了「迁移前的那 15 个色值」的 ARGB。它们不依赖 `SkinBuiltIns` 的实现 ——
 * 即使有人改了 shared 里的 hex，这里也会立刻红，因为期望值是历史值本身。
 * 这就是「换肤不破坏现有界面」的护栏。
 */
class SkinCompatTest {

    /** 迁移前 `ThemeColors.kt` 里的日间 15 色，逐字抄录（ARGB）。 */
    private val legacyLight = mapOf(
        "paper" to 0xFFF7F3EA.toInt(),
        "paperDeep" to 0xFFF0E8D9.toInt(),
        "cardSurface" to 0xFFFFFBF4.toInt(),
        "ink" to 0xFF27231F.toInt(),
        "inkSoft" to 0xFF6F665C.toInt(),
        "inkFaint" to 0xFF6C655F.toInt(),
        "accent" to 0xFF8D5535.toInt(),
        "accentDeep" to 0xFF6F4127.toInt(),
        "accentSoft" to 0xFFE7D3BC.toInt(),
        "onAccent" to 0xFFFFFFFF.toInt(),
        "gold" to 0xFFC99B3F.toInt(),
        "goldOnPaper" to 0xFF7A5420.toInt(),
        "success" to 0xFF487050.toInt(),
        "danger" to 0xFFAC483D.toInt(),
        "bookCoverFallback" to 0xFFE1D5C2.toInt()
    )

    /** 迁移前的夜间 15 色。 */
    private val legacyDark = mapOf(
        "paper" to 0xFF171717.toInt(),
        "paperDeep" to 0xFF1F1D1A.toInt(),
        "cardSurface" to 0xFF221F1B.toInt(),
        "ink" to 0xFFE8E3DA.toInt(),
        "inkSoft" to 0xFFB6ADA2.toInt(),
        "inkFaint" to 0xFF8F877B.toInt(),
        "accent" to 0xFFC98A5E.toInt(),
        "accentDeep" to 0xFFE3AE83.toInt(),
        "accentSoft" to 0xFF3A2E25.toInt(),
        "onAccent" to 0xFF231F1B.toInt(),
        "gold" to 0xFFD8B15C.toInt(),
        "goldOnPaper" to 0xFFD8B15C.toInt(),
        "success" to 0xFF7FB08C.toInt(),
        "danger" to 0xFFE0796C.toInt(),
        "bookCoverFallback" to 0xFF3A342C.toInt()
    )

    private fun actual(p: LinguaPalette): Map<String, Int> = mapOf(
        "paper" to p.paper.toArgb(),
        "paperDeep" to p.paperDeep.toArgb(),
        "cardSurface" to p.cardSurface.toArgb(),
        "ink" to p.ink.toArgb(),
        "inkSoft" to p.inkSoft.toArgb(),
        "inkFaint" to p.inkFaint.toArgb(),
        "accent" to p.accent.toArgb(),
        "accentDeep" to p.accentDeep.toArgb(),
        "accentSoft" to p.accentSoft.toArgb(),
        "onAccent" to p.onAccent.toArgb(),
        "gold" to p.gold.toArgb(),
        "goldOnPaper" to p.goldOnPaper.toArgb(),
        "success" to p.success.toArgb(),
        "danger" to p.danger.toArgb(),
        "bookCoverFallback" to p.bookCoverFallback.toArgb()
    )

    private fun assertMatches(legacy: Map<String, Int>, p: LinguaPalette, label: String) {
        val got = actual(p)
        val mismatches = legacy.mapNotNull { (name, expected) ->
            val value = got.getValue(name)
            if (value == expected) null
            else name + ": expected " + ComposeRenderHarness.hexColor(expected) +
                " but was " + ComposeRenderHarness.hexColor(value)
        }
        assertEquals(
            label + " drifted from the pre-migration values:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches
        )
    }

    @Test
    fun lightPaletteMatchesLegacyValuesExactly() =
        assertMatches(legacyLight, LightLinguaPalette, "LightLinguaPalette")

    @Test
    fun darkPaletteMatchesLegacyValuesExactly() =
        assertMatches(legacyDark, DarkLinguaPalette, "DarkLinguaPalette")

    @Test
    fun paletteIsProjectedFromDesignTokensNotDuplicated() {
        // 同一份真值的两条路径必须产出完全相同的 Color（Color 的 equals 是值相等）。
        assertEquals(LightLinguaPalette, paletteForSkin(SkinBuiltIns.light))
        assertEquals(DarkLinguaPalette, paletteForSkin(SkinBuiltIns.dark))
        // 反向验证：调色板每个字段确实等于 token 的对应字段。
        val tokens = SkinRegistry.toDesignTokens(SkinBuiltIns.light)
        val legacy = paletteForSkin(SkinBuiltIns.light)
        assertEquals(tokens.colors.paper, legacy.paper)
        assertEquals(tokens.colors.cardSurface, legacy.cardSurface)
        assertEquals(tokens.colors.accent, legacy.accent)
        assertEquals(tokens.colors.goldOnPaper, legacy.goldOnPaper)
        assertEquals(tokens.isDark, legacy.isDark)
    }

    @Test
    fun lightAndDarkAreNotAccidentallyEqual() {
        assertNotEquals(LightLinguaPalette, DarkLinguaPalette)
    }

    /**
     * 形状常量必须保持迁移前的 dp 值：`CardShape`=14 / `SmallShape`=9 / `PillShape`=50。
     * 40+ 文件按视觉期望依赖它们，改一个数就会整屏不对。
     */
    @Test
    fun legacyShapesKeepTheirPreMigrationGeometry() {
        val density = Density(1f, 1f)
        val size = Size(200f, 200f)
        // 迁移前是 RoundedCornerShape(14.dp) / (9.dp)——绝对值。
        assertEquals(14f, CardShape.topStart.toPx(size, density), 0.001f)
        assertEquals(9f, SmallShape.topStart.toPx(size, density), 0.001f)
        // 迁移前是 RoundedCornerShape(50)，注意这是 **50%** 不是 50dp。
        // 现在用「很大的 dp 值」表达胶囊（999dp），视觉上同样会被夹到短边的一半，
        // 所以这里对的是**实际像素**而不是字面值。
        val legacyPill = RoundedCornerShape(50)
        assertEquals(
            legacyPill.topStart.toPx(size, density),
            PillShape.topStart.toPx(size, density),
            0.001f
        )
        // 非正方形也要一致，否则长条胶囊的圆角会变形。
        val wide = Size(400f, 80f)
        assertEquals(
            legacyPill.topStart.toPx(wide, density),
            PillShape.topStart.toPx(wide, density),
            0.001f
        )
        // 类型也必须是圆角（有人误换成 CutCornerShape 时这里会红）。
        assertTrue(CardShape is RoundedCornerShape)
        assertTrue(SmallShape is RoundedCornerShape)
        assertTrue(PillShape is RoundedCornerShape)
    }

    @Test
    fun legacyPaletteIsDarkFlagsAreCorrect() {
        assertTrue(DarkLinguaPalette.isDark)
        assertTrue(!LightLinguaPalette.isDark)
    }

    @Test
    fun customSkinStillProjectsIntoTheSameType() {
        // P3 换肤后，旧代码拿到的应是新皮肤的颜色（而不是永远返回日间）。
        val amoled = paletteForSkin(SkinBuiltIns.amoled)
        assertEquals(0xFF000000.toInt(), amoled.paper.toArgb())
        assertTrue(amoled.isDark)
        assertNotEquals(amoled, LightLinguaPalette)
    }

    @Test
    fun everySkinProjectsIntoALinguaPalette() {
        // 换肤系统里所有内置皮肤都必须能被旧 API 消费，否则切到某套皮肤时旧屏会花。
        SkinBuiltIns.all.forEach { skin ->
            val p = paletteForSkin(skin)
            assertEquals("skin " + skin.id + " isDark", skin.isDark, p.isDark)
            // 不能用 android.graphics.Color.alpha()：纯 JVM 单测里它没被 mock。
            assertEquals(0xFF, (p.paper.toArgb() ushr 24) and 0xFF)
        }
    }

    @Test
    fun tokenSpacingAndRadiiAreInDp() {
        val tokens = SkinRegistry.toDesignTokens(SkinBuiltIns.light)
        assertEquals(4.dp, tokens.spacing.xs)
        assertEquals(8.dp, tokens.spacing.s)
        assertEquals(16.dp, tokens.spacing.m)
        assertEquals(24.dp, tokens.spacing.l)
        assertEquals(9.dp, tokens.radii.sm)
        assertEquals(14.dp, tokens.radii.md)
        assertEquals(20.dp, tokens.radii.lg)
        // 默认皮肤的卡片形状 = Legacy CardShape（14dp）
        assertEquals(CardShape.topStart.toPx(Size(100f, 100f), Density(1f)), tokens.cardShape.topStart.toPx(Size(100f, 100f), Density(1f)), 0.001f)
    }

    @Test
    fun typographyScalesWithFontSize() {
        // 行高在 :shared 存倍数，到 Compose 必须换算成绝对 sp。
        val tokens = SkinRegistry.toDesignTokens(SkinBuiltIns.light)
        val text = tokens.typography.title
        val size = text.fontSize.value.toDouble()
        val line = text.lineHeight.value.toDouble()
        val ratio = SkinBuiltIns.light.typography.titleLineHeight
        // 两边都转 Double 再比：Float*Double 的结果类型会让 org.junit 的重载解析失败。
        assertEquals(size * ratio, line, 0.01)
    }

    @Test
    fun fontFamiliesMapToComposeFamilies() {
        val tokens = SkinRegistry.toDesignTokens(SkinBuiltIns.light)
        assertEquals(
            androidx.compose.ui.text.font.FontFamily.Serif,
            tokens.typography.title.fontFamily
        )
        assertEquals(
            androidx.compose.ui.text.font.FontFamily.SansSerif,
            tokens.typography.body.fontFamily
        )
    }
}
