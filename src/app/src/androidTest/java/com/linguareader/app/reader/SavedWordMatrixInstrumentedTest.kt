package com.linguareader.app.reader

import android.os.Build
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.shared.data.ReaderPreferences
import com.linguareader.shared.data.ReaderTheme
import com.linguareader.shared.reader.SavedWordMarks
import org.json.JSONArray
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
import java.util.concurrent.atomic.AtomicInteger

/** Runs production EpubPage -> ReaderController -> current generated ReaderCoreJs in WebView. */
@RunWith(AndroidJUnit4::class)
class SavedWordMatrixInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val controller = ReaderController()
    private val ready = AtomicInteger()
    private var marks by mutableStateOf(SavedWordMarks(emptyList(), emptyList()))
    private var preferences by mutableStateOf(ReaderPreferences())
    private var chapter by mutableStateOf<File?>(null)

    private fun start(body: String, suffix: String = ".html") {
        val namespace = if (suffix == ".xhtml") " xmlns='http://www.w3.org/1999/xhtml'" else ""
        val file = File(context.cacheDir, "word-mark-matrix-${System.nanoTime()}$suffix").apply {
            writeText("<html$namespace><head><meta charset='UTF-8'/><meta name='viewport' content='width=device-width,initial-scale=1'/></head><body>$body</body></html>")
        }
        chapter = file
        compose.setContent {
            val current = chapter ?: return@setContent
            key(current.absolutePath) {
                EpubPage(
                    chapterFile = current, initialPage = 0, preferences = preferences,
                    savedWords = marks.forms, dueWords = marks.dueForms,
                    chromeTopPx = 0, chromeBottomPx = 0, controller = controller,
                    modifier = Modifier.fillMaxSize(), onReady = { _, _ -> ready.incrementAndGet() },
                    onPageChanged = { _, _, _ -> }, onChapterRequested = {}, onWord = {}, onToolbarRequested = {}
                )
            }
        }
        compose.waitUntil(15_000) { ready.get() > 0 }
        assertEquals("function", js("typeof window.lrRefreshSavedWords"))
    }

    private fun find(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun js(script: String): Any? {
        val done = CountDownLatch(1)
        var value: Any? = null
        compose.runOnUiThread {
            requireNotNull(find(compose.activity.window.decorView)).evaluateJavascript(script) {
                value = JSONTokener(it ?: "null").nextValue(); done.countDown()
            }
        }
        assertTrue("WebView callback timed out", done.await(5, TimeUnit.SECONDS))
        return value
    }

    private fun set(words: List<String>, due: List<String> = emptyList()) {
        compose.runOnIdle { marks = SavedWordMarks(words, due) }
        // FIFO barrier after the real AndroidView update and evaluateJavascript injection.
        compose.waitForIdle()
        js("true")
    }

    private fun snapshot(label: String, ids: List<String>): JSONObject {
        val encoded = JSONArray(ids).toString()
        val rows = JSONObject(js("""
            JSON.stringify((function() {
              const rows = {};
              $encoded.forEach(function(id) {
                const root = document.getElementById(id);
                const marks = Array.from(root.querySelectorAll('.lr-saved-word,.lr-due-word'));
                rows[id] = {
                  text: root.textContent,
                  saved: marks.filter(x => x.className === 'lr-saved-word').map(x => x.textContent),
                  due: marks.filter(x => x.className === 'lr-due-word').map(x => x.textContent),
                  styles: marks.map(x => ({kind: x.className, line: getComputedStyle(x).textDecorationStyle,
                    thickness: getComputedStyle(x).textDecorationThickness, color: getComputedStyle(x).textDecorationColor})),
                  nested: marks.filter(x => x.querySelector('.lr-saved-word,.lr-due-word')).length,
                  emphasis: Array.from(root.querySelectorAll('em')).map(x => x.textContent)
                };
              });
              return rows;
            })())
        """.trimIndent()) as String)
        val record = JSONObject().put("api", Build.VERSION.SDK_INT).put("label", label).put("rows", rows)
        record.put("documentContentType", js("document.contentType"))
        record.put("nodeNames", JSONObject(js("JSON.stringify(Object.fromEntries($encoded.map(id => {const e=document.getElementById(id);return [id,{tagName:e.tagName,localName:e.localName}]})))") as String))
        if (Build.VERSION.SDK_INT >= 26) record.put("webView", WebView.getCurrentWebViewPackage()?.versionName)
        File(context.filesDir, "word-mark-validation").apply { mkdirs() }
            .resolve("$label.json").writeText(record.toString(2))
        if (label in setOf("t02-plain", "t02-due", "t07-inline", "t11-night-large", "t11-overlap", "b01-xhtml")) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(150)
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(context.filesDir, "word-mark-validation/$label.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
        ids.forEach { assertEquals("Nested mark in $it", 0, rows.getJSONObject(it).getInt("nested")) }
        return rows
    }

    private fun texts(rows: JSONObject, id: String, kind: String = "saved"): List<String> =
        rows.getJSONObject(id).getJSONArray(kind).let { a -> (0 until a.length()).map(a::getString) }

    private fun inlineWordLookup(): JSONObject = JSONObject(js("""
        JSON.stringify((function() {
          const element = document.querySelector('#phrase em');
          const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
          const node = walker.nextNode();
          const range = document.createRange(); range.selectNodeContents(node);
          const r = range.getBoundingClientRect();
          return window.lrLookupAt(r.left + r.width / 2, r.top + r.height / 2, false);
        })())
    """.trimIndent()) as String)

    @Test fun b01WholeWordBoundaryUsesTheCompleteInlineText() {
        start("<p id='positive'>app APP app,\napp.</p><p id='negative'>apple application app<em>le</em></p>")
        set(listOf("app"))
        val r = snapshot("b01-boundary", listOf("positive", "negative"))
        assertEquals(listOf("app", "APP", "app", "app"), texts(r, "positive"))
        assertEquals(emptyList<String>(), texts(r, "negative"))
        assertEquals("apple application apple", r.getJSONObject("negative").getString("text"))
        assertEquals(listOf("le"), r.getJSONObject("negative").getJSONArray("emphasis").let { (0 until it.length()).map(it::getString) })
    }

    @Test fun t02RegularFormsKeepFullSpellingAndDueStyle() {
        start("<p id='forms'>run RUNNING stop stopped study studies studied make making</p><p id='negative'>runway unstoppable understudy remake</p>")
        val words = listOf("run", "stop", "study", "make")
        set(words)
        val plain = snapshot("t02-plain", listOf("forms", "negative"))
        val expected = "run RUNNING stop stopped study studies studied make making".split(' ')
        assertEquals(expected, texts(plain, "forms"))
        assertEquals(emptyList<String>(), texts(plain, "negative"))
        set(words, words)
        val due = snapshot("t02-due", listOf("forms", "negative"))
        assertEquals(expected, texts(due, "forms", "due"))
        assertEquals(emptyList<String>(), texts(due, "negative", "due"))
    }

    @Test fun t07WordThenPhraseSelectionDoesNotLoseThePhrase() {
        start("<p id='phrase'>give up</p>")
        set(listOf("give"))
        assertEquals(listOf("give"), texts(snapshot("t07-word", listOf("phrase")), "phrase"))
        set(listOf("give up"))
        assertEquals(listOf("give up"), texts(snapshot("t07-after-word", listOf("phrase")), "phrase"))
        set(listOf("give", "give up"))
        assertEquals(listOf("give up"), texts(snapshot("t07-overlap", listOf("phrase")), "phrase"))
        set(listOf("give"))
        assertEquals(listOf("give"), texts(snapshot("t07-phrase-removed", listOf("phrase")), "phrase"))
    }

    @Test fun t07PhraseAcrossInlineMarkupPreservesFormattingAndTextOffsets() {
        start("<p id='phrase'>Please give <em>up</em> now.</p><p id='other'>give</p><p id='separate'>up</p><p id='br'>give<br/>up</p>")
        val before = js("document.getElementById('phrase').textContent")
        val lookupBefore = inlineWordLookup()
        assertEquals("up", lookupBefore.getString("word"))
        assertEquals(12, lookupBefore.getInt("blockOffset"))
        set(listOf("give up"))
        val r = snapshot("t07-inline", listOf("phrase", "other", "separate", "br"))
        assertEquals("give up", texts(r, "phrase").joinToString(""))
        assertEquals(before, r.getJSONObject("phrase").getString("text"))
        assertEquals("up", r.getJSONObject("phrase").getJSONArray("emphasis").getString(0))
        assertEquals(emptyList<String>(), texts(r, "other"))
        assertEquals(emptyList<String>(), texts(r, "separate"))
        assertEquals(emptyList<String>(), texts(r, "br"))
        val lookupAfter = inlineWordLookup()
        listOf("word", "sentence", "paragraph", "sentenceOffset", "blockOffset").forEach { key ->
            assertEquals("Marking changed lookup coordinate $key", lookupBefore.get(key), lookupAfter.get(key))
        }
    }

    @Test fun t07WhitespaceEquivalentPhrasesShareTheDueStyle() {
        start("<p id='phrase'>give\n  up GIVE\tUP</p>")
        set(listOf("give up"), listOf("give up"))
        val r = snapshot("t07-whitespace", listOf("phrase"))
        assertEquals(listOf("give\n  up", "GIVE\tUP"), texts(r, "phrase", "due"))
        assertEquals(emptyList<String>(), texts(r, "phrase"))
    }

    @Test fun t08SeenIrregularFormsRemainExactAndCanBeRemoved() {
        start("<p id='forms'>go went gone WENT wagon</p>")
        set(listOf("go", "went"))
        assertEquals(listOf("go", "went", "WENT"), texts(snapshot("t08-seen", listOf("forms")), "forms"))
        set(emptyList())
        assertEquals(emptyList<String>(), texts(snapshot("t08-removed", listOf("forms")), "forms"))
    }

    @Test fun t11TwoStylesRefreshWithoutChangingTextOrSameLayoutAnchor() {
        start("<p id='words'>study studied app</p>" + (1..70).joinToString("") { "<p>Fixed paragraph $it with enough text to paginate in this real WebView.</p>" })
        val locus = js("window.lrLocusHere()")
        for (index in 0 until 12) {
            set(listOf("study", "app"), if (index % 2 == 0) listOf("study") else emptyList())
            val r = snapshot("t11-refresh-$index", listOf("words"))
            assertEquals("study studied app", r.getJSONObject("words").getString("text"))
            assertEquals(locus, js("window.lrLocusHere()"))
        }
        set(listOf("study", "app"), listOf("study"))
        compose.runOnIdle { preferences = preferences.copy(theme = ReaderTheme.DARK, fontPercent = 150) }
        compose.waitForIdle()
        val styles = snapshot("t11-night-large", listOf("words")).getJSONObject("words").getJSONArray("styles")
        assertTrue((0 until styles.length()).any { styles.getJSONObject(it).getString("line") == "solid" })
        assertTrue((0 until styles.length()).any { styles.getJSONObject(it).getString("line") == "dotted" })
        set(emptyList())
        val off = snapshot("t11-off", listOf("words"))
        assertEquals(emptyList<String>(), texts(off, "words"))
        assertEquals(emptyList<String>(), texts(off, "words", "due"))
    }

    @Test fun t11DueWordInsideAnOrdinaryPhraseKeepsAnIndependentSolidUnderline() {
        start("<p id='plain'>give up</p><p id='inline'>give <em>up</em></p>")
        set(listOf("give up", "give"), listOf("give"))
        val r = snapshot("t11-overlap", listOf("plain", "inline"))
        for (id in listOf("plain", "inline")) {
            assertEquals("give", texts(r, id, "due").joinToString(""))
            assertEquals(" up", texts(r, id).joinToString(""))
            assertEquals("give up", r.getJSONObject(id).getString("text"))
        }
        set(listOf("give up", "give"), listOf("give up"))
        val inverse = snapshot("t11-overlap-due-phrase", listOf("plain", "inline"))
        for (id in listOf("plain", "inline")) {
            assertEquals("give up", texts(inverse, id, "due").joinToString(""))
            assertEquals(emptyList<String>(), texts(inverse, id))
        }
    }

    @Test fun b01XhtmlNamespaceKeepsBlockBoundariesAndSkipsScriptAndStyle() {
        start("<p id='app'>app</p><p id='tail'>le</p>" +
            "<p id='give'>give </p><p id='up'>up</p>" +
            "<script type='application/json' id='script'>{\"text\":\"give up\"}</script>" +
            "<style id='style'>.not-present { --fixture: 'give up'; }</style>" +
            "<x:p xmlns:x='http://www.w3.org/1999/xhtml' id='prefixed'>app</x:p>", ".xhtml")
        set(listOf("app", "give up"))
        val r = snapshot("b01-xhtml", listOf("app", "tail", "give", "up", "script", "style", "prefixed"))
        assertEquals(listOf("app"), texts(r, "app"))
        assertEquals(listOf("app"), texts(r, "prefixed"))
        listOf("tail", "give", "up", "script", "style").forEach { id ->
            assertEquals("Unexpected mark across an XHTML block or in excluded content: $id", emptyList<String>(), texts(r, id))
        }
    }
}
