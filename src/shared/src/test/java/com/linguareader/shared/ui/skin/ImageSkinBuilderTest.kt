package com.linguareader.shared.ui.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片 → 皮肤。重点覆盖任务点名的三种病态输入：**单色图 / 高饱和图 / 低对比灰阶图**。
 * 每种都断言两件事：不崩（有输出）＋ 输出可用（WCAG 合规）。
 */
class ImageSkinBuilderTest {

    private fun build(pixels: IntArray, w: Int, h: Int, name: String = "测试"): SkinSpec =
        ImageSkinBuilder.fromImage(pixels, w, h, name = name)

    private fun assertUsable(spec: SkinSpec, label: String) {
        val violations = SkinValidator.violations(spec.colors)
        assertTrue(
            label + " must be WCAG compliant, got " + violations.joinToString("; ") { it.kind.toString() + "=" + it.actual },
            violations.isEmpty()
        )
        // 正文一定可读（而不是审计器算错）。
        for (surface in spec.colors.surfaces()) {
            assertTrue(
                label + ": ink must be readable on every surface",
                SkinColorMath.contrastRatio(spec.colors.ink, surface) >= SkinValidator.TEXT_MIN
            )
        }
    }

    // ── ① 单色图 ─────────────────────────────────────────────────────

    @Test
    fun pureWhiteImageYieldsUsableLightSkin() {
        val spec = build(TestImages.solid(64, 64, 0xFFFFFFFF.toInt()), 64, 64, "全白")
        assertFalse("all-white must be a light skin", spec.isDark)
        assertUsable(spec, "all-white")
        assertEquals("全白", spec.name)
        assertEquals(SkinOrigin.IMAGE, spec.origin)
        // 白图没有彩色信息，强调色必须是合成出来的（不能是白/近白，否则按钮看不见）。
        assertTrue(
            "accent must stand out on a white canvas",
            SkinColorMath.contrastRatio(spec.colors.accent, spec.colors.paper) >= 3.0
        )
    }

    @Test
    fun pureBlackImageYieldsUsableDarkSkin() {
        val spec = build(TestImages.solid(64, 64, 0xFF000000.toInt()), 64, 64, "全黑")
        assertTrue("all-black must be a dark skin", spec.isDark)
        assertUsable(spec, "all-black")
        // 纯黑底的纸色保持极暗（不要把 AMOLED 风格推成灰）。
        assertTrue(
            "paper should stay very dark, got " + SkinColorMath.toHex(spec.colors.paper),
            SkinColorMath.relativeLuminance(spec.colors.paper) < 0.06
        )
    }

    @Test
    fun midGrayImageDoesNotCrashAndStaysUsable() {
        // 单色中灰是最坏情况：没有色相、明度卡在灰区。
        val spec = build(TestImages.solid(48, 48, 0xFF808080.toInt()), 48, 48, "中灰")
        assertUsable(spec, "mid-gray")
    }

    // ── ② 高饱和图 ───────────────────────────────────────────────────

    @Test
    fun saturatedRedImageYieldsUsableSkinWithRedAccent() {
        val spec = build(TestImages.solid(64, 64, 0xFFD32F2F.toInt()), 64, 64, "红底")
        assertUsable(spec, "saturated red")
        // 底色被压饱和度（红海报不能当 UI 底直接铺），但仍要保留色相 —— 红图出来的纸
        // 应该偏暖（色相靠红/橙一侧）。
        val paperHsl = SkinColorMath.toHsl(spec.colors.paper)
        assertTrue(
            "paper saturation must be tamed, got " + paperHsl.saturation,
            paperHsl.saturation <= 0.36
        )
        val warmth = SkinColorMath.warmth(paperHsl.hue, paperHsl.saturation)
        assertTrue(
            "red image should give a warm paper, got hue " + paperHsl.hue + " warmth " + warmth,
            warmth >= 0.5 || paperHsl.saturation < 0.05
        )
    }

    @Test
    fun saturatedRedImageGetsRedishAccent() {
        // 一张红底白字的海报：强调色应该能认出是红的（色相 < 30 或 > 330）。
        val pixels = TestImages.synthetic(80, 80) { x, _ ->
            if (x < 12) 0xFFFFFFFF.toInt() else 0xFFC62828.toInt()
        }
        val spec = build(pixels, 80, 80, "红海报")
        val accentHue = SkinColorMath.toHsl(spec.colors.accent).hue
        assertTrue(
            "accent should stay in the red family, got hue " + accentHue,
            accentHue < 40.0 || accentHue > 330.0
        )
        assertUsable(spec, "red poster")
    }

    @Test
    fun neonGreenImageKeepsPaperCalm() {
        val spec = build(TestImages.solid(64, 64, 0xFF00E676.toInt()), 64, 64, "荧光绿")
        assertUsable(spec, "neon green")
        assertTrue(
            "paper saturation must be clamp'd, got " + SkinColorMath.toHsl(spec.colors.paper).saturation,
            SkinColorMath.toHsl(spec.colors.paper).saturation <= 0.36
        )
    }

    // ── ③ 低对比灰阶图 ───────────────────────────────────────────────

    @Test
    fun grayscaleRampStaysUsable() {
        // 从 #202020 到 #A0A0A0 的灰阶渐变：没有色彩倾向，且明暗跨度不足。
        val pixels = TestImages.synthetic(100, 100) { _, y ->
            val v = 0x20 + (y * (0xA0 - 0x20) / 99)
            TestImages.rgb(v, v, v)
        }
        val spec = build(pixels, 100, 100, "灰阶")
        assertUsable(spec, "grayscale ramp")
    }

    @Test
    fun flatLowContrastImageStillGetsDistinctInk() {
        // 几乎同色的浅灰图：提取出来的簇彼此极近，墨色必须被拉开。
        val pixels = TestImages.synthetic(64, 64) { x, _ ->
            if (x < 32) 0xFFBFBFBF.toInt() else 0xFFC4C4C4.toInt()
        }
        val spec = build(pixels, 64, 64, "低对比")
        assertUsable(spec, "low contrast")
        assertTrue(
            "ink must separate from paper",
            SkinColorMath.contrastRatio(spec.colors.ink, spec.colors.paper) >= SkinValidator.TEXT_MIN
        )
    }

    @Test
    fun grayImageGetsNonGrayAccent() {
        // 全灰图不该产出一套「只剩灰」的 UI —— 强调色按暖褐合成。
        val spec = build(TestImages.solid(32, 32, 0xFF9E9E9E.toInt()), 32, 32, "纯灰")
        val accentHsl = SkinColorMath.toHsl(spec.colors.accent)
        assertTrue(
            "gray image must synthesise a chromatic accent, got saturation " + accentHsl.saturation,
            accentHsl.saturation >= 0.2
        )
    }

    // ── 暗色图 / 亮色图的分派 ────────────────────────────────────────

    @Test
    fun darkPhotoYieldsDarkSkin() {
        val pixels = TestImages.twoBands(80, 80, 0xFF101820.toInt(), 0xFF1B2A41.toInt())
        val spec = build(pixels, 80, 80, "暗图")
        assertTrue("dark photo should give a dark skin", spec.isDark)
        assertUsable(spec, "dark photo")
    }

    @Test
    fun brightPhotoYieldsLightSkin() {
        val pixels = TestImages.twoBands(80, 80, 0xFFF5F0E6.toInt(), 0xFFEDE3D0.toInt())
        val spec = build(pixels, 80, 80, "亮图")
        assertFalse("bright photo should give a light skin", spec.isDark)
        assertUsable(spec, "bright photo")
    }

    @Test
    fun posterWithSmallBrightAreaStaysDark() {
        // 深色插画 + 一小块米白留白：背景阵营应按占比定，不能被那块留白拉成亮色。
        val pixels = TestImages.synthetic(100, 100) { x, y ->
            if (x > 90 && y > 90) 0xFFF5F0E6.toInt() else 0xFF14181F.toInt()
        }
        val spec = build(pixels, 100, 100, "深海报")
        assertTrue("a small bright patch must not flip the whole skin", spec.isDark)
    }

    // ── 边界与稳定性 ─────────────────────────────────────────────────

    @Test
    fun emptyImageFallsBackToBuiltIn() {
        // 全透明：没有可用色簇 → 回退内置日间皮肤，而不是抛异常。
        val spec = build(TestImages.solid(16, 16, 0x00000000), 16, 16, "空图")
        assertEquals(SkinOrigin.IMAGE, spec.origin)
        assertEquals("空图", spec.name)
        assertUsable(spec, "empty image")
    }

    @Test
    fun zeroSizedImageFallsBack() {
        val spec = ImageSkinBuilder.fromImage(IntArray(0), 0, 0, name = "零尺寸")
        assertUsable(spec, "zero sized")
    }

    @Test
    fun sameImageYieldsSameId() {
        val pixels = TestImages.twoBands(32, 32, 0xFF223344.toInt(), 0xFFAABBCC.toInt())
        val a = ImageSkinBuilder.fromImage(pixels, 32, 32)
        val b = ImageSkinBuilder.fromImage(pixels, 32, 32)
        assertEquals("same image must give a stable id", a.id, b.id)
        assertTrue("id should be readable", a.id.startsWith("image-"))
    }

    @Test
    fun differentImagesYieldDifferentIds() {
        val a = ImageSkinBuilder.fromImage(TestImages.solid(16, 16, 0xFF112233.toInt()), 16, 16)
        val b = ImageSkinBuilder.fromImage(TestImages.solid(16, 16, 0xFF445566.toInt()), 16, 16)
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun explicitIdAndNameWin() {
        val spec = ImageSkinBuilder.fromImage(
            TestImages.solid(8, 8, 0xFF336699.toInt()), 8, 8,
            name = "我的皮肤", id = "my-skin"
        )
        assertEquals("my-skin", spec.id)
        assertEquals("我的皮肤", spec.name)
    }

    @Test
    fun imageSkinSurvivesJsonRoundTrip() {
        val spec = build(TestImages.solid(32, 32, 0xFF6A4E32.toInt()), 32, 32, "棕")
        val back = SkinSpec.fromJson(spec.toJson())
        assertEquals(spec, back)
    }

    @Test
    fun builtInTypographySpacingRadiiAreInherited() {
        // 图片只决定颜色；排版/间距/圆角/动效沿用基准，避免「选张图字就变了」。
        val spec = build(TestImages.solid(8, 8, 0xFF888888.toInt()), 8, 8)
        assertEquals(SkinTypography.default(), spec.typography)
        assertEquals(SkinSpacing.default(), spec.spacing)
        assertEquals(SkinRadii.default(), spec.radii)
        assertEquals(SkinMotion.default(), spec.motion)
        assertEquals(SkinElevation.default(), spec.elevation)
    }

    // ── 角色分配的可观测性 ───────────────────────────────────────────

    @Test
    fun assignRolesExposesCanvasAndAccent() {
        val clusters = ImagePaletteExtractor.extract(
            TestImages.twoBands(64, 64, 0xFFF2EBDD.toInt(), 0xFFB22222.toInt()),
            64, 64
        )
        val mapping = ImageSkinBuilder.assignRoles(clusters)
        assertFalse(mapping.isDark)
        assertTrue("canvas share should dominate", mapping.canvasCluster.share >= 0.4)
        assertTrue("average luminance in 0..1", mapping.averageLuminance in 0.0..1.0)
        // describe() 是给人看的调试串，不该抛异常也不该为空。
        val text = ImageSkinBuilder.describe(mapping)
        assertTrue(text.contains("isDark="))
        assertTrue(text.contains("canvas="))
    }

    @Test
    fun semanticColorsIgnoreImageHue() {
        // 成功的色相必须固定在绿区、危险固定在红区——否则「删除」可能是绿的。
        val spec = build(TestImages.solid(64, 64, 0xFF2244AAL.toInt()), 64, 64, "蓝图")
        val successHue = SkinColorMath.toHsl(spec.colors.success).hue
        val dangerHue = SkinColorMath.toHsl(spec.colors.danger).hue
        assertTrue("success must stay green-ish, got " + successHue, successHue in 90.0..190.0)
        assertTrue("danger must stay red-ish, got " + dangerHue, dangerHue < 45.0 || dangerHue > 315.0)
    }
}
