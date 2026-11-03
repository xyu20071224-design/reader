package com.linguareader.app.uirender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.linguareader.app.Accent
import com.linguareader.app.AppShapes
import com.linguareader.app.CardShape
import com.linguareader.app.DarkLinguaPalette
import com.linguareader.app.Ink
import com.linguareader.app.LightLinguaPalette
import com.linguareader.app.LinguaPalette
import com.linguareader.app.LocalLinguaPalette
import com.linguareader.app.Paper
import com.linguareader.app.colorSchemeFor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 用**真实的** LinguaPalette + MaterialTheme 渲染一张样板页，验证：
 * 1) 应用自己的主题能在这条纯 JVM 通道里渲染；
 * 2) 主题色确实按预期落到像素上（日后换肤时这条会变成回归护栏）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ThemeRenderProbeTest {

    private fun renderTheme(name: String, palette: LinguaPalette) {
        val bmp = ComposeRenderHarness.render(name, 540, 960) {
            CompositionLocalProvider(LocalLinguaPalette provides palette) {
                MaterialTheme(colorScheme = colorSchemeFor(palette), shapes = AppShapes) {
                    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
                        Column(Modifier.padding(16.dp)) {
                            Text("语境阅读 LinguaReader", color = Ink)
                            Box(
                                Modifier
                                    .padding(top = 12.dp)
                                    .width(240.dp)
                                    .height(72.dp)
                                    .background(Accent, CardShape)
                            )
                        }
                    }
                }
            }
        }
        // 右下角空白处必须是纸色（大面积取样，抗锯齿不影响）
        val corner = bmp.getPixel(bmp.width - 4, bmp.height - 4)
        val expected = palette.paper.toArgb()
        assertEquals(
            "paper background expected, got " + ComposeRenderHarness.hexColor(corner),
            expected,
            corner
        )
    }

    @Test
    fun lightThemeRenders() = renderTheme("theme-light", LightLinguaPalette)

    @Test
    fun darkThemeRenders() = renderTheme("theme-dark", DarkLinguaPalette)
}
