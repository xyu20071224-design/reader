package com.linguareader.app.uirender

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.linguareader.app.AppShapes
import com.linguareader.app.DarkLinguaPalette
import com.linguareader.app.LightLinguaPalette
import com.linguareader.app.LinguaPalette
import com.linguareader.app.LocalLinguaPalette
import com.linguareader.app.Paper
import com.linguareader.app.colorSchemeFor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **迁移前基准图**（P3 的 before 基线）。
 *
 * 为什么不能只靠组件级出图：组件图证明"组件长什么样"，证明不了"这一屏整体长什么样"。
 * 逐屏迁移前必须先留一张整屏快照，否则迁完之后没有可对照的东西，
 * "布局没塌"这句话就只能靠回忆。
 *
 * 本文件只做**基准记录**，不做断言——断言在迁移后用像素对比补。
 * 出图：build/ui-render-probe/before-<屏名>-<light|dark>.png
 *
 * 注意：真实的 BookshelfScreen 有 54 个参数且依赖 ViewModel，直接构造代价很高；
 * 这里先用「外壳 + 典型内容」组合出等价于空书架屏的基准，等 P3 迁移后再换成真屏幕。
 * 这个取舍写在注释里，避免后来人误以为这是真屏幕的快照。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RealScreenBaselineTest {

    private fun renderShell(name: String, palette: LinguaPalette) {
        ComposeRenderHarness.renderDp(name) {
            CompositionLocalProvider(LocalLinguaPalette provides palette) {
                MaterialTheme(colorScheme = colorSchemeFor(palette), shapes = AppShapes) {
                    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
                        Box(Modifier.fillMaxSize()) {
                            Text(
                                text = "基准：外壳 + 纸底（P3 迁移前）",
                                color = Color(0xFF000000).copy(alpha = 0.35f),
                                fontSize = 14.sp,
                                modifier = Modifier
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun beforeBookshelfLight() = renderShell("before-bookshelf-light", LightLinguaPalette)

    @Test
    fun beforeBookshelfDark() = renderShell("before-bookshelf-dark", DarkLinguaPalette)
}
