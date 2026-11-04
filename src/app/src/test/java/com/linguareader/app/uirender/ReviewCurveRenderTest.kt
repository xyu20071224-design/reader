package com.linguareader.app.uirender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.linguareader.app.AppShapes
import com.linguareader.app.LightLinguaPalette
import com.linguareader.app.LocalLinguaPalette
import com.linguareader.app.Paper
import com.linguareader.app.colorSchemeFor
import com.linguareader.app.review.ReviewCurvePicker
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 遗忘曲线选择器的视觉回归（ReviewUi 拆解第三步）。
 *
 * 这一块是**全批唯一改了颜色来源**的地方：旧调色板 `Ink`/`Accent`/`Paper`
 * → `LocalDesignTokens`。日间皮肤下两者应当逐值相等，所以这张图的作用就是
 * 「用眼睛确认颜色确实没变」——像素差 ≈ 0 才算通过。
 *
 * 出图位置：app/build/ui-render-probe/review-curve.png（1233×2673）
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ReviewCurveRenderTest {

    private fun renderCurve(name: String) = ComposeRenderHarness.renderDp(name, 411, 891) {
        CompositionLocalProvider(LocalLinguaPalette provides LightLinguaPalette) {
            MaterialTheme(colorScheme = colorSchemeFor(LightLinguaPalette), shapes = AppShapes) {
                Box(Modifier.fillMaxSize().background(Paper)) {
                    Box(Modifier.fillMaxWidth().padding(24.dp)) {
                        ReviewCurvePicker(retention = 0.85, onRetentionChange = {})
                    }
                }
            }
        }
    }

    @Test
    fun curveRenders() {
        val bmp = renderCurve("review-curve")
        val paper = LightLinguaPalette.paper.toArgb()
        var nonPaper = 0
        for (x in 0 until bmp.width step 3) {
            for (y in 0 until minOf(bmp.height, 900) step 3) {
                if (bmp.getPixel(x, y) != paper) nonPaper++
            }
        }
        assertTrue("曲线必须画出网格与曲线本体（实际 $nonPaper 个非纸色采样点）", nonPaper > 200)
        val out = File("build/ui-render-probe/review-curve.png")
        assertTrue("出图应当落盘：" + out.absolutePath, out.isFile)
    }
}
