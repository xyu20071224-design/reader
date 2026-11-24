package com.linguareader.app

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.app.data.SavedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Q1-t05/t12：驱动真实会话和评分回调；不以静态卡片像素替代逐卡进度。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class ReviewDeckProgressRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun cards() = List(8) { index ->
        SavedWord(
            id = "progress-$index", headword = "flowword$index", phonetic = "", meaning = "meaning $index",
            sentence = "A short example for card $index.", bookId = "review-progress", bookTitle = "Review",
            chapterTitle = "Chapter", addedAt = 0L, reviewLevel = 0, nextReviewAt = 0L
        )
    }

    private fun inReview(text: String): SemanticsNodeInteraction = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(UiTags.REVIEW_SHEET))
    )

    private fun exerciseDeck(fromVocabulary: Boolean) {
        val original = cards()
        val savedWords = mutableStateOf(original)
        val ids = original.map { it.id }.toSet()
        val graded = mutableListOf<Pair<String, Boolean>>()
        fun review(id: String, remembered: Boolean, done: (Boolean) -> Unit) {
            // 模拟真实评分完成后，上游发布带新 reviewLevel/nextReviewAt 的 savedWords。
            // ReaderScreen 按固定 ID 集合重筛时会得到新列表，卡组身份仍应保持不变。
            savedWords.value = savedWords.value.map { word ->
                if (word.id == id) word.copy(
                    reviewLevel = if (remembered) word.reviewLevel + 1 else 0,
                    nextReviewAt = System.currentTimeMillis() + 60_000L
                ) else word
            }
            graded += id to remembered
            done(true)
        }
        compose.setContent {
            MaterialTheme {
                if (fromVocabulary) {
                    VocabularyScreen(
                        words = savedWords.value,
                        reviewPreset = ReviewMode.DEFAULT,
                        customReview = ReviewPace.defaultCustom(),
                        reminders = ReviewReminders.DEFAULT,
                        onReviewModeChange = {}, onCustomReviewChange = {}, onRemindersChange = {},
                        onRemove = {}, onReview = ::review, onExport = {}, onSpeak = {}
                    )
                } else {
                    ReviewSheet(
                        deck = savedWords.value.filter { it.id in ids },
                        onReview = ::review, onSpeak = {}, onDismiss = {}
                    )
                }
            }
        }
        if (fromVocabulary) {
            compose.onNodeWithText(context.getString(R.string.common_review)).assertIsDisplayed().performClick()
        }
        original.forEachIndexed { index, card ->
            val progress = if (index == 0) {
                context.getString(R.string.review_card_position, 1, original.size)
            } else context.getString(R.string.review_card_progress, index, original.size)
            inReview(progress).assertIsDisplayed()
            inReview(card.headword).assertIsDisplayed()
            inReview(context.getString(R.string.review_reveal)).assertIsDisplayed().performClick()
            inReview(card.meaning).assertIsDisplayed()
            val remembered = index % 2 == 0
            inReview(context.getString(if (remembered) R.string.review_known else R.string.review_learn_again))
                .assertIsDisplayed().performClick()
            compose.runOnIdle {
                assertEquals(index + 1, graded.size)
                assertEquals(card.id to remembered, graded.last())
                assertTrue(savedWords.value.single { it.id == card.id }.nextReviewAt > 0L)
            }
        }
        inReview(context.getString(R.string.review_done_title)).assertIsDisplayed()
        inReview(context.getString(R.string.review_done_body, 8, 8)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(original.map { it.id }, graded.map { it.first })
            assertEquals(8, graded.map { it.first }.distinct().size)
            assertEquals(List(8) { it % 2 == 0 }, graded.map { it.second })
        }
    }

    @Test
    fun vocabularyReviewCompletesAllEightDueWordsWithTheActualDenominator() = exerciseDeck(fromVocabulary = true)

    @Test
    fun refilteredSavedWordsDoNotResetTheActiveReviewSessionAfterEachGrade() = exerciseDeck(fromVocabulary = false)
}
