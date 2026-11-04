package com.linguareader.app.uirender

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linguareader.app.skin.DesignTokens
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.skin.SkinRegistry
import com.linguareader.app.skin.SkinPickerSection
import com.linguareader.shared.ui.skin.ImageSkinBuilder
import com.linguareader.shared.ui.skin.SkinBuiltIns
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **皮肤选择区的视觉基线**（P9）。
 *
 * 这一块是「图片换肤」能力的**用户入口**。在本次改动之前，`ImageSkinBuilder` 在生产代码里
 * 零调用点 —— 能力建好了但用户摸不到。现在它有入口、有持久化、有测试。
 *
 * 两张基线：
 * 1. 没有自定义皮肤时（占位色块 + 引导文案）；
 * 2. 有自定义皮肤时（真实配色 + 「移除」按钮）。
 *
 * 第二种是关键：它证明「图片 → 皮肤 → UI 配色」这条链**真的通到底**，
 * 而不只是算法算出了一个对象。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SkinPickerRenderTest {

    private fun tokensOf(dark: Boolean): DesignTokens =
        SkinRegistry.toDesignTokens(if (dark) SkinBuiltIns.dark else SkinBuiltIns.light)

    /** 与 `ImageSkinQualityGateTest` 同源的暖木色图：大片暖棕纸 + 深色墨点。 */
    private fun warmWoodSkin() = run {
        val size = 64
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val deep = x in 20..40 && y in 20..40
                pixels[y * size + x] = if (deep) 0xFF2B2118.toInt() else 0xFFE8D9C3.toInt()
            }
        }
        ImageSkinBuilder.fromImage(pixels, size, size, name = "书桌照")
    }

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val tokens = tokensOf(dark)
        val bmp = ComposeRenderHarness.renderDp(name, 411, 891) {
            CompositionLocalProvider(LocalDesignTokens provides tokens) {
                Surface(color = tokens.colors.paper, modifier = Modifier.padding(24.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.width(363.dp)) { content() }
                }
            }
        }
        val result = VisualBaseline.compare(name, bmp)
        val summary = VisualBaseline.describe(name, result)
        println(summary)
        assertTrue(summary, result.passed)
    }

    @Test
    fun pickerWithoutCustomSkin() = snapshot("skin-picker-empty", dark = false) {
        SkinPickerSection(
            selectedId = SkinBuiltIns.light.id,
            customSkin = null,
            onSelectBuiltIn = {},
            onPickImage = {},
            onRemoveCustom = {}
        )
    }

    @Test
    fun pickerWithCustomSkin() = snapshot("skin-picker-custom", dark = false) {
        val custom = warmWoodSkin()
        SkinPickerSection(
            selectedId = custom.id,
            customSkin = custom,
            onSelectBuiltIn = {},
            onPickImage = {},
            onRemoveCustom = {}
        )
    }
}