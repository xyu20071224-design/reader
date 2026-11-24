package com.linguareader.app

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import com.linguareader.app.data.SavedWord
import com.linguareader.app.data.VocabularyRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises Reader -> Reader in the real NavHost, without returning to the bookshelf. */
@RunWith(AndroidJUnit4::class)
class LookupBookIsolationInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun switchingBooksClosesTheOldLookupAndSavesOnlyTheNewBooksWord() {
        val vocabularyFile = File(context.filesDir, "vocabulary.json")
        val previousVocabulary = vocabularyFile.takeIf { it.isFile }?.readBytes()
        // Only this local feature flag is changed; service credentials are never read or logged.
        val aiPreferences = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
        val hadAiEnabled = aiPreferences.contains("enabled")
        val previousAiEnabled = aiPreferences.getBoolean("enabled", false)
        val suffix = UUID.randomUUID().toString().take(8)
        val rootA = File(context.filesDir, "books/lookup-isolation-a-$suffix")
        val rootB = File(context.filesDir, "books/lookup-isolation-b-$suffix")
        val wordA = "qzxalpha"
        val wordB = "qzxbeta"
        val markerA = "ALOOKUPFIXTURE"
        val markerB = "BLOOKUPFIXTURE"
        try {
            assertTrue(aiPreferences.edit().putBoolean("enabled", false).commit())
            val bookA = fixture(rootA, "Lookup A $suffix", wordA, markerA)
            val bookB = fixture(rootB, "Lookup B $suffix", wordB, markerB)
            val originalAWord = SavedWord(
                id = "qzxsentry", headword = "qzxsentry", phonetic = "", meaning = "A control record",
                sentence = "An unchanged saved context from $markerA.", bookId = bookA.id,
                bookTitle = bookA.title, chapterTitle = bookA.chapters.single().title,
                addedAt = 17L, reviewLevel = 3, nextReviewAt = Long.MAX_VALUE,
                reviewCount = 5, surfaceForms = emptyList(), updatedAt = 19L
            )
            vocabularyFile.writeText(JSONArray().put(originalAWord.toJson()).toString())
            val vocabulary = VocabularyRepository(context)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var viewModel: AppViewModel
                scenario.onActivity { viewModel = ViewModelProvider(it)[AppViewModel::class.java] }
                compose.waitUntil(15_000) { !viewModel.state.value.loading }
                scenario.onActivity { viewModel.dismissLaunchPrompt() }
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(bookA.title).fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(listOf(originalAWord), viewModel.state.value.savedWords)
                compose.onNodeWithText(bookA.title).performClick()
                compose.waitUntil(15_000) { viewModel.state.value.currentBook?.id == bookA.id }
                awaitReaderContent(scenario, markerA, markerB)
                tapVisibleWord(scenario, wordA, markerA)
                awaitLookup(wordA)
                compose.onNodeWithTag(UiTags.READER_LOOKUP_SHEET).assertIsDisplayed()
                assertEquals(listOf(originalAWord), runBlocking { vocabulary.load() })

                // This is the public, real Reader -> Reader transition: no closeBook, back,
                // recreation, or test-only lookup-state reset may hide the stale-state defect.
                lateinit var originalActivity: MainActivity
                scenario.onActivity { activity ->
                    originalActivity = activity
                    viewModel.openBook(bookB)
                }
                compose.waitUntil(15_000) { viewModel.state.value.currentBook?.id == bookB.id }
                awaitReaderContent(scenario, markerB, markerA)
                compose.waitForIdle()
                scenario.onActivity { activity ->
                    assertTrue("Switching a book must stay in the same Activity", activity === originalActivity)
                }
                compose.onNodeWithTag(UiTags.READER_PAGE).assertIsDisplayed()
                // Assert before tapping B: a new tap must not mask the old A sheet surviving.
                compose.onNodeWithTag(UiTags.READER_LOOKUP_SHEET).assertDoesNotExist()
                assertEquals(listOf(originalAWord), runBlocking { vocabulary.load() })

                tapVisibleWord(scenario, wordB, markerB)
                awaitLookup(wordB)
                val add = hasText(context.getString(R.string.reader_add_to_vocab)) and isEnabled() and
                    hasAnyAncestor(hasTestTag(UiTags.READER_LOOKUP_SHEET))
                compose.waitUntil(15_000) { compose.onAllNodes(add).fetchSemanticsNodes().size == 1 }
                compose.onNode(add).assertIsDisplayed().performClick()
                compose.waitUntil(10_000) {
                    runBlocking { vocabulary.load().any { it.id == wordB } }
                }
                val saved = runBlocking { vocabulary.load() }
                assertEquals("Exactly the A control and the B word may be present", 2, saved.size)
                assertEquals("A's context and review state must be unchanged", originalAWord,
                    saved.single { it.id == originalAWord.id })
                assertFalse("The discarded A lookup must never be saved", saved.any { it.id == wordA })
                assertEquals(listOf(originalAWord), saved.filter { it.bookId == bookA.id })
                val savedB = saved.single { it.id == wordB }
                assertEquals(bookB.id, savedB.bookId)
                assertEquals(bookB.title, savedB.bookTitle)
                assertEquals(bookB.chapters.single().title, savedB.chapterTitle)
                assertEquals(wordB, savedB.headword)
                assertTrue(savedB.sentence.contains(wordB))
                assertTrue(savedB.sentence.contains(markerB))
                assertFalse(savedB.sentence.contains(markerA))
                assertEquals(listOf(savedB), saved.filter { it.bookId == bookB.id })
                compose.waitUntil(10_000) { viewModel.state.value.savedWords.any { it.id == wordB } }
                assertEquals(saved.toSet(), viewModel.state.value.savedWords.toSet())
            }
        } finally {
            rootA.deleteRecursively()
            rootB.deleteRecursively()
            if (previousVocabulary == null) vocabularyFile.delete() else vocabularyFile.writeBytes(previousVocabulary)
            val restore = aiPreferences.edit()
            if (hadAiEnabled) restore.putBoolean("enabled", previousAiEnabled) else restore.remove("enabled")
            assertTrue("Restore the original local AI flag", restore.commit())
        }
    }

    private fun fixture(root: File, title: String, word: String, marker: String): Book {
        assertTrue("Fixture directory must be new", root.mkdirs())
        val body = "<p>This is a local reader fixture.</p>" +
            "<p>Two ordinary context paragraphs precede the lookup target.</p>" +
            (1..28).joinToString("") { "<p>" + ("$word waits in $marker local text. ").repeat(18) + "</p>" }
        File(root, "chapter.xhtml").writeText(
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"UTF-8\"/></head>" +
                "<body>$body</body></html>"
        )
        return Book(root.name, title, "Local isolation fixture", root.absolutePath, null,
            listOf(Chapter("$marker chapter", "chapter.xhtml")), addedAt = System.currentTimeMillis()).also {
            File(root, "metadata.json").writeText(it.toJson().toString())
        }
    }

    private fun awaitLookup(word: String) {
        val expectedWord = hasText(word) and hasAnyAncestor(hasTestTag(UiTags.READER_LOOKUP_SHEET))
        compose.waitUntil(15_000) { compose.onAllNodes(expectedWord).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(UiTags.READER_LOOKUP_SHEET).assertIsDisplayed()
    }

    private fun awaitReaderContent(scenario: ActivityScenario<MainActivity>, marker: String, otherMarker: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        do {
            if (evaluate(scenario, """(() => {
                const content = document.getElementById('lingua-reader-content');
                return typeof window.lrLookupAt === 'function' && !!content &&
                    content.textContent.includes(${JSONObject.quote(marker)}) &&
                    !content.textContent.includes(${JSONObject.quote(otherMarker)});
            })()""") == "true") return
            SystemClock.sleep(150)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("The requested book's actual WebView content did not become ready: $marker")
    }

    private fun tapVisibleWord(scenario: ActivityScenario<MainActivity>, word: String, marker: String) {
        var point: Pair<Int, Int>? = null
        val deadline = SystemClock.uptimeMillis() + 15_000
        do {
            val latch = CountDownLatch(1)
            scenario.onActivity { activity ->
                val web = findWebView(activity.window.decorView)
                if (web == null) { latch.countDown(); return@onActivity }
                val origin = IntArray(2).also(web::getLocationOnScreen)
                val scale = web.scale
                web.evaluateJavascript("""(() => {
                    const content = document.getElementById('lingua-reader-content');
                    if (!content || typeof window.lrLookupAt !== 'function' ||
                        !content.textContent.includes(${JSONObject.quote(marker)})) return null;
                    const walker = document.createTreeWalker(content, NodeFilter.SHOW_TEXT);
                    while (walker.nextNode()) {
                        const node = walker.currentNode;
                        if (node.parentElement.closest('script,style')) continue;
                        for (const match of node.textContent.matchAll(new RegExp('\\b' + ${JSONObject.quote(word)} + '\\b', 'g'))) {
                            const range = document.createRange();
                            range.setStart(node, match.index); range.setEnd(node, match.index + ${word.length});
                            const r = range.getBoundingClientRect();
                            if (r.width <= 0 || r.left < 0 || r.right > innerWidth || r.top <= 90 || r.bottom >= innerHeight - 160) continue;
                            const x = r.left + r.width / 2, y = r.top + r.height / 2;
                            const hit = window.lrLookupAt(x, y, false);
                            if (hit && hit.word.toLowerCase() === ${JSONObject.quote(word)} &&
                                hit.sentence.includes(${JSONObject.quote(word)}) && hit.paragraph.includes(${JSONObject.quote(marker)}))
                                return JSON.stringify({x:x, y:y});
                        }
                    }
                    return null;
                })()""") { raw ->
                    val value = JSONTokener(raw).nextValue()
                    if (value is String) JSONObject(value).let {
                        point = (origin[0] + it.getDouble("x") * scale).toInt() to
                            (origin[1] + it.getDouble("y") * scale).toInt()
                    }
                    latch.countDown()
                }
            }
            assertTrue("WebView hit-check callback did not arrive", latch.await(10, TimeUnit.SECONDS))
            if (point == null) SystemClock.sleep(150)
        } while (point == null && SystemClock.uptimeMillis() < deadline)
        val (x, y) = requireNotNull(point) { "No safely visible, real lrLookupAt hit for $word in $marker" }
        instrumentation.uiAutomation.executeShellCommand("input tap $x $y").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        scenario.onActivity { activity ->
            val web = findWebView(activity.window.decorView)
            if (web == null) latch.countDown()
            else web.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        assertTrue("WebView callback did not arrive", latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }
}
