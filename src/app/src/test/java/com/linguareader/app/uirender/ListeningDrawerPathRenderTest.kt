package com.linguareader.app.uirender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.linguareader.app.AiDrawerContent
import com.linguareader.app.AiDrawerTab
import com.linguareader.app.AppShapes
import com.linguareader.app.LightLinguaPalette
import com.linguareader.app.ListeningSettingsBody
import com.linguareader.app.Paper
import com.linguareader.app.colorSchemeFor
import com.linguareader.app.skin.provideLinguaTheme
import com.linguareader.shared.ui.skin.SkinBuiltIns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * **AI 抽屉复用的听书 Tab**：迁移前后的横向留白对比（Lead 要求单独立项验证）。
 *
 * ## 为什么这一条要单独出图
 *
 * `ListeningSettingsBody` 被两处复用，两处的留白账本不一样：
 *
 *  | 路径 | 迁移前 | 迁移后 |
 *  | --- | --- | --- |
 *  | 阅读页弹层（[ListeningSettingsSheet]） | ModalBottomSheet 0 + 内容体 24 = **24** | LinguaSheet 24 + 内容体 0 = **24** |
 *  | AI 抽屉（本文件的重点） | LinguaSheet 24 + 内容体 24 = **48**（双层叠加，是 bug） | LinguaSheet 24 + 内容体 0 = **24** |
 *
 * 也就是说：**弹层路径观感不变，抽屉路径从 48 收到 24**（修掉双层 padding）。
 * 这是本次迁移唯一一处用户可见的布局变化，必须留客观证据 —— 不能只写"我推断更对齐"。
 *
 * 证据就是 `leftmostContentX`：「内容左边第一个非背景像素的 x 坐标」。
 *  - 内容体单独渲染（外壳 0）→ 迁移后必须是 **0dp**（迁移前是 24dp）；
 *  - 内容体 + 外壳 24 → 迁移后必须是 **24dp**（= 抽屉路径的实际观感）；
 *  - 所以抽屉路径迁移前 = 24(外壳) + 24(内容体) = 48，迁移后 = 24(外壳) + 0 = 24。
 * 第三条由前两条推出，且比整图 diff 精准（整图 diff 会把排版梯换档的差异一起算进去）。

 * `AiDrawerContent` 同理必须自带 0 留白，否则外壳 24 之上再叠一层，同样回到 48。
 *
 * ## 夹具必须用 Column，不能用 Box（踩过一次）
 *
 * `AiDrawerContent` 本身不是容器：它把「总开关行 / Tab 行 / 分隔线 / 内容区」
 * **作为多个兄弟节点发射到调用方的 scope**，真机上那个 scope 是 `LinguaSheet` 的
 * `ColumnScope`。夹具若套 `Box`，兄弟节点会全部叠在同一位置 —— 出图看起来像
 * 「标题压在 Tab 行上」的实现 bug，实际是夹具给错了作用域。
 *
 * ## 画布尺寸
 *
 * 一律 [ComposeRenderHarness.renderDp]（xxhdpi 下 411×891dp = 1233×2673px）。
 *
 * 出图位置：app/build/ui-render-probe/listening-drawer-tab*.png
 *
 * **不是真机替代品**：抽屉的滑动关闭、Tab 切换动画、内层滚动都测不到。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ListeningDrawerPathRenderTest {

    private val density: Float
        get() = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .resources.displayMetrics.density

    /** 内容左边第一个非纸色像素的 x（像素）。返回 -1 表示整列都是纸色。 */
    private fun leftmostContentX(bmp: android.graphics.Bitmap): Int {
        val paper = LightLinguaPalette.paper.toArgb()
        for (x in 0 until bmp.width) {
            for (y in 0 until bmp.height step 2) {
                if (bmp.getPixel(x, y) != paper) return x
            }
        }
        return -1
    }

    /**
     * 抽屉里那个 Tab 的内容体（**外壳留白 = 0**，复刻迁移前的抽屉外层）。
     *
     * 只渲染 `ListeningSettingsBody` 本身，不带任何容器 padding —— 迁移前它自己
     * 写死 24dp，迁移后为 0，两者的差值就是这个测试要量出来的东西。
     */
    private fun renderDrawerInner(name: String) =
        ComposeRenderHarness.renderDp(name, 411, 891) {
            provideLinguaTheme(SkinBuiltIns.light) {
                MaterialTheme(
                    colorScheme = colorSchemeFor(LightLinguaPalette),
                    shapes = AppShapes
                ) {
                    Box(Modifier.fillMaxSize().background(Paper)) {
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            ListeningSettingsBody(onDismiss = null, onSaved = {})
                        }
                    }
                }
            }
        }

    /**
     * 抽屉里的听书 Tab：内容体不再自带横向留白。
     *
     * 断言写的是**迁移后的事实**：内容从 x≈0 起排（真机上由 `LinguaSheet` 给 24dp）。
     * 迁移前这一值是 24dp × density。**这个数字变化是本屏唯一可见的布局变化，
     * 已写进提交信息的「可见变化」一节**（Lead 要求）。
     */
    @Test
    fun drawerTabBodyHasNoOwnHorizontalPadding() {
        val bmp = renderDrawerInner("listening-drawer-tab")
        val left = leftmostContentX(bmp)
        assertEquals(
            "内容体不得自带横向留白（留白归外壳）；实际左边距 " + (left / density) + "dp",
            0,
            (left / density).toInt()
        )
        assertTrue("出图应当落盘", File("build/ui-render-probe/listening-drawer-tab.png").isFile)
    }

    /**
     * 抽屉里补上外壳留白（`LinguaSheet` 的 spacing.l = 24dp）后，内容起点回到 24dp。
     *
     * 与上一条合起来构成完整证据：**内容体 0 + 外壳 24 = 净 24**。
     * 弹层路径迁移前是 24 + 24 = 48（双层叠加），迁移后同样是 24 —— 两条路径口径统一。
     */
    @Test
    fun withSheetHostPaddingContentStartsAt24dp() {
        val bmp = ComposeRenderHarness.renderDp("listening-drawer-tab-hosted", 411, 891) {
            provideLinguaTheme(SkinBuiltIns.light) {
                MaterialTheme(
                    colorScheme = colorSchemeFor(LightLinguaPalette),
                    shapes = AppShapes
                ) {
                    Box(Modifier.fillMaxSize().background(Paper)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(top = 8.dp)
                        ) {
                            ListeningSettingsBody(onDismiss = null, onSaved = {})
                        }
                    }
                }
            }
        }
        val leftDp = (leftmostContentX(bmp) / density).toInt()
        assertTrue("外壳补 24dp 后内容起点应为 24dp，实际 " + leftDp + "dp", leftDp in 23..25)
    }

    /**
     * 整条 AI 抽屉（含总开关行、Tab 行、分隔线）也必须只靠外壳给留白。
     *
     * 这条守的是「抽屉外层没有 padding」这个事实：如果哪天有人为了"看起来更居中"
     * 给 `AiDrawerContent` 自己加了 padding，这里会立刻变成 24dp 而失败。
     */
    @Test
    fun fullDrawerContentStartsAtZeroPadding() {
        val bmp = ComposeRenderHarness.renderDp("listening-drawer-full", 411, 891) {
            provideLinguaTheme(SkinBuiltIns.light) {
                MaterialTheme(
                    colorScheme = colorSchemeFor(LightLinguaPalette),
                    shapes = AppShapes
                ) {
                    Box(Modifier.fillMaxSize().background(Paper)) {
                        // 真机 scope 是 LinguaSheet 的 ColumnScope，夹具用 Column 复刻。
                        Column(Modifier.fillMaxWidth()) {
                            AiDrawerContent(
                                tab = AiDrawerTab.TTS,
                                powerEnabled = true,
                                onTabChange = {},
                                onPowerChange = {},
                                books = emptyList(),
                                aiSettings = com.linguareader.app.ai.AiSettings(),
                                onAiSettingsChange = {},
                                onLoadGlossary = {
                                    com.linguareader.shared.ai.BookGlossary(bookId = it)
                                },
                                onAddGlossary = { bookId, _, _ ->
                                    com.linguareader.shared.ai.BookGlossary(bookId = bookId)
                                },
                                onUpdateGlossary = { bookId, _ ->
                                    com.linguareader.shared.ai.BookGlossary(bookId = bookId)
                                },
                                onRemoveGlossary = { bookId, _ ->
                                    com.linguareader.shared.ai.BookGlossary(bookId = bookId)
                                }
                            )
                        }
                    }
                }
            }
        }
        assertEquals(
            "AiDrawerContent 不得自带横向留白（留白归 LinguaSheet）；实际 " +
                (leftmostContentX(bmp) / density) + "dp",
            0,
            (leftmostContentX(bmp) / density).toInt()
        )
    }
}
