package com.linguareader.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Runs the production Activity, state collection, navigation host and reader close callbacks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
class ReaderReturnRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var viewModel: AppViewModel
    private lateinit var book: Book

    @Before fun prepareBook() {
        compose.runOnIdle {
            viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            viewModel.dismissLaunchPrompt()
            val directory = File(compose.activity.filesDir, "books/return-regression").apply { mkdirs() }
            File(directory, "chapter.html").writeText("<html><body><p>A quiet afternoon in the library.</p></body></html>")
            book = Book(
                id = directory.name, title = "Return Regression", author = "Test",
                extractedDir = directory.absolutePath, coverRelativePath = null,
                chapters = listOf(Chapter("Return Chapter", "chapter.html")), addedAt = 1L
            )
            File(directory, "metadata.json").writeText(book.toJson().toString())
            viewModel.refresh()
        }
        compose.waitUntil(10_000) { compose.runOnIdle { !viewModel.state.value.loading } }
    }

    private fun openBook() {
        compose.onNodeWithText(book.title).assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Return Chapter").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Return Chapter").assertIsDisplayed()
    }

    private fun assertReturnedToShelf() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(book.title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(book.title).assertIsDisplayed()
        compose.runOnIdle {
            assertNull(viewModel.state.value.currentBook)
            assertFalse(compose.activity.isFinishing)
            assertFalse(compose.activity.isDestroyed)
        }
    }

    @Test fun toolbarBackReturnsToShelfAndBookCanBeReopened() {
        repeat(3) {
            openBook()
            compose.onNodeWithContentDescription("返回").performClick()
            assertReturnedToShelf()
        }
    }

    @Test fun systemBackClosesTheBookInsteadOfFinishingActivity() {
        openBook()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertReturnedToShelf()
    }
}
