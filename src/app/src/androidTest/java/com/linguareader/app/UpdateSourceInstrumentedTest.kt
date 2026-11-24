package com.linguareader.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.app.update.AppUpdateSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real settings wiring: invalid input stays open and selection persists across Activity recreation. */
@RunWith(AndroidJUnit4::class)
class UpdateSourceInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun sourceSelectionValidatesPersistsAndReturnsToGitHub() {
        val original = AppUpdateSettings.load(context)
        val mirror = "https://mirror.example/reader/catalog.json"
        try {
            AppUpdateSettings.save(context, AppUpdateSettings(autoCheckEnabled = false))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()
                repeat(3) {
                    listOf(R.string.launch_greeting_start, R.string.common_got_it).forEach { key ->
                        val text = context.getString(key)
                        if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) {
                            compose.onNodeWithText(text).performClick()
                            compose.waitForIdle()
                        }
                    }
                }
                compose.onNodeWithContentDescription(context.getString(R.string.update_sheet_title)).performClick()
                editSource()
                compose.onNodeWithText(context.getString(R.string.update_source_mirror)).performClick()
                compose.onNodeWithText(context.getString(R.string.update_source_catalog)).performTextInput("http://mirror.example/catalog.json")
                compose.onNodeWithText(context.getString(R.string.common_save)).performClick()
                compose.onNodeWithText(context.getString(R.string.update_source_invalid)).assertIsDisplayed()
                assertNull(AppUpdateSettings.load(context).catalogUrl)
                compose.onNodeWithText(context.getString(R.string.update_source_catalog)).performTextReplacement(mirror)
                compose.onNodeWithText(context.getString(R.string.common_save)).performClick()
                compose.waitUntil { AppUpdateSettings.load(context).catalogUrl == mirror }
                compose.onNodeWithText(mirror).assertIsDisplayed()
                scenario.recreate()
                compose.waitForIdle()
                compose.onNodeWithText(mirror).assertIsDisplayed()
                editSource()
                compose.onNodeWithText(context.getString(R.string.update_source_github)).performClick()
                compose.onNodeWithText(context.getString(R.string.common_save)).performClick()
                compose.waitUntil { AppUpdateSettings.load(context).catalogUrl == null }
                assertEquals(false, AppUpdateSettings.load(context).autoCheckEnabled)
            }
        } finally { AppUpdateSettings.save(context, original) }
    }

    private fun editSource() = compose.onNodeWithText(context.getString(R.string.update_source_change)).performClick()
}
