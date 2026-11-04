package com.linguareader.shared.ui.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **图片 → 风格的推导**（P20）。
 *
 * ## 背景
 *
 * `SkinSpec` 有六个维度（colors / typography / spacing / radii / motion / elevation），
 * 但 `fromClusters` 原本只用图片推导 `colors`，其余五个全写死 `default()`。
 * 结果是**图片只能换颜色，不能换风格** —— 而目标要的是「快速接受由图片提供的风格」。
 *
 * 五维全推是错的（字体、间距、动效与图片没有可信对应，硬推等于编），
 * 但**圆角与阴影两维有真实对应**：
 * - 圆角 ← 图片的「柔和度」（低饱和 + 明度分布窄 = 柔）；
 * - 阴影 ← 图片的「层次强度」（色簇离散度大 = 前景背景分得开）。
 *
 * ## 这组测试守什么
 *
 * 1. 推导**真的随图片变**（否则等于没接）；
 * 2. 推导**有界**（不能把 UI 推成全方或全圆）；
 * 3. 纯色图的层次弱 → 不用阴影（用描边）；
 * 4. 确定性（同一张图永远同一结果）。
 */
class ImageSkinCharacterTest {

    /** 柔和图：低饱和、明度集中（模拟晨雾 / 柔光书桌）。 */
    private fun softImage(): List<ColorCluster> {
        val palette = longArrayOf(0xFFF2EBE0, 0xFFEDE4D6, 0xFFE8DFD1, 0xFFE3D9CB)
        return palette.map { ColorCluster(color = it, share = 0.25, count = 25) }
    }

    /** 硬图：高对比、明度跨度大（模拟夜景霓虹 / 高对比海报）。 */
    private fun hardImage(): List<ColorCluster> {
        val palette = longArrayOf(0xFF08080C, 0xFFFFFFFF, 0xFFFF0044, 0xFF001A2E)
        return palette.map { ColorCluster(color = it, share = 0.25, count = 25) }
    }

    @Test
    fun softImageYieldsRounderCornersThanHardImage() {
        val soft = ImageSkinBuilder.fromClusters(softImage(), name = "柔", id = "soft")
        val hard = ImageSkinBuilder.fromClusters(hardImage(), name = "硬", id = "hard")

        assertTrue(
            "柔和图应当推出比硬图更大的圆角。柔=${soft.radii.md}，硬=${hard.radii.md}",
            soft.radii.md > hard.radii.md
        )
    }

    @Test
    fun radiiStayWithinBounds() {
        // 极端输入也不能把 UI 推成全方或全胶囊 —— 那不是风格，是坏掉。
        for (px in listOf(softImage(), hardImage())) {
            val spec = ImageSkinBuilder.fromClusters(px, name = "x", id = "x")
            assertTrue("圆角 md 不应小于 4dp，实际 ${spec.radii.md}", spec.radii.md >= 4.0)
            assertTrue("圆角 md 不应大于 18dp，实际 ${spec.radii.md}", spec.radii.md <= 18.0)
            assertTrue("圆角 lg 不应大于 26dp，实际 ${spec.radii.lg}", spec.radii.lg <= 26.0)
        }
    }

    @Test
    fun highContrastImageUsesShadowWhileFlatImageDoesNot() {
        val hard = ImageSkinBuilder.fromClusters(hardImage(), name = "硬", id = "hard")
        assertTrue("层次分明的图应当用阴影加强层次", hard.elevation.useShadow)

        // 纯色图：所有色簇明度相同 → 层次为 0 → 用描边而非阴影。
        val flat = List(4) { ColorCluster(color = 0xFFF0F0F0, share = 0.25, count = 25) }
        val flatSpec = ImageSkinBuilder.fromClusters(flat, name = "平", id = "flat")
        assertFalse(
            "纯色图没有层次，加阴影会显得脏；应当改用描边",
            flatSpec.elevation.useShadow
        )
    }

    @Test
    fun derivationIsDeterministic() {
        val a = ImageSkinBuilder.fromClusters(softImage(), name = "柔", id = "soft")
        val b = ImageSkinBuilder.fromClusters(softImage(), name = "柔", id = "soft")
        assertEquals(a.radii, b.radii)
        assertEquals(a.elevation, b.elevation)
    }

    @Test
    fun emptyInputStillFallsBackToBuiltIn() {
        // 选错一张全透明图不该让设置页崩掉（既有约定）。
        val spec = ImageSkinBuilder.fromClusters(emptyList(), name = "空", id = "empty")
        assertEquals(SkinBuiltIns.light.radii, spec.radii)
        assertEquals(SkinBuiltIns.light.elevation, spec.elevation)
    }

    @Test
    fun typographyAndSpacingAndMotionStayDefault() {
        // 这三维与图片没有可信对应关系，硬推等于编。保持 default 是有意的。
        val spec = ImageSkinBuilder.fromClusters(softImage(), name = "柔", id = "soft")
        assertEquals(SkinTypography.default(), spec.typography)
        assertEquals(SkinSpacing.default(), spec.spacing)
        assertEquals(SkinMotion.default(), spec.motion)
    }
}