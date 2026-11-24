package com.linguareader.app.reader

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.app.MainActivity
import com.linguareader.app.R
import com.linguareader.app.UiTags
import com.linguareader.app.data.AndroidAppContext
import com.linguareader.shared.data.*
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

/** Real word taps and lookup actions, persisted IDs/forms, recreation, actual WebView marks. */
@RunWith(AndroidJUnit4::class)
class SavedWordWorkflowInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val cases = listOf("app" to "app", "studied" to "study", "making" to "make", "running" to "run", "went" to "go")

    private fun fixture(old: SavedWord? = null, action: (ActivityScenario<MainActivity>, VocabularyRepository) -> Unit) {
        assertTrue("Isolated verify build required", context.packageName.endsWith(".verify"))
        val file = File(context.filesDir, "vocabulary.json")
        val previous = file.takeIf(File::isFile)?.readBytes()
        val preferences = context.getSharedPreferences("review_settings", Context.MODE_PRIVATE)
        val previousReminders = preferences.getString(ReviewReminders.STORAGE_KEY, null)
        val root = File(context.filesDir, "books/saved-word-workflow-fixture")
        val store = VocabularyRepository(AndroidAppContext(context))
        try {
            file.delete()
            preferences.edit().putString(ReviewReminders.STORAGE_KEY,
                ReviewReminders(contextHighlight = true, pausePrompt = false, toolbarBadge = false, notifications = false).toJson()).commit()
            root.mkdirs()
            val body = "<p id='app'>app APP apple application app<em>le</em>.</p>" + cases.drop(1).joinToString("") { (surface, _) ->
                "<p id='$surface'>$surface <em>${surface.uppercase()}</em>.</p>"
            }
            File(root, "chapter.xhtml").writeText("<html xmlns='http://www.w3.org/1999/xhtml'><head><meta charset='UTF-8'/><meta name='viewport' content='width=device-width,initial-scale=1'/></head><body>$body</body></html>")
            val book = Book("saved-word-workflow-fixture", "Saved forms workflow fixture", "Test", root.absolutePath,
                null, listOf(Chapter("Forms", "chapter.xhtml")), System.currentTimeMillis())
            File(root, "metadata.json").writeText(book.toJson().toString())
            if (old != null) runBlocking { store.upsert(old) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()
                repeat(3) { listOf(R.string.launch_greeting_start, R.string.common_got_it).forEach { key ->
                    val label = context.getString(key)
                    if (compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()) {
                        compose.onNodeWithText(label).performClick(); compose.waitForIdle()
                    }
                } }
                compose.waitUntil(15_000) { compose.onAllNodesWithText(book.title).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(book.title).performClick()
                ready(scenario)
                action(scenario, store)
            }
        } finally {
            root.deleteRecursively()
            if (previous == null) file.delete() else file.writeBytes(previous)
            preferences.edit().apply {
                if (previousReminders == null) remove(ReviewReminders.STORAGE_KEY) else putString(ReviewReminders.STORAGE_KEY, previousReminders)
            }.commit()
        }
    }

    @Test fun b01T02T08RealLookupSaveRecreateAndDeleteKeepWholeSurfaceMarks() = fixture { scenario, store ->
        val original = snapshot(scenario, "workflow-before")
        for ((surface, lemma) in cases) {
            tap(scenario, surface)
            clickAction(R.string.reader_add_to_vocab)
            compose.waitUntil(5_000) { runBlocking { store.load().any { it.id == lemma } } }
            val saved = runBlocking { store.load().first { it.id == lemma } }
            assertEquals(lemma, saved.headword)
            assertEquals(if (surface == lemma) emptyList<String>() else listOf(surface), saved.surfaceForms)
            dismissLookup()
        }
        assertMarks(snapshot(scenario, "workflow-saved"), original, present = true)
        scenario.recreate()
        ready(scenario)
        assertMarks(snapshot(scenario, "workflow-recreated"), original, present = true)
        for ((surface, lemma) in cases) {
            tap(scenario, surface)
            clickAction(R.string.reader_remove_from_vocab)
            compose.waitUntil(5_000) { runBlocking { store.load().none { it.id == lemma } } }
            dismissLookup()
        }
        assertMarks(snapshot(scenario, "workflow-removed"), original, present = false)
    }

    @Test fun t08AlreadySavedOldLemmaLearnsARealIrregularSurfaceWithoutResettingReview() {
        val old = SavedWord(id = "go", headword = "go", phonetic = "", meaning = "old meaning", sentence = "old context",
            bookId = "saved-word-workflow-fixture", bookTitle = "Old book", chapterTitle = "Old chapter", addedAt = 1L,
            reviewLevel = 4, reviewCount = 7, nextReviewAt = Long.MAX_VALUE, surfaceForms = emptyList())
        fixture(old) { scenario, store ->
            tap(scenario, "went")
            awaitAction(R.string.reader_remove_from_vocab)
            snapshot(scenario, "t08-old-lemma-before-observation")
            compose.waitUntil(5_000) { runBlocking { store.load().single().surfaceForms.contains("went") } }
            val learned = runBlocking { store.load().single() }
            assertEquals(old.headword, learned.headword)
            assertEquals(old.meaning, learned.meaning)
            assertEquals(old.sentence, learned.sentence)
            assertEquals(old.addedAt, learned.addedAt)
            assertEquals(old.reviewLevel, learned.reviewLevel)
            assertEquals(old.reviewCount, learned.reviewCount)
            assertEquals(old.nextReviewAt, learned.nextReviewAt)
            dismissLookup()
            scenario.recreate()
            ready(scenario)
            val r = snapshot(scenario, "t08-old-lemma-learned")
            assertEquals(listOf("went", "WENT"), marks(r, "went"))
        }
    }

    private fun assertMarks(actual: JSONObject, original: JSONObject, present: Boolean) {
        for ((surface, _) in cases) {
            assertEquals(original.getJSONObject(surface).getString("text"), actual.getJSONObject(surface).getString("text"))
            assertEquals(if (present) listOf(surface, surface.uppercase()) else emptyList<String>(), marks(actual, surface))
        }
    }
    private fun marks(rows: JSONObject, id: String) = rows.getJSONObject(id).getJSONArray("marks").let { a -> (0 until a.length()).map(a::getString) }
    private val pane get() = hasAnyAncestor(hasTestTag(UiTags.READER_LOOKUP_SHEET))
    private fun awaitAction(resource: Int) {
        val selector = hasText(context.getString(resource)) and isEnabled() and pane
        compose.waitUntil(10_000) { compose.onAllNodes(selector).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun clickAction(resource: Int) { awaitAction(resource); compose.onNode(hasText(context.getString(resource)) and isEnabled() and pane).performClick() }
    private fun dismissLookup() {
        instrumentation.uiAutomation.executeShellCommand("input keyevent 4").use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.READER_LOOKUP_SHEET).fetchSemanticsNodes().isEmpty() }
    }
    private fun ready(scenario: ActivityScenario<MainActivity>) {
        compose.waitUntil(15_000) { runCatching { js(scenario, "typeof window.lrLookupAt === 'function' && !!document.getElementById('went')") == true }.getOrDefault(false) }
    }
    private fun tap(scenario: ActivityScenario<MainActivity>, word: String) {
        var point: Pair<Int, Int>? = null
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (point == null && SystemClock.uptimeMillis() < deadline) {
            val done = CountDownLatch(1)
            scenario.onActivity { activity ->
                val web = find(activity.window.decorView)!!
                val origin = IntArray(2).also(web::getLocationOnScreen)
                val scale = web.scale
                web.evaluateJavascript("""
                    (() => {
                      const wanted = ${JSONObject.quote(word)};
                      const walker = document.createTreeWalker(document.getElementById(wanted), NodeFilter.SHOW_TEXT);
                      const re = new RegExp('\\b' + wanted + '\\b', 'i');
                      while (walker.nextNode()) {
                        const node = walker.currentNode, match = re.exec(node.textContent);
                        if (!match) continue;
                        const range = document.createRange(); range.setStart(node, match.index); range.setEnd(node, match.index + match[0].length);
                        const r = range.getBoundingClientRect();
                        if (r.width > 0 && r.top > 90 && r.bottom < innerHeight - 100)
                          return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});
                      }
                      return null;
                    })()
                """.trimIndent()) { raw ->
                    val value = JSONTokener(raw ?: "null").nextValue()
                    if (value is String) JSONObject(value).let { point = (origin[0] + it.getDouble("x") * scale).toInt() to (origin[1] + it.getDouble("y") * scale).toInt() }
                    done.countDown()
                }
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            if (point == null) SystemClock.sleep(100)
        }
        val (x, y) = requireNotNull(point) { "No visible fixture token $word" }
        instrumentation.uiAutomation.executeShellCommand("input tap $x $y").use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }
    private fun snapshot(scenario: ActivityScenario<MainActivity>, label: String): JSONObject {
        val rows = JSONObject(js(scenario, """
            JSON.stringify(Object.fromEntries(['app','studied','making','running','went'].map(id => {
              const root = document.getElementById(id), spans = Array.from(root.querySelectorAll('.lr-saved-word,.lr-due-word'));
              return [id,{text:root.textContent,marks:spans.map(x=>x.textContent),nested:spans.filter(x=>x.querySelector('.lr-saved-word,.lr-due-word')).length}];
            })))
        """.trimIndent()) as String)
        cases.forEach { assertEquals(0, rows.getJSONObject(it.first).getInt("nested")) }
        val out = File(context.filesDir, "word-mark-validation").apply { mkdirs() }
        val stored = runBlocking { VocabularyRepository(AndroidAppContext(context)).load() }
        out.resolve("$label.json").writeText(JSONObject().put("rows", rows).put("stored", org.json.JSONArray().apply { stored.forEach { put(it.toJson()) } }).toString(2))
        instrumentation.waitForIdleSync(); SystemClock.sleep(150)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        out.resolve("$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        return rows
    }
    private fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
    private fun js(scenario: ActivityScenario<MainActivity>, script: String): Any? {
        val done = CountDownLatch(1); var value: Any? = null
        scenario.onActivity { activity ->
            val web = find(activity.window.decorView)
            if (web == null) { done.countDown(); return@onActivity }
            web.evaluateJavascript(script) { value = JSONTokener(it ?: "null").nextValue(); done.countDown() }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return value
    }
}
