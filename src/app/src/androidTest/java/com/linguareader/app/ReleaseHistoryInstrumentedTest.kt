package com.linguareader.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.app.update.AppUpdateSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on the isolated emulator with networking disabled; opening history never refreshes. */
@RunWith(AndroidJUnit4::class)
class ReleaseHistoryInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun shelfHistoryRemainsReadableWithoutAnUpdateCheckAcrossRecreation() {
        val settings = AppUpdateSettings.load(context)
        val prefs = context.getSharedPreferences("release_history", 0)
        val oldRead = prefs.getString("read_build", null)
        try {
            AppUpdateSettings.save(context, settings.copy(autoCheckEnabled = false))
            prefs.edit().remove("read_build").commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()
                repeat(3) { listOf(R.string.launch_greeting_start, R.string.common_got_it).forEach { key ->
                    val text = context.getString(key)
                    if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) {
                        compose.onNodeWithText(text).performClick(); compose.waitForIdle()
                    }
                } }
                compose.onNodeWithText(context.getString(R.string.release_history_title)).performClick()
                compose.onNodeWithText(context.getString(R.string.release_history_current_missing)).assertIsDisplayed()
                compose.onNodeWithText(context.getString(R.string.release_history_all_tab)).performClick()
                compose.onNodeWithText("v2.3.1").performScrollTo().assertIsDisplayed()
                assertEquals(null, prefs.getString("read_build", null))
                val installation = prefs.getString("installation", null)
                scenario.recreate()
                compose.waitForIdle()
                compose.onNodeWithText("v2.3.1").performScrollTo().assertIsDisplayed()
                assertEquals(installation, prefs.getString("installation", null))
                compose.onNodeWithText(context.getString(R.string.release_history_mark_read)).performScrollTo().performClick()
                compose.onNodeWithText(context.getString(R.string.release_history_read)).assertIsDisplayed()
            }
        } finally {
            AppUpdateSettings.save(context, settings)
            prefs.edit().putString("read_build", oldRead).commit()
        }
    }
}
