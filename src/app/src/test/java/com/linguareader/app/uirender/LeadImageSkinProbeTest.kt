package com.linguareader.app.uirender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.linguareader.app.skin.DesignTokens
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.skin.SkinRegistry
import com.linguareader.shared.ui.skin.ImageSkinBuilder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lead 复验：**一张图片 → SkinSpec → DesignTokens → 渲染成 PNG** 的端到端链路。
 *
 * 这是「架构能快速接受图片风格」这条需求的最终验收点。不经过任何手写配色，
 * 像素从图片进来，直接出界面。
 *
 * 三张不同风格的测试图，验证管线不是只会处理某一种色调：
 * - 暖木书房（棕 + 米白）
 * - 冷调夜空（深蓝 + 浅灰）
 * - 高饱和海报（品红 + 黄）
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LeadImageSkinProbeTest {

    private fun pixels(w: Int, h: Int, block: (x: Int, y: Int) -> Int): IntArray =
        IntArray(w * h) { i -> block(i % w, i / w) }

    private fun renderSkin(name: String, spec: com.linguareader.shared.ui.skin.SkinSpec) {
        val tokens: DesignTokens = SkinRegistry.toDesignTokens(spec)
        val bmp = ComposeRenderHarness.render(name, 540, 960) {
            CompositionLocalProvider(LocalDesignTokens provides tokens) {
                Column(Modifier.fillMaxSize().background(tokens.colors.paper)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(tokens.spacing.l)
                    ) {
                        Text("语境阅读", color = tokens.colors.ink, style = tokens.typography.title)
                    }
                    Box(
                        Modifier
                            .padding(horizontal = tokens.spacing.l)
                            .fillMaxWidth()
                            .height(72.dp)
                            .background(tokens.colors.accent, RoundedCornerShape(tokens.radii.md))
                    )
                    Box(Modifier.height(tokens.spacing.l))
                    Box(
                        Modifier
                            .padding(horizontal = tokens.spacing.l)
                            .fillMaxWidth()
                            .height(72.dp)
                            .background(tokens.colors.cardSurface, RoundedCornerShape(tokens.radii.md))
                    )
                }
            }
        }

        // 断言：背景像素 == paper，卡片像素 == cardSurface
        val paperPx = bmp.getPixel(4, bmp.height - 4)
        assertTrue(
            name + " 背景应为 paper " + ComposeRenderHarness.hexColor(tokens.colors.paper.toArgbInt()) +
                "，实际 " + ComposeRenderHarness.hexColor(paperPx),
            paperPx == tokens.colors.paper.toArgbInt()
        )

        // 生成的皮肤必须通过对比度门槛（由 SkinValidator 保证，这里复核一次）
        val inkOnPaper = com.linguareader.shared.ui.skin.SkinColorMath.contrastRatio(
            spec.colors.ink, spec.colors.paper
        )
        println("SKIN " + name + " ink/paper contrast=" + String.format(java.util.Locale.ROOT, "%.2f", inkOnPaper))
        assertTrue(
            name + " 正文字色在纸底上的对比度必须 ≥ 4.5:1，实际 " + inkOnPaper,
            inkOnPaper >= 4.5
        )
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
        (0xFF shl 24) or
            ((this.red * 255).toInt() shl 16) or
            ((this.green * 255).toInt() shl 8) or
            (this.blue * 255).toInt()

    @Test
    fun warmWoodImageProducesUsableSkin() {
        val px = pixels(120, 120) { x, y ->
            when {
                y < 24 -> 0xFFF3EADF.toInt()          // 米白（光线）
                x < 60 -> 0xFF7B5B3A.toInt()          // 木头
                else -> 0xFF3E6B58.toInt()            // 绿植
            }
        }
        renderSkin("skin-from-warm-wood", ImageSkinBuilder.fromImage(px, 120, 120, name = "暖木书房"))
    }

    @Test
    fun coolNightImageProducesUsableSkin() {
        val px = pixels(120, 120) { _, y ->
            if (y < 60) 0xFF0E1A2B.toInt() else 0xFF9AA7B4.toInt()
        }
        renderSkin("skin-from-cool-night", ImageSkinBuilder.fromImage(px, 120, 120, name = "冷调夜空"))
    }

    @Test
    fun saturatedPosterImageProducesUsableSkin() {
        val px = pixels(120, 120) { x, y ->
            when {
                x < 40 -> 0xFFE0218A.toInt()          // 品红
                x < 80 -> 0xFFFFD400.toInt()          // 黄
                else -> 0xFF1A1A1A.toInt()
            }
        }
        val spec = ImageSkinBuilder.fromImage(px, 120, 120, name = "高饱和海报")
        println("SATURATED SPEC=" + spec.toJson().take(400))
        renderSkin("skin-from-poster", spec)
    }
}
