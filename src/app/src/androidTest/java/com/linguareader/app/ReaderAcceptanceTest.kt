package com.linguareader.app

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.app.data.VocabularyRepository
import com.linguareader.app.data.WordLookup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
    private val unlistedWord = "qzxvocabfixture"

    @After
    fun tearDown() {
        bookRoot.deleteRecursively()
        runBlocking { VocabularyRepository(context).remove(unlistedWord) }
    }

    @Test
    fun controlsHideTogetherWithoutChangingTextViewport() {
        seedBook()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openBook()
            waitForPageIndicator()
            fun findWebView(view: android.view.View): android.webkit.WebView? {
                if (view is android.webkit.WebView) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                    findWebView(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            fun js(script: String): String {
                val ready = java.util.concurrent.CountDownLatch(1)
                var result = ""
                scenario.onActivity { activity ->
                    val web = requireNotNull(findWebView(activity.window.decorView))
                    web.evaluateJavascript(script) { result = it; ready.countDown() }
                }
                assertTrue("WebView callback timed out", ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return result
            }
            val metricsScript = "JSON.stringify([getComputedStyle(document.documentElement).getPropertyValue('--lr-chrome-top'), getComputedStyle(document.documentElement).getPropertyValue('--lr-chrome-bottom'), document.getElementById('lr-scroller').clientHeight])"
            val initialViewport = js(metricsScript)
            val initialPage = pageNumber()
            composeRule.mainClock.advanceTimeBy(4_500)
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithContentDescription(context.getString(R.string.reader_page_indicator))
                    .fetchSemanticsNodes().isEmpty()
            }
            assertTrue(composeRule.onAllNodesWithContentDescription(context.getString(R.string.reader_back))
                .fetchSemanticsNodes().isEmpty())
            assertEquals(initialViewport, js(metricsScript))
            // Send a blank-area tap through the real document pointer handlers and ReaderBridge.
            assertEquals("true", js("window.lrLookupAt(innerWidth / 2, 4, false) === null"))
            js("['pointerdown','pointerup'].forEach(function(type) { document.dispatchEvent(new PointerEvent(type, {clientX: innerWidth / 2, clientY: 4, bubbles: true})); })")
            waitForPageIndicator()
            assertEquals(initialPage, pageNumber())
            assertEquals(initialViewport, js(metricsScript))
            composeRule.onNodeWithContentDescription(context.getString(R.string.reader_next_page)).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithContentDescription(context.getString(R.string.reader_page_indicator))
                    .fetchSemanticsNodes().isEmpty()
            }
            js("['pointerdown','pointerup'].forEach(function(type) { document.dispatchEvent(new PointerEvent(type, {clientX: innerWidth / 2, clientY: 4, bubbles: true})); })")
            waitForPageIndicator()
            assertTrue("page turn must advance text", pageNumber() > initialPage)
            assertEquals(initialViewport, js(metricsScript))
        }
    }

    @Test
    fun readerKeepsCurrentPageAfterConfigurationChange() {
        seedBook()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openBook()
            waitForPageIndicator()

            composeRule.onNodeWithContentDescription(context.getString(R.string.reader_next_page)).performClick()
            // Page turns intentionally hide the controls. Reveal them through the real
            // document handler before inspecting the new page; viewport geometry stays fixed.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithContentDescription(context.getString(R.string.reader_page_indicator))
                    .fetchSemanticsNodes().isEmpty()
            }
            revealReaderControls(scenario)
            waitForPageIndicator()
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

            composeRule.onNodeWithContentDescription(context.getString(R.string.reader_page_indicator)).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(context.getString(R.string.reader_jump_title)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(hasSetTextAction()).performTextClearance()
            composeRule.onNode(hasSetTextAction()).performTextInput("3")
            composeRule.onNodeWithText(context.getString(R.string.reader_jump_action)).performClick()

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
                found = composeRule.onAllNodesWithText(context.getString(R.string.reader_add_to_vocab))
                    .fetchSemanticsNodes().isNotEmpty()
                y += 60
            }
            assertTrue("lookup sheet did not open after tapping text", found)
            composeRule.onNodeWithText(context.getString(R.string.reader_add_to_vocab)).assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.common_speak)).assertIsDisplayed()
        }
    }

    @Test
    fun dictionaryHitShowsChineseMeaningAfterWordTap() = assertDictionaryMeaningAfterTap("lantern", "灯笼")

    @Test
    fun horseLookupRepairsEmptyCopyAfterWordTap() = assertDictionaryMeaningAfterTap("horse", "马", emptyCopy = true)

    private fun assertDictionaryMeaningAfterTap(word: String, chinese: String, emptyCopy: Boolean = false) {
        val repository = DictionaryRepository(context)
        val result = runBlocking {
            repository.lookup(WordLookup(word, "$word.", "", 0, 0f, 0f))
        }
        val meaning = requireNotNull(result.entry).senses.first().text
        repository.invalidate()
        assertTrue("Bundled dictionary must contain Chinese meaning", meaning.contains(chinese))
        if (emptyCopy) {
            val target = File(context.filesDir, "dictionary/ecdict-v2.sqlite")
            assertTrue(target.delete())
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(target, null).use { db ->
                db.execSQL("CREATE TABLE entries(word TEXT PRIMARY KEY, phonetic TEXT, translation TEXT, definition TEXT)")
                db.execSQL("CREATE TABLE forms(form TEXT, lemma TEXT)")
            }
        }
        seedBook(word)
        ActivityScenario.launch(MainActivity::class.java).use {
            openBook()
            waitForPageIndicator()
            val metrics = context.resources.displayMetrics
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            var y = (metrics.heightPixels * 0.25).toInt()
            while (y < metrics.heightPixels * 0.55 &&
                composeRule.onAllNodesWithTag(UiTags.READER_LOOKUP_SHEET).fetchSemanticsNodes().isEmpty()) {
                ui.executeShellCommand("input tap ${metrics.widthPixels / 2} $y").close()
                SystemClock.sleep(700)
                y += 60
            }
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(meaning).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(meaning).assertIsDisplayed()
        }
    }

    @Test
    fun dictionaryMissCanBeSavedReopenedAndRemoved() {
        val repository = VocabularyRepository(context)
        val request = WordLookup(unlistedWord, "$unlistedWord.", "", 0, 0f, 0f)
        assertTrue("测试词必须未被本地词典收录", runBlocking {
            DictionaryRepository(context).lookup(request).entry == null
        })
        runBlocking { repository.remove(unlistedWord) }
        seedBook(unlistedWord)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openBook()
            waitForPageIndicator()
            val metrics = context.resources.displayMetrics
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            var y = (metrics.heightPixels * 0.25).toInt()
            while (y < metrics.heightPixels * 0.55 &&
                composeRule.onAllNodesWithTag(UiTags.READER_LOOKUP_SHEET).fetchSemanticsNodes().isEmpty()) {
                ui.executeShellCommand("input tap ${metrics.widthPixels / 2} $y").close()
                SystemClock.sleep(700)
                y += 60
            }
            val add = context.getString(R.string.reader_add_to_vocab)
            val remove = context.getString(R.string.reader_remove_from_vocab)
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(hasText(add) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(add).assertIsDisplayed().performClick()
            composeRule.waitUntil(5_000) {
                runBlocking { repository.load().any { it.id == unlistedWord } }
            }
            val saved = runBlocking { repository.load().first { it.id == unlistedWord } }
            assertTrue(saved.headword == unlistedWord && saved.sentence.contains(unlistedWord))

            scenario.recreate()
            composeRule.waitUntil(15_000) {
                composeRule.onAllNodes(hasText(remove) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(remove).assertIsDisplayed().performClick()
            composeRule.waitUntil(5_000) {
                runBlocking { repository.load().none { it.id == unlistedWord } }
            }
            composeRule.onNodeWithText(add).assertIsDisplayed()
        }
    }

    private fun openBook() {
        dismissLaunchPromptIfShown()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Acceptance Book").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Acceptance Book").performClick()
    }

    private fun revealReaderControls(scenario: ActivityScenario<MainActivity>) {
        val done = java.util.concurrent.CountDownLatch(1)
        var revealed = false
        scenario.onActivity { activity ->
            fun find(view: android.view.View): android.webkit.WebView? = when (view) {
                is android.webkit.WebView -> view
                is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
                else -> null
            }
            requireNotNull(find(activity.window.decorView)).evaluateJavascript("""(() => {
                if (window.lrLookupAt(innerWidth / 2, 4, false) !== null) return false;
                ['pointerdown','pointerup'].forEach(type => document.dispatchEvent(new PointerEvent(type,
                  {clientX:innerWidth/2,clientY:4,bubbles:true})));
                return true;
            })()""") { revealed = it == "true"; done.countDown() }
        }
        assertTrue("Blank-area reader event did not complete", done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("The reveal action must target a blank area", revealed)
    }

    private fun dismissLaunchPromptIfShown() {
        composeRule.waitForIdle()
        listOf(R.string.launch_greeting_start, R.string.common_got_it).map(context::getString).forEach { label ->
            if (composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithText(label).performClick()
                composeRule.waitForIdle()
            }
        }
    }

    private fun waitForPageIndicator() {
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithContentDescription(context.getString(R.string.reader_page_indicator))
                .fetchSemanticsNodes().isNotEmpty()
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
        composeRule.onNodeWithContentDescription(context.getString(R.string.reader_page_indicator))
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    private fun pageNumber(): Int =
        pageIndicatorText().substringAfter("·").trim().substringBefore("/").trim().toInt()

    private fun pageCount(): Int =
        pageIndicatorText().substringAfter("·").trim().substringAfter("/").trim().toInt()

    private fun seedBook(repeatedWord: String? = null) {
        bookRoot.mkdirs()
        val firstBody = repeatedWord?.let { word ->
            (1..40).joinToString("\n") { "<p>${List(50) { word }.joinToString(" ") }.</p>" }
        } ?: longBody(1)
        File(bookRoot, "chapter_001.xhtml").writeText(chapterXhtml("Chapter One", firstBody))
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
