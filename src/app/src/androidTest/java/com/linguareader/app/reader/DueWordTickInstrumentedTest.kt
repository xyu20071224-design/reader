package com.linguareader.app.reader

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.app.AppViewModel
import com.linguareader.app.MainActivity
import com.linguareader.app.R
import com.linguareader.app.data.AndroidAppContext
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import com.linguareader.shared.data.ReviewReminders
import com.linguareader.shared.data.SavedWord
import com.linguareader.shared.data.VocabularyRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real ReaderScreen clock, real persisted review, real EpubPage; never drives refresh with JS. */
@RunWith(AndroidJUnit4::class)
class DueWordTickInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val target = "qzduefixtureword"
    private val future = "qzfuturefixtureword"

    @Test fun realThirtySecondTickUpdatesDueMarksAndReviewClearsThemWithoutReloadingThePage() {
        assertTrue("This fixture is for the isolated verify application", context.packageName.endsWith(".verify"))
        val vocabulary = File(context.filesDir, "vocabulary.json")
        val previous = vocabulary.takeIf(File::isFile)?.readBytes()
        val settings = context.getSharedPreferences("review_settings", Context.MODE_PRIVATE)
        val previousReminders = settings.getString(ReviewReminders.STORAGE_KEY, null)
        val root = File(context.filesDir, "books/due-word-clock-fixture")
        val repository = VocabularyRepository(AndroidAppContext(context))
        val reminders = ReviewReminders(contextHighlight = true, pausePrompt = false, toolbarBadge = true, notifications = false)
        try {
            vocabulary.delete()
            settings.edit().putString(ReviewReminders.STORAGE_KEY, reminders.toJson()).commit()
            root.mkdirs()
            val body = "<p id='clockwords'>$target ${target}s $future</p>" +
                (1..35).joinToString("") { "<p>Fixed paragraph $it with ordinary fixture text for stable pagination.</p>" }
            File(root, "chapter.html").writeText("<html><head><meta charset='UTF-8'/></head><body>$body</body></html>")
            val book = Book("due-word-clock-fixture", "Due word clock fixture", "Test", root.absolutePath,
                null, listOf(Chapter("Clock", "chapter.html")), System.currentTimeMillis())
            File(root, "metadata.json").writeText(book.toJson().toString())
            fun record(word: String) = SavedWord(id = word, headword = word, phonetic = "", meaning = "fixture",
                sentence = "$target ${target}s $future", bookId = book.id, bookTitle = book.title,
                chapterTitle = "Clock", addedAt = 1L, nextReviewAt = Long.MAX_VALUE)
            runBlocking { repository.upsert(record(target)); repository.upsert(record(future)) }
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
                compose.waitUntil(15_000) { runCatching { snapshot(scenario).getInt("plain") == 3 }.getOrDefault(false) }
                js(scenario, "window.__dueClockDocument = 'same-page'")
                val locus = js(scenario, "window.lrLocusHere()")
                val expiresAt = System.currentTimeMillis() + 2_000L
                runBlocking { repository.upsert(record(target).copy(nextReviewAt = expiresAt)) }
                scenario.onActivity { ViewModelProvider(it)[AppViewModel::class.java].refresh() }
                compose.waitUntil(5_000) {
                    var loaded = false
                    scenario.onActivity { loaded = ViewModelProvider(it)[AppViewModel::class.java].state.value.savedWords
                        .any { word -> word.id == target && word.nextReviewAt == expiresAt } }
                    loaded
                }
                assertEquals("same-page", js(scenario, "window.__dueClockDocument"))
                assertEquals(0, snapshot(scenario).getInt("due"))
                val deadline = SystemClock.uptimeMillis() + 35_000L
                while (snapshot(scenario).getInt("due") != 2 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(200)
                val observedAt = System.currentTimeMillis()
                val due = snapshot(scenario)
                assertEquals("Real ReaderScreen nowTick did not update within 30s + 5s allowance", 2, due.getInt("due"))
                assertEquals(1, due.getInt("plain"))
                assertTrue("Due styling appeared before expiry", observedAt >= expiresAt)
                assertTrue("Due refresh exceeded the declared allowance", observedAt - expiresAt <= 35_000L)
                assertEquals("same-page", js(scenario, "window.__dueClockDocument"))
                assertEquals(locus, js(scenario, "window.lrLocusHere()"))
                var dueIds = emptyList<String>()
                scenario.onActivity { dueIds = ViewModelProvider(it)[AppViewModel::class.java].state.value.savedWords
                    .filter { word -> word.nextReviewAt <= observedAt }.map { word -> word.id } }
                assertEquals(listOf(target), dueIds)
                // Show the real toolbar through its production pointer/bridge path, then check its count.
                val badge = context.resources.getQuantityString(R.plurals.reader_review_badge, 1, 1)
                if (compose.onAllNodesWithText(badge).fetchSemanticsNodes().isEmpty()) js(scenario,
                    "['pointerdown','pointerup'].forEach(t => document.dispatchEvent(new PointerEvent(t,{clientX:innerWidth/2,clientY:4,bubbles:true})))")
                compose.waitUntil(5_000) { compose.onAllNodesWithText(badge).fetchSemanticsNodes().isNotEmpty() }
                scenario.onActivity { ViewModelProvider(it)[AppViewModel::class.java].setReminders(reminders.copy(contextHighlight = false)) }
                compose.waitUntil(5_000) { snapshot(scenario).let { it.getInt("plain") == 0 && it.getInt("due") == 0 } }
                scenario.onActivity { ViewModelProvider(it)[AppViewModel::class.java].setReminders(reminders) }
                compose.waitUntil(5_000) { snapshot(scenario).getInt("due") == 2 }
                var reviewed: Boolean? = null
                scenario.onActivity { ViewModelProvider(it)[AppViewModel::class.java].reviewWord(target, true) { reviewed = it } }
                compose.waitUntil(5_000) { reviewed != null }
                assertEquals(true, reviewed)
                compose.waitUntil(5_000) { snapshot(scenario).let { it.getInt("plain") == 3 && it.getInt("due") == 0 } }
                val stored = runBlocking { repository.load().first { it.id == target } }
                assertEquals(1, stored.reviewCount)
                assertTrue(stored.nextReviewAt > observedAt)
                assertEquals("same-page", js(scenario, "window.__dueClockDocument"))
                val output = JSONObject().put("expiresAt", expiresAt).put("observedAt", observedAt)
                    .put("refreshLagMillis", observedAt - expiresAt).put("dueIds", org.json.JSONArray(dueIds))
                    .put("dueSnapshot", due).put("reviewedSnapshot", snapshot(scenario))
                File(context.filesDir, "word-mark-validation").apply { mkdirs() }.resolve("t11-real-tick.json").writeText(output.toString(2))
            }
        } finally {
            root.deleteRecursively()
            if (previous == null) vocabulary.delete() else vocabulary.writeBytes(previous)
            settings.edit().apply {
                if (previousReminders == null) remove(ReviewReminders.STORAGE_KEY)
                else putString(ReviewReminders.STORAGE_KEY, previousReminders)
            }.commit()
        }
    }

    private fun snapshot(scenario: ActivityScenario<MainActivity>): JSONObject = JSONObject(js(scenario, """
        JSON.stringify((() => {
          const root = document.getElementById('clockwords');
          return { text: root ? root.textContent : '', plain: root ? root.querySelectorAll('.lr-saved-word').length : -1,
            due: root ? root.querySelectorAll('.lr-due-word').length : -1,
            nested: root ? root.querySelectorAll('.lr-saved-word .lr-saved-word,.lr-due-word .lr-due-word').length : -1 };
        })())
    """.trimIndent()) as String).also { assertEquals(0, it.getInt("nested")) }

    private fun js(scenario: ActivityScenario<MainActivity>, script: String): Any? {
        val done = CountDownLatch(1)
        var result: Any? = null
        scenario.onActivity { activity ->
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val web = find(activity.window.decorView)
            if (web == null) { done.countDown(); return@onActivity }
            web.evaluateJavascript(script) { raw -> result = JSONTokener(raw ?: "null").nextValue(); done.countDown() }
        }
        assertTrue("WebView callback timed out", done.await(5, TimeUnit.SECONDS))
        return result
    }
}
