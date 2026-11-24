package com.linguareader.app

import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import com.linguareader.app.data.VocabularyRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Clicks a real WebView word, selects the bundled dictionary phrase, and saves through AppViewModel. */
@RunWith(AndroidJUnit4::class)
class PhraseSaveFeedbackInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun phraseSaveFeedbackMatchesTheStoredAndReviewEntry() = phraseWorkflow("in fact", "in", false)

    @Test fun primaryPhraseOffersAnIndependentWordSelection() = phraseWorkflow("look up", "up", true)

    private fun phraseWorkflow(phrase: String, word: String, primaryPhrase: Boolean) {
        val vocabulary = File(context.filesDir, "vocabulary.json")
        val previous = vocabulary.takeIf { it.isFile }?.readBytes()
        val root = File(context.filesDir, "books/phrase-feedback-fixture-$word")
        try {
            vocabulary.delete()
            root.mkdirs()
            File(root, "chapter.xhtml").writeText("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"UTF-8\"/></head><body>" +
                (1..40).joinToString("") { "<p>" + (phrase + " ").repeat(70) + ".</p>" } + "</body></html>")
            val book = Book("phrase-feedback-fixture-$word", "Phrase feedback fixture $word", "Test fixture", root.absolutePath,
                null, listOf(Chapter("Phrase", "chapter.xhtml")), addedAt = System.currentTimeMillis())
            File(root, "metadata.json").writeText(book.toJson().toString())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()
                repeat(3) { listOf(R.string.launch_greeting_start, R.string.common_got_it).forEach { key ->
                    val text = context.getString(key)
                    if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) {
                        compose.onNodeWithText(text).performClick(); compose.waitForIdle()
                    }
                } }
                compose.waitUntil(15_000) { compose.onAllNodesWithText(book.title).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(book.title).performClick()
                compose.waitForIdle()
                tapVisibleWord(scenario, word)
                if (primaryPhrase) {
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithText(context.getString(R.string.reader_back_to_word)).fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithText(context.getString(R.string.reader_back_to_word)).performClick()
                    compose.onNode(hasText(word) and hasAnyAncestor(hasTestTag(UiTags.READER_LOOKUP_SHEET))).assertIsDisplayed()
                    compose.onNodeWithText(context.getString(R.string.reader_add_to_vocab)).performClick()
                    try {
                        compose.waitUntil(10_000) { runBlocking { VocabularyRepository(context).load().any { it.id == word } } }
                    } catch (failure: Throwable) {
                        val evidence = File(context.getExternalFilesDir(null), "phrase-feedback").apply { mkdirs() }
                        File(evidence, "word-selection-failure.json").writeText(JSONObject().put("expected", word)
                            .put("saved", runBlocking { VocabularyRepository(context).load().map { it.id } }).toString())
                        screenshot("word-selection-failure.png")
                        throw failure
                    }
                } else compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(context.getString(R.string.reader_related_phrase, phrase)).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(context.getString(R.string.reader_view)).performClick()
                compose.onNodeWithText(context.getString(R.string.reader_add_to_vocab)).performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasText(context.getString(R.string.notice_word_saved, phrase)) and
                        hasAnyAncestor(hasTestTag(UiTags.READER_LOOKUP_SHEET))).fetchSemanticsNodes().isNotEmpty()
                }
                inlineFeedback(phrase).assertIsDisplayed()
                val saved = runBlocking { VocabularyRepository(context).load().single { it.id == phrase } }
                assertEquals(phrase, saved.id)
                assertEquals(phrase, saved.headword)
                assertTrue("The clicked word must not become a phrase surface", saved.surfaceForms.none { it.equals(word, true) })
                screenshot("phrase-saved-$word.png")
                compose.onNodeWithText(context.getString(R.string.reader_back_to_word)).performClick()
                inlineFeedback(phrase).assertDoesNotExist()
                compose.onNodeWithText(context.getString(if (primaryPhrase) R.string.reader_remove_from_vocab else R.string.reader_add_to_vocab)).assertIsDisplayed()
                if (primaryPhrase) assertTrue(runBlocking { VocabularyRepository(context).load().any { it.id == word } })
                screenshot("word-view-$word.png")
                compose.onNodeWithText(context.getString(R.string.reader_view)).performClick()
                compose.onNodeWithText(context.getString(R.string.common_review)).performClick()
                compose.onNodeWithText(phrase).assertIsDisplayed()
                screenshot("phrase-review-$word.png")
            }
        } finally {
            root.deleteRecursively()
            if (previous == null) vocabulary.delete() else vocabulary.writeBytes(previous)
        }
    }

    private fun inlineFeedback(phrase: String) = compose.onNode(hasText(context.getString(R.string.notice_word_saved, phrase)) and
        hasAnyAncestor(hasTestTag(UiTags.READER_LOOKUP_SHEET)))

    private fun tapVisibleWord(scenario: ActivityScenario<MainActivity>, word: String) {
        var point: Pair<Int, Int>? = null
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (point == null && android.os.SystemClock.uptimeMillis() < deadline) {
          val latch = CountDownLatch(1)
          scenario.onActivity { activity ->
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val web = find(activity.window.decorView)
            if (web == null) { latch.countDown(); return@onActivity }
            val origin = IntArray(2).also(web::getLocationOnScreen)
            val scale = web.scale
            web.evaluateJavascript("""(() => {
                if (typeof window.lrLookupAt !== 'function') return null;
                const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                while (walker.nextNode()) {
                    const node = walker.currentNode;
                    if (node.parentElement.closest('script,style')) continue;
                    const matches = node.textContent.matchAll(new RegExp("\\b" + ${JSONObject.quote(word)} + "\\b", "g"));
                    for (const match of matches) {
                        const range = document.createRange(); range.setStart(node,match.index); range.setEnd(node,match.index+${word.length});
                        const r = range.getBoundingClientRect();
                        if (r.width > 0 && r.left >= 0 && r.right <= innerWidth && r.top > 90 && r.bottom < innerHeight-160) {
                            const x = r.left+r.width/2, y = r.top+r.height/2;
                            const hit = window.lrLookupAt(x,y,false);
                            if (hit && hit.word.toLowerCase() === ${JSONObject.quote(word)})
                                return JSON.stringify({x:x,y:y});
                        }
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
          assertTrue("WebView lookup coordinates did not arrive", latch.await(10, TimeUnit.SECONDS))
          if (point == null) android.os.SystemClock.sleep(200)
        }
        val (x, y) = requireNotNull(point) { "No visible target token" }
        instrumentation.uiAutomation.executeShellCommand("input tap $x $y").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    private fun screenshot(name: String) {
        val out = File(context.getExternalFilesDir(null), "phrase-feedback").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(out, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
