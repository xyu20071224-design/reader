package com.linguareader.app

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderAcceptanceTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bookRoot = File(context.filesDir, "books/acceptance")

    @After
    fun tearDown() {
        bookRoot.deleteRecursively()
    }

    @Test
    fun readerKeepsCurrentPageAfterConfigurationChange() {
        seedBook()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openBook()
            waitForPageIndicator()

            composeRule.onNodeWithContentDescription("下一页").performClick()
            composeRule.waitUntil(5_000) { pageNumber() != 1 }
            val secondPage = pageNumber()

            scenario.recreate()
            composeRule.waitForIdle()
            waitForPageIndicator()

            // Rotation (configuration change) must restore the same chapter/page.
            // Total page count may shift slightly after remeasurement, so only
            // the current page index (and chapter) must match.
            assertTrue("expected page $secondPage but was ${pageNumber()}", pageNumber() == secondPage)
        }
    }

    @Test
    fun readerJumpsToTypedPageWithinChapter() {
        seedBook()
        ActivityScenario.launch(MainActivity::class.java).use {
            openBook()
            waitForPageIndicator()

            composeRule.onNodeWithContentDescription("页码指示").performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("跳转到页码").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(hasSetTextAction()).performTextClearance()
            composeRule.onNode(hasSetTextAction()).performTextInput("3")
            composeRule.onNodeWithText("跳转").performClick()

            composeRule.waitUntil(5_000) { pageIndicatorText().contains("· 3/") }
            assertTrue(pageIndicatorText().contains("· 3/"))
        }
    }

    @Test
    fun readerWordTapOpensLookupSheet() {
        seedBook()
        ActivityScenario.launch(MainActivity::class.java).use {
            openBook()
            waitForPageIndicator()

            val metrics = context.resources.displayMetrics
            val x = metrics.widthPixels / 2
            var y = (metrics.heightPixels * 0.25).toInt()
            var found = false
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            while (y < metrics.heightPixels * 0.55 && !found) {
                ui.executeShellCommand("input tap $x $y").close()
                SystemClock.sleep(700L)
                found = composeRule.onAllNodesWithText("加入生词本")
                    .fetchSemanticsNodes().isNotEmpty()
                y += 60
            }
            assertTrue("lookup sheet did not open after tapping text", found)
            composeRule.onNodeWithText("加入生词本").assertIsDisplayed()
            composeRule.onNodeWithText("朗读").assertIsDisplayed()
        }
    }

    private fun openBook() {
        dismissLaunchPromptIfShown()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Acceptance Book").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Acceptance Book").performClick()
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

    private fun waitForPageIndicator() {
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithContentDescription("页码指示").fetchSemanticsNodes().isNotEmpty()
        }
        // The indicator appears as soon as the toolbar renders, before the
        // chapter has finished paginating (pageCount starts at 1). A page turn
        // tapped in that window is silently dropped because window.lrTurn is
        // not installed until the WebView fires onReady. Wait until the real
        // page count is known so the reader is actually ready to turn pages.
        composeRule.waitUntil(15_000) {
            runCatching { pageCount() }.getOrDefault(1) > 1
        }
    }

    private fun pageIndicatorText(): String =
        composeRule.onNodeWithContentDescription("页码指示")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    private fun pageNumber(): Int =
        pageIndicatorText().substringAfter("·").trim().substringBefore("/").trim().toInt()

    private fun pageCount(): Int =
        pageIndicatorText().substringAfter("·").trim().substringAfter("/").trim().toInt()

    private fun seedBook() {
        bookRoot.mkdirs()
        File(bookRoot, "chapter_001.xhtml").writeText(chapterXhtml("Chapter One", longBody(1)))
        File(bookRoot, "chapter_002.xhtml").writeText(chapterXhtml("Chapter Two", longBody(2)))
        val book = Book(
            id = "acceptance",
            title = "Acceptance Book",
            author = "Tester",
            extractedDir = bookRoot.absolutePath,
            coverRelativePath = null,
            chapters = listOf(
                Chapter("Chapter One", "chapter_001.xhtml"),
                Chapter("Chapter Two", "chapter_002.xhtml")
            ),
            addedAt = System.currentTimeMillis(),
            sourceFormat = "epub"
        )
        File(bookRoot, "metadata.json").writeText(book.toJson().toString())
    }

    private fun chapterXhtml(title: String, body: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
<title>${escape(title)}</title>
</head><body>
$body
</body></html>"""

    private fun longBody(seed: Int): String =
        (1..140).joinToString("\n") { index ->
            "<p>${escape(paragraph(seed, index))}</p>"
        }

    private fun paragraph(seed: Int, index: Int): String {
        // All common dictionary words so any tapped position opens a lookup.
        val words = listOf(
            "the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog",
            "and", "then", "every", "word", "here", "is", "common", "text"
        )
        return List(70) { words[(seed + index + it) % words.size] }.joinToString(" ")
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
