package com.linguareader.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.linguareader.app.data.LaunchPromptPolicy
import com.linguareader.app.data.updateNoteFor
import org.junit.Rule
import org.junit.Test

class LaunchPromptUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun greetingDialogShowsSceneCardForMorning() {
        composeRule.setContent {
            LaunchPromptDialog(
                prompt = LaunchPromptUi.GreetingPrompt(LaunchPromptPolicy.greetingForHour(6)),
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("清晨").assertIsDisplayed()
        composeRule.onNodeWithText("5–11 点").assertIsDisplayed()
        composeRule.onNodeWithText("被崭新的一天唤醒，迎接美好的朝阳").assertIsDisplayed()
        composeRule.onNodeWithText("开始阅读").assertIsDisplayed()
    }

    @Test
    fun greetingDialogShowsSceneCardForLateNight() {
        composeRule.setContent {
            LaunchPromptDialog(
                prompt = LaunchPromptUi.GreetingPrompt(LaunchPromptPolicy.greetingForHour(23)),
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("深夜").assertIsDisplayed()
        composeRule.onNodeWithText("22–4 点").assertIsDisplayed()
        composeRule.onNodeWithText("忙碌的人们陷入好梦，窗外是闪烁的星河").assertIsDisplayed()
        composeRule.onNodeWithText("开始阅读").assertIsDisplayed()
    }

    @Test
    fun updateDialogShowsOneTimeReleaseNotes() {
        composeRule.setContent {
            LaunchPromptDialog(
                prompt = LaunchPromptUi.UpdatePrompt(updateNoteFor(5, "1.2.0")),
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("版本更新 1.2.0").assertIsDisplayed()
        composeRule.onNodeWithText("知道了").assertIsDisplayed()
        composeRule.onNodeWithText("· 新增深夜时段", substring = true).assertExists()
    }
}