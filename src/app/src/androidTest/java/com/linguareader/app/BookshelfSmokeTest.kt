package com.linguareader.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class BookshelfSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bookshelfOffersEpubImport() {
        dismissLaunchPromptIfShown()
        // 书架顶栏只放书/词计数、不放应用名（BookshelfScreen.kt:233 有意为之），
        // 空书架才是「书架已就绪」的可见标志。
        composeRule.onNodeWithText("从一本英文书开始").assertIsDisplayed()
        // 「导入」自 2026-09 起是图标入口，名字只在 contentDescription（BookshelfScreen.kt:292）。
        composeRule.onNodeWithContentDescription("导入").assertIsDisplayed()
    }

    @Test
    fun bookshelfCanOpenVocabulary() {
        dismissLaunchPromptIfShown()
        // 书架/生词本切换是图标按钮，名字只在 contentDescription（BookshelfScreen.kt:295-302）。
        composeRule.onNodeWithContentDescription("生词本").performClick()

        composeRule.onNodeWithText("我的生词").assertIsDisplayed()
        composeRule.onNodeWithText("复习").assertIsDisplayed()
        composeRule.onNodeWithText("导出").assertIsDisplayed()
    }

    /**
     * 关掉启动问候 / 版本更新弹窗。
     *
     * 弹窗晚于 Activity 出现（首启要算问候时段、更新提示要读版本号），只做一次
     * `waitForIdle()` 后立刻探测会漏掉它，之后弹窗仍盖在书架上、断言随设备快慢
     * 时红时绿（PKB110 / Android 16 上实测到）。这里改成有界的「等弹窗出现再关」。
     */
    private fun dismissLaunchPromptIfShown() {
        composeRule.waitForIdle()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            // device 实测：冷启更新提示的确认按钮文案是「开始使用」，不是「知道了」。
            if (clickAnyIfPresent(listOf("开始使用", "开始阅读", "知道了"))) return
            if (composeRule.onAllNodesWithText("从一本英文书开始").fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(50)
        }
        throw AssertionError("启动弹窗在 5s 内既没有出现、也没有消失")
    }

    private fun clickAnyIfPresent(labels: List<String>): Boolean {
        val hit = labels.firstOrNull {
            composeRule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()
        } ?: return false
        composeRule.onNodeWithText(hit).performClick()
        composeRule.waitForIdle()
        return true
    }
}