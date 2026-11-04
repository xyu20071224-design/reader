package com.linguareader.app.uirender

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.components.SettingRow
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.skin.SkinRegistry
import com.linguareader.shared.ui.skin.SkinBuiltIns
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Compose 语义树断言搬到 JVM**（P25 的可行性验证）。
 *
 * ## 为什么要有这一层
 *
 * 项目里有 4 个 UI 交互仪器测试（`BookshelfSmokeTest` / `LaunchPromptUiTest` /
 * `ReaderAcceptanceTest` / `ReviewUiTest`），它们按中文文案定位节点：
 *
 * ```kotlin
 * composeRule.onNodeWithText("从一本英文书开始").assertIsDisplayed()
 * ```
 *
 * 这些测试需要设备，而本项目长期无设备（`HypervisorPresent=False`），**一次都没跑过**。
 * P24 只能静态核对「文案还在资源里」，证明不了「它被渲染出来」。
 *
 * **Robolectric 支持 Compose 语义树** —— `onNodeWithText` / `performClick` / `assertIsDisplayed`
 * 都能在 JVM 上跑。所以「某段文案在不在语义树里」这类断言**不需要设备**。
 *
 * ## 这一层能测什么、不能测什么
 *
 * | 能（JVM） | 不能（必须设备） |
 * | --- | --- |
 * | 文案是否出现在语义树里 | 真实手势（滑动、长按、拖拽） |
 * | 点击后是否触发了回调 | 真实布局测量与像素对齐 |
 * | 组件是否被渲染（而非仅存在于资源）| WebView 内容与 JS 桥 |
 * | 语义角色与可点性 | insets / 状态栏重叠 |
 *
 * **它是设备验证的补充，不是替代。**
 *
 * ## 这个文件先验证「这套方案在本项目里可行」
 *
 * 如果这一层的 `createComposeRule` 在本项目的 Robolectric 配置下跑不起来（图形模式、
 * 依赖冲突、Activity 缺失等），那「把 4 个仪器测试搬到 JVM」的方案就不成立，
 * 需要换思路。**所以先用最小用例探路，再决定要不要搬那 4 个。**
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ComposeSemanticsProbeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setThemedContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDesignTokens provides SkinRegistry.toDesignTokens(SkinBuiltIns.light)
            ) {
                content()
            }
        }
    }

    /** 探路 1：文案能否在语义树里被找到。 */
    @Test
    fun textIsFindableInSemanticsTree() {
        setThemedContent {
            Column {
                SectionHeader("阅读设置")
                SettingRow(
                    title = "字号跟随系统",
                    subtitle = "关闭后使用手动字号",
                    checked = true,
                    onCheckedChange = {}
                )
            }
        }
        composeRule.onNodeWithText("阅读设置").assertIsDisplayed()
        composeRule.onNodeWithText("字号跟随系统").assertIsDisplayed()
        // 副标题现在走 AnimatedVisibility（P23）—— 首帧就应当可见。
        composeRule.onNodeWithText("关闭后使用手动字号").assertIsDisplayed()
    }

    /** 探路 2：点击能否触发回调。 */
    @Test
    fun clickInvokesCallback() {
        var clicked = 0
        setThemedContent {
            PrimaryButton(text = "导入图书", onClick = { clicked++ })
        }
        composeRule.onNodeWithText("导入图书").performClick()
        composeRule.waitForIdle()
        assertEquals("点击应当触发一次回调", 1, clicked)
    }

    /** 探路 3：状态变化后语义树能否反映（模拟「点了之后出现什么」）。 */
    @Test
    fun stateChangeIsReflectedInSemanticsTree() {
        val state = androidx.compose.runtime.mutableStateOf(false)
        setThemedContent {
            if (state.value) Text("已展开")
            PrimaryButton(text = "展开", onClick = { state.value = true })
        }
        composeRule.onNodeWithText("展开").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已展开").assertIsDisplayed()
    }
}