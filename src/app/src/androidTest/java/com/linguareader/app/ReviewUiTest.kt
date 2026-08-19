package com.linguareader.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.app.ui.review.ReviewPromptBanner
import com.linguareader.app.ui.review.ReviewSettingsSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReviewUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reviewSettingsSheetListsReminderChannelsPresetsAndCustom() {
        composeRule.setContent {
            ReviewSettingsSheet(
                preset = ReviewMode.GENTLE,
                custom = ReviewPace.defaultCustom(),
                reminders = ReviewReminders.DEFAULT,
                onChangePreset = {},
                onChangeCustom = {},
                onChangeReminders = {},
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("语境浮现").assertIsDisplayed()
        composeRule.onNodeWithText("仅手动").assertExists()
        composeRule.onNodeWithText("沉浸阅读").assertExists()
        composeRule.onNodeWithText("温和节奏").assertExists()
        composeRule.onNodeWithText("勤学模式").assertExists()
        composeRule.onNodeWithText("默认").assertExists()
        composeRule.onNodeWithText("自定义").assertExists()
    }

    @Test
    fun reviewSettingsSheetReportsSelectedPreset() {
        var selected: ReviewMode? = null
        composeRule.setContent {
            ReviewSettingsSheet(
                preset = ReviewMode.GENTLE,
                custom = ReviewPace.defaultCustom(),
                reminders = ReviewReminders.DEFAULT,
                onChangePreset = { selected = it },
                onChangeCustom = {},
                onChangeReminders = {},
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("沉浸阅读").performScrollTo().performClick()

        assertEquals(ReviewMode.IMMERSIVE, selected)
    }

    @Test
    fun customEditorShowsCurveAndSaveApplies() {
        var saved: ReviewPace? = null
        composeRule.setContent {
            ReviewSettingsSheet(
                preset = null,
                custom = ReviewPace.defaultCustom(),
                reminders = ReviewReminders.DEFAULT,
                onChangePreset = {},
                onChangeCustom = { saved = it },
                onChangeReminders = {},
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("语境浮现").assertIsDisplayed()
        composeRule.onNodeWithText("复习时约剩 85% 记忆").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("定时轻提醒").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("保存并启用").performScrollTo().performClick()

        assertEquals(ReviewPace.defaultCustom(), saved)
    }

    @Test
    fun reminderSwitchesCombineIndependently() {
        var updated: ReviewReminders? = null
        composeRule.setContent {
            ReviewSettingsSheet(
                preset = ReviewMode.GENTLE,
                custom = ReviewPace.defaultCustom(),
                reminders = ReviewReminders.DEFAULT,
                onChangePreset = {},
                onChangeCustom = {},
                onChangeReminders = { updated = it },
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("语境浮现").performClick()
        assertEquals(
            ReviewReminders(
                contextHighlight = false,
                pausePrompt = true,
                toolbarBadge = true,
                notifications = false
            ),
            updated
        )

        composeRule.onNodeWithText("仅手动").performScrollTo().performClick()
        assertEquals(
            ReviewReminders(
                contextHighlight = false,
                pausePrompt = false,
                toolbarBadge = false,
                notifications = false
            ),
            updated
        )
    }

    @Test
    fun pausePromptShowsDueCountAndStartAction() {
        var started = false
        composeRule.setContent {
            ReviewPromptBanner(
                count = 3,
                dwellMillis = 60_000L,
                onStart = { started = true },
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("有 3 个词可快速复习").assertIsDisplayed()
        composeRule.onNodeWithText("开始").performClick()
        assertEquals(true, started)
    }
}