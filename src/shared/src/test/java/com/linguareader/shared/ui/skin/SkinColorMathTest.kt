package com.linguareader.shared.ui.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 颜色算术的护栏。这些数字是 WCAG 定义算出来的，不是目测——所以断言也按公式给，
 * 改实现时若对不上就是实现错了。
 */
class SkinColorMathTest {

    @Test
    fun hexRoundTripKeepsOpaqueColor() {
        val c = SkinColorMath.argb(0x8D, 0x55, 0x35)
        assertEquals("#8D5535", SkinColorMath.toHex(c))
        assertEquals(c, SkinColorMath.parseHex("#8D5535"))
        assertEquals(c, SkinColorMath.parseHex("8d5535"))
    }

    @Test
    fun hexRoundTripKeepsAlpha() {
        val c = SkinColorMath.argb(0x12, 0x34, 0x56, 0x78)
        assertEquals("#78123456", SkinColorMath.toHex(c))
        assertEquals(c, SkinColorMath.parseHex("#78123456"))
    }

    @Test
    fun shortHexExpands() {
        assertEquals(0xFFFFFFFFL, SkinColorMath.parseHex("#FFF"))
        assertEquals(0xFF000000L, SkinColorMath.parseHex("#000"))
        assertEquals(0xFFFF0000L, SkinColorMath.parseHex("#F00"))
    }

    @Test
    fun invalidHexReturnsNull() {
        assertNull(SkinColorMath.parseHex(null))
        assertNull(SkinColorMath.parseHex(""))
        assertNull(SkinColorMath.parseHex("#GGGGGG"))
        assertNull(SkinColorMath.parseHex("#12345"))
        assertNull(SkinColorMath.parseHex("not a color"))
    }

    @Test
    fun relativeLuminanceOfExtremes() {
        assertEquals(0.0, SkinColorMath.relativeLuminance(SkinColorMath.BLACK), 1e-9)
        assertEquals(1.0, SkinColorMath.relativeLuminance(SkinColorMath.WHITE), 1e-9)
    }

    @Test
    fun contrastRatioKnownValues() {
        // 黑白是定义上的 21:1。
        assertEquals(21.0, SkinColorMath.contrastRatio(SkinColorMath.BLACK, SkinColorMath.WHITE), 1e-6)
        // 同色 1:1。
        assertEquals(1.0, SkinColorMath.contrastRatio(0xFF8D5535L, 0xFF8D5535L), 1e-9)
        // 与 ThemeColors.kt 注释里引用的口径对齐：日间 ink / paper = 14.08:1。
        assertEquals(
            14.08,
            SkinColorMath.contrastRatio(0xFF27231FL, 0xFFF7F3EAL),
            0.01
        )
    }

    @Test
    fun contrastIsSymmetric() {
        val a = 0xFF8D5535L
        val b = 0xFFF7F3EAL
        assertEquals(
            SkinColorMath.contrastRatio(a, b),
            SkinColorMath.contrastRatio(b, a),
            1e-12
        )
    }

    @Test
    fun hslRoundTrip() {
        val original = 0xFF8D5535L
        val hsl = SkinColorMath.toHsl(original)
        val back = SkinColorMath.fromHsl(hsl.hue, hsl.saturation, hsl.lightness)
        // 8 bit 往返允许 ±1 的量化误差。
        assertEquals(SkinColorMath.red(original).toDouble(), SkinColorMath.red(back).toDouble(), 1.0)
        assertEquals(SkinColorMath.green(original).toDouble(), SkinColorMath.green(back).toDouble(), 1.0)
        assertEquals(SkinColorMath.blue(original).toDouble(), SkinColorMath.blue(back).toDouble(), 1.0)
    }

    @Test
    fun hslOfGrayHasZeroSaturation() {
        val hsl = SkinColorMath.toHsl(0xFF808080L)
        assertEquals(0.0, hsl.saturation, 1e-9)
        assertEquals(128.0 / 255.0, hsl.lightness, 1e-6)
    }

    @Test
    fun ensureContrastLiftsToTarget() {
        // 浅灰字放在白纸上只有约 1.6:1，必须被压暗到达标。
        val paper = 0xFFFFFFFFL
        val faint = 0xFFAAAAAAL
        assertTrue(SkinColorMath.contrastRatio(faint, paper) < 4.5)
        val fixed = SkinColorMath.ensureContrast(faint, paper, 4.5)
        assertTrue(
            "expected >= 4.5:1, got " + SkinColorMath.contrastRatio(fixed, paper),
            SkinColorMath.contrastRatio(fixed, paper) >= 4.5
        )
    }

    @Test
    fun ensureContrastKeepsHue() {
        val orange = 0xFFCC7722L
        val fixed = SkinColorMath.ensureContrast(orange, 0xFFFFFFFFL, 4.5)
        val hueBefore = SkinColorMath.toHsl(orange).hue
        val hueAfter = SkinColorMath.toHsl(fixed).hue
        // 色相允许 ±2°（8bit 量化 + 明度极值时色相本身不稳定）。
        assertEquals(hueBefore, hueAfter, 2.0)
    }

    @Test
    fun ensureContrastIsIdempotentWhenAlreadyCompliant() {
        val ink = 0xFF27231FL
        val paper = 0xFFF7F3EAL
        assertEquals(ink, SkinColorMath.ensureContrast(ink, paper, 4.5))
    }

    @Test
    fun ensureSurfaceSupportsBreaksGrayDeadlock() {
        // #8C8C8C 是经典死结：黑字 6.1:1、白字 3.4:1 —— 等等，实测黑字约 6.1 达标。
        // 真正两边都不够的是 #767676 附近（黑 5.7、白 4.0）；取 #8A8A8A 验证 4.5 门槛下
        // 白字不够、需把底推暗。
        val gray = 0xFF8A8A8AL
        val whiteContrast = SkinColorMath.contrastRatio(SkinColorMath.WHITE, gray)
        val blackContrast = SkinColorMath.contrastRatio(SkinColorMath.BLACK, gray)
        assertTrue("precondition: neither side is comfortable", whiteContrast < 4.5 || blackContrast < 4.5)
        val fixed = SkinColorMath.ensureSurfaceSupports(gray, 4.5)
        val best = SkinColorMath.bestTextContrast(fixed)
        assertTrue("expected >= 4.5, got " + best, best >= 4.5)
    }

    @Test
    fun ensureSurfaceSupportsLeavesGoodSurfaceAlone() {
        val paper = 0xFFF7F3EAL
        assertEquals(paper, SkinColorMath.ensureSurfaceSupports(paper, 4.5))
    }

    @Test
    fun blendOverOpaqueTopWins() {
        assertEquals(
            0xFF112233L,
            SkinColorMath.blendOver(0xFF112233L, 0xFFFFFFFFL)
        )
    }

    @Test
    fun blendOverHalfBlackOnWhiteIsMidGray() {
        val result = SkinColorMath.blendOver(0x80000000L, 0xFFFFFFFFL)
        val r = SkinColorMath.red(result)
        assertTrue("expected mid gray, got " + r, r in 120..136)
    }

    @Test
    fun mixEndpoints() {
        assertEquals(0xFF000000L, SkinColorMath.mix(SkinColorMath.BLACK, SkinColorMath.WHITE, 0.0))
        assertEquals(0xFFFFFFFFL, SkinColorMath.mix(SkinColorMath.BLACK, SkinColorMath.WHITE, 1.0))
    }

    @Test
    fun withAlphaClamps() {
        assertEquals(0x00FF0000L, SkinColorMath.withAlpha(0xFFFF0000L, 0))
        assertEquals(0xFFFF0000L, SkinColorMath.withAlpha(0xFFFF0000L, 255))
        assertEquals(0xFFFF0000L, SkinColorMath.withAlpha(0xFFFF0000L, 999))
    }

    @Test
    fun warmthOfOrangeIsHighAndBlueIsLow() {
        val orange = SkinColorMath.warmth(25.0, 0.9)
        val blue = SkinColorMath.warmth(220.0, 0.9)
        assertTrue("orange should be warm, got " + orange, orange > 0.8)
        assertTrue("blue should be cool, got " + blue, blue < 0.2)
        assertEquals("gray is neither warm nor cool", 0.5, SkinColorMath.warmth(0.0, 0.0), 1e-9)
    }
}
