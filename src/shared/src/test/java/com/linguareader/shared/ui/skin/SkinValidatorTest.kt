package com.linguareader.shared.ui.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 审计与自动修正 —— 这是「图片皮肤不会丑到读不了」的**唯一**保证，
 * 所以这里既有「内置皮肤本来就合规」的正向断言，也有「故意造一套违规配色 → 修到达标」
 * 的负向断言。
 */
class SkinValidatorTest {

    // ── 内置皮肤必须天然合规 ─────────────────────────────────────────

    @Test
    fun lightSkinIsCompliant() {
        val violations = SkinValidator.violations(SkinBuiltIns.light.colors)
        assertTrue("light skin must be WCAG-AA compliant, got " + render(violations), violations.isEmpty())
    }

    @Test
    fun darkSkinIsCompliant() {
        val violations = SkinValidator.violations(SkinBuiltIns.dark.colors)
        assertTrue("dark skin must be WCAG-AA compliant, got " + render(violations), violations.isEmpty())
    }

    @Test
    fun amoledSkinIsCompliant() {
        val violations = SkinValidator.violations(SkinBuiltIns.amoled.colors)
        assertTrue("amoled skin must be WCAG-AA compliant, got " + render(violations), violations.isEmpty())
    }

    @Test
    fun conformIsIdempotentOnCompliantSkin() {
        // 已经合规的皮肤不该被「修正」动一个像素——否则换肤时同一套皮肤会漂移。
        assertEquals(SkinBuiltIns.light.colors, SkinValidator.conform(SkinBuiltIns.light.colors))
        assertEquals(SkinBuiltIns.dark.colors, SkinValidator.conform(SkinBuiltIns.dark.colors))
    }

    // ── 关键对比度契约（写死阈值，防止有人「顺手」把颜色调浅） ──────────

    @Test
    fun bodyTextMeetsAaOnEverySurface() {
        for (skin in SkinBuiltIns.all) {
            for (surface in skin.colors.surfaces()) {
                val ratio = SkinColorMath.contrastRatio(skin.colors.ink, surface)
                assertTrue(
                    skin.id + ": ink on " + SkinColorMath.toHex(surface) + " = " + ratio,
                    ratio >= SkinValidator.TEXT_MIN
                )
            }
        }
    }

    @Test
    fun secondaryTextMeetsAaOnEverySurface() {
        for (skin in SkinBuiltIns.all) {
            for (surface in skin.colors.surfaces()) {
                val ratio = SkinColorMath.contrastRatio(skin.colors.inkSoft, surface)
                assertTrue(
                    skin.id + ": inkSoft on " + SkinColorMath.toHex(surface) + " = " + ratio,
                    ratio >= SkinValidator.TEXT_MIN
                )
            }
        }
    }

    @Test
    fun onAccentMeetsAa() {
        for (skin in SkinBuiltIns.all) {
            val ratio = SkinColorMath.contrastRatio(skin.colors.onAccent, skin.colors.accent)
            assertTrue(skin.id + ": onAccent = " + ratio, ratio >= SkinValidator.TEXT_MIN)
        }
    }

    // ── 违规 → 自动修正 ─────────────────────────────────────────────

    /** 一套故意做坏的配色：灰底 + 浅灰字 + 近白强调。 */
    private fun brokenColors(): SkinColors = SkinBuiltIns.light.colors.copy(
        paper = 0xFF8A8A8AL,
        paperDeep = 0xFF7E7E7EL,
        cardSurface = 0xFF909090L,
        ink = 0xFF9A9A9AL,
        inkSoft = 0xFFA5A5A5L,
        inkFaint = 0xFFB0B0B0L,
        accent = 0xFFEDEDEDL,
        onAccent = 0xFFFFFFFFL,
        goldOnPaper = 0xFFD8D8D8L,
        outline = 0xFF8B8B8BL
    )

    @Test
    fun brokenColorsAreActuallyBroken() {
        // 前提断言：如果这一条挂了，说明 brokenColors 已经不坏了，下面的测试就没意义。
        val violations = SkinValidator.violations(brokenColors())
        assertFalse("fixture must be non-compliant", violations.isEmpty())
    }

    @Test
    fun conformFixesBrokenColorsCompletely() {
        val (fixed, remaining) = SkinValidator.conformAndVerify(brokenColors())
        assertTrue("conform must leave no violations, got " + render(remaining), remaining.isEmpty())
        // 顺带确认修完之后正文确实达标（而不是「审计器自己算错所以看起来通过」）。
        assertTrue(SkinColorMath.contrastRatio(fixed.ink, fixed.paper) >= SkinValidator.TEXT_MIN)
    }

    @Test
    fun conformKeepsHueOfBrokenInk() {
        val original = brokenColors()
        val fixed = SkinValidator.conform(original)
        // 墨色是中性灰（无彩），色相无意义；这里断言饱和度仍然接近 0——
        // 也就是修复没有把灰字变成彩字。
        assertTrue(SkinColorMath.toHsl(fixed.ink).saturation < 0.12)
    }

    @Test
    fun conformResolvesGrayDeadlockOnBackground() {
        // 三面全是「黑白都够不到 4.5:1」的灰 —— 只调文字是死路，必须推背景。
        val deadlocked = SkinBuiltIns.light.colors.copy(
            paper = 0xFF767676L,
            paperDeep = 0xFF787878L,
            cardSurface = 0xFF7A7A7AL
        )
        val fixed = SkinValidator.conform(deadlocked)
        for (surface in fixed.surfaces()) {
            val best = SkinColorMath.bestTextContrast(surface)
            assertTrue(
                "surface " + SkinColorMath.toHex(surface) + " still cannot host text (" + best + ")",
                best >= SkinValidator.TEXT_MIN
            )
        }
        assertTrue(SkinValidator.violations(fixed).isEmpty())
    }

    @Test
    fun conformPutsAllSurfacesInSameCamp() {
        // 一个跨阵营的输入（亮纸 + 黑卡）会被统一到同一侧，否则单一 ink 压不住。
        val crossed = SkinBuiltIns.light.colors.copy(
            paper = 0xFFF7F3EAL,
            paperDeep = 0xFF101010L,
            cardSurface = 0xFFFAFAFAL
        )
        val fixed = SkinValidator.conform(crossed)
        val lums = fixed.surfaces().map { SkinColorMath.relativeLuminance(it) }
        val bright = lums.count { it >= 0.5 }
        assertTrue("expected all surfaces on one side, got luminances " + lums, bright == 0 || bright == 3)
    }

    @Test
    fun auditReportsEveryKind() {
        val kinds = SkinValidator.audit(SkinBuiltIns.light.colors).map { it.first }
        // 三类文字 × 3 表面 + 强调 + 成功 + 危险 + 金 = 11 条。
        assertEquals(SkinValidator.Issue.Kind.entries.size, kinds.size)
        assertEquals(SkinValidator.Issue.Kind.entries.toSet(), kinds.toSet())
    }

    private fun render(issues: List<SkinValidator.Issue>): String =
        issues.joinToString("; ") {
            it.kind.name + "=" + String.format(java.util.Locale.ROOT, "%.2f", it.actual) + "/" + it.required
        }
}
