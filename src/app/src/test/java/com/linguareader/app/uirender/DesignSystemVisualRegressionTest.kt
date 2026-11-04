package com.linguareader.app.uirender

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linguareader.app.Ink
import com.linguareader.app.InkSoft
import com.linguareader.app.components.EmptyState
import com.linguareader.app.components.LinguaCard
import com.linguareader.app.components.LinguaScaffold
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.components.SettingRow
import com.linguareader.app.skin.DesignTokens
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.skin.SkinRegistry
import com.linguareader.shared.ui.skin.SkinBuiltIns
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **设计系统的视觉回归基线**（P8）。
 *
 * 守的是什么：`skin/` + `components/` 这两层是**全体 12 屏的地基**。
 * 它们被改动时，影响面是「所有屏幕」；但每个屏的渲染测试只守自己那一屏，
 * 组件本身变了反而没人发现。
 *
 * ## 夹具必须还原真实上下文（踩过的坑）
 *
 * 第一版夹具直接用 `Column { EmptyState(...) }`，没包背景 Surface。渲染台的画布是**白底**，
 * 而夜间 token 的文字是**浅色** —— 出图变成「浅灰字浮在白底上，几乎看不见」，
 * 看起来像 `EmptyState` 支持深色有问题。**实际是夹具漏了真实使用时的 `Surface(Paper)` 包裹。**
 *
 * 所以 `snapshot()` 统一在外面套一层 `Surface(color = t.colors.paper)`——
 * 那是真实 App 里的情形（`MainActivity` 就是 `Surface(color = Paper)` 打底）。
 * **基线图是「当前认可的外观」的契约，夹具错了契约就错了。**
 *
 * 第一次跑会建基线（`src/app/src/test/resources/visual-baseline/`），之后每次跑都比对。
 * 更新基线：`$env:UPDATE_VISUAL_BASELINE='1'` 后重跑。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DesignSystemVisualRegressionTest {

    private fun tokensOf(dark: Boolean): DesignTokens =
        SkinRegistry.toDesignTokens(if (dark) SkinBuiltIns.dark else SkinBuiltIns.light)

    /** 渲染并用基线比对；差异超阈值即失败。 */
    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val tokens = tokensOf(dark)
        val bmp = ComposeRenderHarness.renderDp(name, 411, 891) {
            CompositionLocalProvider(LocalDesignTokens provides tokens) {
                // ⚠️ 必须有这一层：真实 App 的根就是 Surface(color = Paper)。
                // 缺了它，夜间 token 的浅色文字会浮在渲染台的白画布上。
                Surface(modifier = Modifier.fillMaxSize(), color = tokens.colors.paper) {
                    content()
                }
            }
        }
        val result = VisualBaseline.compare(name, bmp)
        val summary = VisualBaseline.describe(name, result)
        println(summary)
        assertTrue(summary, result.passed)
    }

    @Test
    fun scaffoldDay() = snapshot("ds-scaffold-day", dark = false) {
        LinguaScaffold(title = "语境阅读") {
            Text("内容区", color = Ink, modifier = Modifier.padding(16.dp))
        }
    }

    @Test
    fun scaffoldNight() = snapshot("ds-scaffold-night", dark = true) {
        LinguaScaffold(title = "语境阅读") {
            Text("内容区", color = Ink, modifier = Modifier.padding(16.dp))
        }
    }

    @Test
    fun cardAndRowsDay() = snapshot("ds-card-rows-day", dark = false) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            LinguaCard {
                Text("傲慢与偏见", color = Ink)
                Text("Jane Austen", color = InkSoft)
            }
            SectionHeader("阅读设置")
            SettingRow(
                title = "字号跟随系统",
                subtitle = "编辑时按系统字号缩放",
                checked = true,
                onCheckedChange = {}
            )
        }
    }

    @Test
    fun cardAndRowsNight() = snapshot("ds-card-rows-night", dark = true) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            LinguaCard {
                Text("傲慢与偏见", color = Ink)
                Text("Jane Austen", color = InkSoft)
            }
            SectionHeader("阅读设置")
            SettingRow(
                title = "字号跟随系统",
                subtitle = "编辑时按系统字号缩放",
                checked = false,
                onCheckedChange = {}
            )
        }
    }

    @Test
    fun emptyStateWithActionDay() = snapshot("ds-empty-day", dark = false) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            EmptyState(
                icon = Icons.Filled.Add,
                title = "书架还是空的",
                body = "导入一本 EPUB / TXT / PDF 就能开始。"
            )
            PrimaryButton(text = "导入图书", onClick = {})
        }
    }

    @Test
    fun emptyStateWithActionNight() = snapshot("ds-empty-night", dark = true) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            EmptyState(
                icon = Icons.Filled.Add,
                title = "书架还是空的",
                body = "导入一本 EPUB / TXT / PDF 就能开始。"
            )
            PrimaryButton(text = "导入图书", onClick = {})
        }
    }
}