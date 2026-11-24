package com.linguareader.app

import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Q1-b08 / Q2-c08：实际 Activity 顶栏与系统返回；不删书、不改生词或复习数据。 */
class VocabularyNavigationAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var viewModel: AppViewModel

    @Before
    fun arrangeBookshelf() {
        compose.runOnIdle { viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        compose.waitUntil(10_000) { compose.runOnIdle { !viewModel.state.value.loading } }
        compose.runOnIdle { viewModel.dismissLaunchPrompt() }
        compose.onNodeWithContentDescription(text(R.string.shelf_vocabulary)).assertIsDisplayed()
    }

    private fun text(id: Int) = compose.activity.getString(id)

    private fun openVocabulary() {
        compose.onNodeWithContentDescription(text(R.string.shelf_vocabulary)).performClick()
        compose.onNodeWithText(text(R.string.vocab_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.shelf_tab_shelf)).assertIsDisplayed()
    }

    @Test
    fun vocabularyHidesEveryBookshelfOnlyActionAndKeepsItsOwnActions() {
        val update = compose.runOnIdle { viewModel.state.value.update }
        val updateDescription = if (update.updateAvailable) {
            compose.activity.getString(R.string.update_available_title, update.info?.versionName.orEmpty())
        } else text(R.string.update_sheet_title)
        val descriptions = listOf(
            text(R.string.sync_entry), text(R.string.storage_title),
            text(R.string.shelf_appearance_title), updateDescription
        )
        // 正对照：先证明这些是当前书架的真实动作，再验证进入生词本后完全移除。
        compose.onNodeWithTag(UiTags.SHELF_IMPORT).assertExists()
        compose.onNodeWithTag(UiTags.SHELF_AI_CENTER).assertExists()
        descriptions.forEach { compose.onNodeWithContentDescription(it).assertExists() }

        openVocabulary()

        compose.onNodeWithTag(UiTags.SHELF_IMPORT).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.SHELF_AI_CENTER).assertDoesNotExist()
        descriptions.forEach { compose.onNodeWithContentDescription(it).assertDoesNotExist() }
        compose.onNodeWithText(text(R.string.common_review)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vocab_export)).assertIsDisplayed()
    }

    @Test
    fun systemBackReturnsFromVocabularyToTheSameBookshelfActivityAndAllowsReentry() {
        val activity = compose.activity
        val pid = Process.myPid()
        repeat(3) {
            openVocabulary()
            Espresso.pressBack()
            compose.onNodeWithContentDescription(text(R.string.shelf_vocabulary)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.vocab_title)).assertDoesNotExist()
            compose.runOnIdle {
                assertSame(activity, compose.activity)
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
                assertEquals(pid, Process.myPid())
                assertNull(viewModel.state.value.currentBook)
            }
        }
        openVocabulary()
    }
}
