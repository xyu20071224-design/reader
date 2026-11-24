package com.linguareader.app

import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
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

    @Test fun phraseSaveFeedbackMatchesTheStoredAndReviewEntry() {
        val vocabulary = File(context.filesDir, "vocabulary.json")
        val previous = vocabulary.takeIf { it.isFile }?.readBytes()
        val root = File(context.filesDir, "books/phrase-feedback-fixture")
        try {
            vocabulary.delete()
            root.mkdirs()
            File(root, "chapter.xhtml").writeText("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"UTF-8\"/></head><body>" +
                (1..40).joinToString("") { "<p>" + "in fact ".repeat(70) + ".</p>" } + "</body></html>")
            val book = Book("phrase-feedback-fixture", "Phrase feedback fixture", "Test fixture", root.absolutePath,
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
                tapVisibleIn(scenario)
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(context.getString(R.string.reader_related_phrase, "in fact")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(context.getString(R.string.reader_view)).performClick()
                compose.onNodeWithText(context.getString(R.string.reader_add_to_vocab)).performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(context.getString(R.string.notice_word_saved, "in fact")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(context.getString(R.string.notice_word_saved, "in fact")).assertIsDisplayed()
                val saved = runBlocking { VocabularyRepository(context).load().single() }
                assertEquals("in fact", saved.id)
                assertEquals("in fact", saved.headword)
                screenshot("phrase-saved.png")
                compose.onNodeWithText(context.getString(R.string.reader_back_to_word)).performClick()
                compose.onNodeWithText(context.getString(R.string.notice_word_saved, "in fact")).assertDoesNotExist()
                compose.onNodeWithText(context.getString(R.string.reader_add_to_vocab)).assertIsDisplayed()
                screenshot("word-view.png")
                compose.onNodeWithText(context.getString(R.string.reader_view)).performClick()
                compose.onNodeWithText(context.getString(R.string.common_review)).performClick()
                compose.onNodeWithText("in fact").assertIsDisplayed()
                screenshot("phrase-review.png")
            }
        } finally {
            root.deleteRecursively()
            if (previous == null) vocabulary.delete() else vocabulary.writeBytes(previous)
        }
    }

    private fun tapVisibleIn(scenario: ActivityScenario<MainActivity>) {
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
                const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                while (walker.nextNode()) {
                    const node = walker.currentNode;
                    if (node.parentElement.closest('script,style')) continue;
                    const matches = node.textContent.matchAll(/\bin\b/g);
                    for (const match of matches) {
                        const range = document.createRange(); range.setStart(node,match.index); range.setEnd(node,match.index+2);
                        const r = range.getBoundingClientRect();
                        if (r.width > 0 && r.top > 90 && r.bottom < innerHeight-160)
                            return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});
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
        val (x, y) = requireNotNull(point) { "No visible in token" }
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
