package com.linguareader.app.uirender

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.linguareader.app.components.LinguaCard
import com.linguareader.app.components.LinguaScaffold
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.components.StatusChip
import com.linguareader.app.components.StatusChipTone
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.skin.SkinRegistry
import com.linguareader.shared.ui.skin.ImageSkinBuilder
import com.linguareader.shared.ui.skin.SkinColorMath
import com.linguareader.shared.ui.skin.SkinSpec
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **图片 → SkinSpec → DesignTokens → 渲染成 PNG** 的端到端验收（skin-engineer 版）。
 *
 * 与 Lead 的 `LeadImageSkinProbeTest`（540×960 纯色块探针）并存，本测试补的是它没覆盖的两点：
 * 1. 画布用**真实设备像素** 1233×2673（qualifiers 是 411dp×891dp @ xxhdpi，density 3）；
 *    540×960 只等于 180dp×320dp 的窗口，内容一多就溢出、屏外的东西画不出来；
 * 2. 画的是**真实组件**而不是色块 —— 这样才能一眼看出正文有没有被图片的彩色带跑。
 *
 * 三张输入图覆盖三种极端风格：暖木书房 / 冷调夜空 / 高饱和海报。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ImageSkinRenderTest {

    private fun pixels(w: Int, h: Int, block: (x: Int, y: Int) -> Int): IntArray =
        IntArray(w * h) { i -> block(i % w, i / w) }

    private fun renderSkin(name: String, spec: SkinSpec) {
        val tokens = SkinRegistry.toDesignTokens(spec)
        val bmp = ComposeRenderHarness.render(name, 1233, 2673) {
            CompositionLocalProvider(LocalDesignTokens provides tokens) {
                LinguaScaffold(title = "语境阅读") {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = tokens.spacing.gutter),
                        verticalArrangement = Arrangement.spacedBy(tokens.spacing.m)
                    ) {
                        Spacer(Modifier.height(tokens.spacing.s))
                        LinguaCard {
                            // 标题行与分割线照生产（shelf/ShelfBookCard.kt）手写，
                            // 不用 2026-11-04 审计出的死组件 CardTitleRow / CardDivider。
                            Text(
                                "傲慢与偏见",
                                style = tokens.typography.title,
                                color = tokens.colors.ink
                            )
                            Text(
                                "Jane Austen · 第 12 章",
                                style = tokens.typography.caption,
                                color = tokens.colors.inkSoft
                            )
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(tokens.hairline)
                                    .background(tokens.colors.outline)
                            )
                            Text(
                                "It is a truth universally acknowledged, that a single man in " +
                                    "possession of a good fortune, must be in want of a wife.",
                                style = tokens.typography.body,
                                color = tokens.colors.ink
                            )
                            Spacer(Modifier.height(tokens.spacing.s))
                            Text(
                                "公认的真理是：一个拥有丰厚财产的单身男子，必定想要娶一位妻子。",
                                style = tokens.typography.caption,
                                color = tokens.colors.inkSoft
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.s)) {
                            StatusChip("已同步", StatusChipTone.SUCCESS)
                            StatusChip("待复习 12", StatusChipTone.ACCENT)
                        }
                        PrimaryButton(text = "继续阅读", onClick = {})
                    }
                }
            }
        }

        // 背景像素必须就是 paper —— 证明 LocalDesignTokens 真的被读到了。
        val paperPx = bmp.getPixel(6, bmp.height - 6)
        val expected = (0xFF shl 24) or
            ((tokens.colors.paper.red * 255).toInt() shl 16) or
            ((tokens.colors.paper.green * 255).toInt() shl 8) or
            (tokens.colors.paper.blue * 255).toInt()
        assertTrue(
            name + " 背景应为 paper " + ComposeRenderHarness.hexColor(expected) +
                "，实际 " + ComposeRenderHarness.hexColor(paperPx),
            paperPx == expected
        )
        // 正文对比度底线（渲染之外再核一次，防止 token 接错但没被像素断言抓到）
        val inkOnPaper = SkinColorMath.contrastRatio(spec.colors.ink, spec.colors.paper)
        assertTrue(name + " ink/paper 应 ≥ 4.5:1，实际 " + inkOnPaper, inkOnPaper >= 4.5)
        // 正文必须是中性色（这是质量门禁 2 的渲染侧复核）
        val inkSat = SkinColorMath.toHsl(spec.colors.ink).s
        assertTrue(name + " 正文饱和度应 ≤ 0.20（不能是彩色正文），实际 " + inkSat, inkSat <= 0.20)
    }

    @Test
    fun warmWoodImageRendersUsefulSkin() {
        val px = pixels(120, 120) { x, y ->
            when {
                y < 24 -> 0xFFF3EADF.toInt()   // 米白窗光
                x < 60 -> 0xFF7B5B3A.toInt()   // 木桌
                else -> 0xFF3E6B58.toInt()     // 绿植
            }
        }
        // 名字刻意与 Lead 的 `LeadImageSkinProbeTest`（skin-from-*，540×960）区分开：
        // 两个测试写同名文件会互相覆盖，谁后跑谁说了算。
        renderSkin("skin-render-warm-wood", ImageSkinBuilder.fromImage(px, 120, 120, name = "暖木书房"))
    }

    @Test
    fun coolNightImageRendersUsefulSkin() {
        val px = pixels(120, 120) { _, y ->
            if (y < 60) 0xFF0E1A2B.toInt() else 0xFF9AA7B4.toInt()
        }
        renderSkin("skin-render-cool-night", ImageSkinBuilder.fromImage(px, 120, 120, name = "冷调夜空"))
    }

    @Test
    fun saturatedPosterImageRendersUsefulSkin() {
        val px = pixels(120, 120) { x, _ ->
            when {
                x < 40 -> 0xFFE0218A.toInt()   // 品红
                x < 80 -> 0xFFFFD400.toInt()   // 黄
                else -> 0xFF1A1A1A.toInt()     // 近黑
            }
        }
        renderSkin("skin-render-poster", ImageSkinBuilder.fromImage(px, 120, 120, name = "高饱和海报"))
    }
}