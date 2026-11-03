package com.linguareader.shared.ui.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs


/**
 * 中位切分的护栏。用**合成图**而不是真图片：像素可控，断言才能写成「红色必须占约 50%」
 * 这种硬话，而不是「看起来还行」。
 */
class ImagePaletteExtractorTest {

    @Test
    fun solidWhiteProducesSingleCluster() {
        val clusters = ImagePaletteExtractor.extract(TestImages.solid(64, 64, 0xFFFFFFFF.toInt()), 64, 64)
        assertEquals(1, clusters.size)
        assertEquals(0xFFFFFFFFL, clusters[0].color)
        assertEquals(1.0, clusters[0].share, 1e-9)
    }

    @Test
    fun solidBlackProducesSingleCluster() {
        val clusters = ImagePaletteExtractor.extract(TestImages.solid(32, 32, 0xFF000000.toInt()), 32, 32)
        assertEquals(1, clusters.size)
        assertEquals(0xFF000000L, clusters[0].color)
        assertEquals(0.0, clusters[0].luminance, 1e-9)
    }

    @Test
    fun twoHalvesProduceTwoClustersWithExpectedShares() {
        val pixels = TestImages.synthetic(100, 100) { x, _ ->
            if (x < 50) 0xFFD32F2F.toInt() else 0xFF1565C0.toInt()
        }
        val clusters = ImagePaletteExtractor.extract(pixels, 100, 100)
        assertEquals(2, clusters.size)
        // 占比各半（按面积精确 50%）。
        for (c in clusters) assertEquals(0.5, c.share, 0.02)
        val hexes = clusters.map { SkinColorMath.toHex(it.color) }.toSet()
        assertEquals(setOf("#D32F2F", "#1565C0"), hexes)
    }

    @Test
    fun clustersAreSortedByShareDescending() {
        // 60% 红 + 30% 绿 + 10% 蓝
        val pixels = TestImages.synthetic(100, 100) { x, y ->
            when {
                y < 60 -> 0xFFE53935.toInt()
                y < 90 -> 0xFF43A047.toInt()
                else -> 0xFF1E88E5.toInt()
            }
        }
        val clusters = ImagePaletteExtractor.extract(pixels, 100, 100)
        val shares = clusters.map { it.share }
        assertEquals("must be sorted descending", shares.sortedDescending(), shares)
        assertEquals(0.6, clusters[0].share, 0.02)
        assertEquals(0.3, clusters[1].share, 0.02)
    }

    @Test
    fun respectsMaxColors() {
        // 每行一色，共 20 种。
        val pixels = TestImages.synthetic(20, 20) { _, y -> 0xFF000000.toInt() or (y * 12 shl 16) or (y * 7 shl 8) or y }
        val clusters = ImagePaletteExtractor.extract(pixels, 20, 20, maxColors = 4)
        assertTrue("expected <= 4 clusters, got " + clusters.size, clusters.size <= 4)
    }

    @Test
    fun transparentPixelsAreIgnored() {
        val pixels = TestImages.synthetic(40, 40) { _, _ -> 0x00FF00FF.toInt() }
        val clusters = ImagePaletteExtractor.extract(pixels, 40, 40)
        assertTrue("fully transparent image must yield no clusters", clusters.isEmpty())
    }

    @Test
    fun mostlyTransparentStillFindsOpaquePart() {
        val pixels = TestImages.synthetic(40, 40) { x, _ ->
            if (x < 4) 0xFF336699.toInt() else 0x00000000
        }
        val clusters = ImagePaletteExtractor.extract(pixels, 40, 40)
        assertEquals(1, clusters.size)
        assertEquals("#336699", SkinColorMath.toHex(clusters[0].color))
        assertEquals(1.0, clusters[0].share, 1e-9)
    }

    @Test
    fun mismatchedPixelArrayDoesNotThrow() {
        // 调用方给的数组比 w*h 短 / 长，都只取交集部分，绝不越界。
        val short = IntArray(10) { 0xFF112233.toInt() }
        val fromShort = ImagePaletteExtractor.extract(short, 100, 100)
        assertEquals(1, fromShort.size)

        val long = IntArray(10_000) { 0xFF445566.toInt() }
        val fromLong = ImagePaletteExtractor.extract(long, 10, 10)
        assertEquals(1, fromLong.size)
    }

    @Test
    fun zeroSizeYieldsNothing() {
        assertTrue(ImagePaletteExtractor.extract(IntArray(0), 0, 0).isEmpty())
        assertTrue(ImagePaletteExtractor.extract(IntArray(0), -3, 10).isEmpty())
    }

    @Test
    fun extractionIsDeterministic() {
        // HashMap 迭代序不承诺稳定，所以实现里必须排序；同一张图跑两次结果要逐位相同。
        val pixels = TestImages.synthetic(64, 64) { x, y -> 0xFF000000.toInt() or ((x * 4) shl 16) or ((y * 4) shl 8) or 0x80 }
        val a = ImagePaletteExtractor.extract(pixels, 64, 64)
        val b = ImagePaletteExtractor.extract(pixels, 64, 64)
        assertEquals(a.map { it.color }, b.map { it.color })
        assertEquals(a.map { it.share }, b.map { it.share })
    }

    @Test
    fun clusterDerivedPropertiesAreConsistent() {
        val clusters = ImagePaletteExtractor.extract(TestImages.solid(8, 8, 0xFF8D5535.toInt()), 8, 8)
        val c = clusters.first()
        val hsl = SkinColorMath.toHsl(c.color)
        assertEquals(hsl.hue, c.hue, 1e-9)
        assertEquals(hsl.saturation, c.saturation, 1e-9)
        assertEquals(hsl.lightness, c.lightness, 1e-9)
        assertEquals(SkinColorMath.relativeLuminance(c.color), c.luminance, 1e-12)
        assertTrue(abs(c.warmth - SkinColorMath.warmth(hsl.hue, hsl.saturation)) < 1e-12)
    }
}
