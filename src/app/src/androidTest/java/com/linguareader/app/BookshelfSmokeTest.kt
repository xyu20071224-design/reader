package com.linguareader.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class BookshelfSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bookshelfOffersEpubImport() {
        dismissLaunchPromptIfShown()
        composeRule.onNodeWithText("语境阅读").assertIsDisplayed()
        composeRule.onNodeWithText("导入").assertIsDisplayed()
    }

    @Test
    fun bookshelfCanOpenVocabulary() {
        dismissLaunchPromptIfShown()
        composeRule.onNodeWithText("生词本", substring = true).performClick()

        composeRule.onNodeWithText("我的生词").assertIsDisplayed()
        composeRule.onNodeWithText("复习").assertIsDisplayed()
        composeRule.onNodeWithText("导出").assertIsDisplayed()
    }

    private fun dismissLaunchPromptIfShown() {
        composeRule.waitForIdle()
        listOf("开始阅读", "知道了").forEach { label ->
            if (composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithText(label).performClick()
                composeRule.waitForIdle()
            }
        }
    }
}