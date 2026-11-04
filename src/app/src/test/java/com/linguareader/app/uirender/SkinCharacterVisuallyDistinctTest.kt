package com.linguareader.app.uirender

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linguareader.app.components.LinguaCard
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.skin.SkinRegistry
import com.linguareader.shared.ui.skin.ColorCluster
import com.linguareader.shared.ui.skin.ImageSkinBuilder
import com.linguareader.shared.ui.skin.SkinSpec
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **P20 的推导在像素上是否可见**（P21）。
 *
 * ## 为什么需要这条测试
 *
 * P20 让圆角与阴影从图片推导，单元测试断言了「值确实不同」（柔和图圆角 16.0 vs 硬图 11.1）。
 * 但**「值不同」不等于「画出来不一样」**：
 *
 * - 如果组件用的是 `t.pillShape`（`radii.pill = 999`，永远是胶囊），圆角推导就白做；
 * - 如果组件根本不读 `t.elevation`，阴影推导也是白做；
 * - 如果两者都读了但差 4.9dp 在 411dp 宽的屏幕上肉眼不可辨，那等于没做。
 *
 * 这正是本项目反复出现的「接了一半」形态：算出来了、断言过了、但用户看不到。
 *
 * ## 这条测试怎么判
 *
 * 用同样的组件树，只换皮肤（柔和 vs 硬），**渲染两张图并断言像素不同**。
 * 如果圆角/阴影推导真的走到了像素上，两张图必然不同；
 * 如果组件没读那两个 token，两张图会一模一样 —— 那时这条会红。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SkinCharacterVisuallyDistinctTest {

    /** 柔和图：低饱和、明度集中。 */
    private fun softSkin(): SkinSpec = ImageSkinBuilder.fromClusters(
        longArrayOf(0xFFF2EBE0, 0xFFEDE4D6, 0xFFE8DFD1, 0xFFE3D9CB).map {
            ColorCluster(color = it, share = 0.25, count = 25)
        }, name = "柔", id = "soft"
    )

    /** 硬图：高对比、明度跨度大。 */
    private fun hardSkin(): SkinSpec = ImageSkinBuilder.fromClusters(
        longArrayOf(0xFF08080C, 0xFFFFFFFF, 0xFFFF0044, 0xFF001A2E).map {
            ColorCluster(color = it, share = 0.25, count = 25)
        }, name = "硬", id = "hard"
    )

    /** 一棵会暴露圆角与阴影差异的最小组件树（卡片 + 设置行）。 */
    @Composable
    private fun Probe() {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LinguaCard {
                Text("第一张卡片")
                Text("第二行内容")
            }
            LinguaCard {
                Text("第二张卡片")
            }
        }
    }

    private fun render(skin: SkinSpec, name: String) =
        ComposeRenderHarness.renderDp(name, 411, 400) {
            CompositionLocalProvider(LocalDesignTokens provides SkinRegistry.toDesignTokens(skin)) {
                Probe()
            }
        }

    @Test
    fun softAndHardSkinsRenderVisiblyDifferent() {
        val soft = softSkin()
        val hard = hardSkin()

        // 先确认推导值确实不同（否则后面的像素断言没有意义）。
        assertNotEquals("柔和图与硬图的圆角应当不同", soft.radii.md, hard.radii.md)
        assertNotEquals("柔和图与硬图的阴影策略应当不同", soft.elevation.useShadow, hard.elevation.useShadow)

        val softBmp = render(soft, "character-soft")
        val hardBmp = render(hard, "character-hard")

        // 逐像素统计差异。只看上半部分（卡片区域），避开背景。
        var diff = 0
        var total = 0
        for (x in 0 until minOf(softBmp.width, hardBmp.width) step 2) {
            for (y in 0 until minOf(softBmp.height, hardBmp.height, 900) step 2) {
                total++
                if (softBmp.getPixel(x, y) != hardBmp.getPixel(x, y)) diff++
            }
        }
        val ratio = if (total == 0) 0.0 else diff.toDouble() / total

        // 颜色本来就会不同（纸色不同），所以这里顺带证明了渲染生效；
        // 但**关键**是下面那条：圆角/阴影的差异必须在同一纸色的前提下也能看出来。
        assertTrue(
            "两张图必须不同（颜色差异已足够），实际差异率 $ratio",
            ratio > 0.01
        )
    }

    /**
     * **关键测试**：保持纸色一致，只让圆角与阴影不同，看像素是否可辨。
     *
     * 这条排除「颜色不同导致图不同」的干扰，直接回答：**圆角/阴影的推导可见吗？**
     */
    @Test
    fun radiiAndElevationAloneProduceVisibleDifference() {
        val soft = softSkin()
        val hard = hardSkin()
        // 把硬图的颜色换成柔和图的颜色，只保留它的圆角与阴影。
        val hardWithSoftColors = hard.copy(
            id = "hard-colors-normalised",
            colors = soft.colors
        )

        // 两者颜色完全相同，只有 radii / elevation 不同。
        val a = render(soft, "character-radii-soft")
        val b = render(hardWithSoftColors, "character-radii-hard")

        var diff = 0
        var total = 0
        for (x in 0 until minOf(a.width, b.width) step 2) {
            for (y in 0 until minOf(a.height, b.height, 900) step 2) {
                total++
                if (a.getPixel(x, y) != b.getPixel(x, y)) diff++
            }
        }
        val ratio = if (total == 0) 0.0 else diff.toDouble() / total

        assertTrue(
            "**颜色相同、只有圆角/阴影不同时，渲染结果必须仍然可辨** —— " +
                "否则 P20 的推导在像素上不可见（组件可能没读 radii/elevation）。" +
                "实际差异率 ${(ratio * 100)}%。",
            ratio > 0.002
        )
    }
}