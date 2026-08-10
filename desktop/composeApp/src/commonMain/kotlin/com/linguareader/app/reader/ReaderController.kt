package com.linguareader.app.reader

import com.linguareader.app.data.ReaderPreferences
import java.lang.ref.WeakReference
import org.json.JSONObject
import org.json.JSONTokener

/** Minimal JavaScript executor abstraction over Android WebView / JavaFX WebEngine. */
interface JsEvaluator {
    /** Stable platform object identity used to detach the same engine later. */
    val identity: Any

    fun evaluate(script: String, onResult: ((String?) -> Unit)? = null)
}

class ReaderController {
    private var evaluator = WeakReference<JsEvaluator>(null)
    private var identity: Any? = null

    internal fun attach(view: JsEvaluator) {
        evaluator = WeakReference(view)
        identity = view.identity
    }

    internal fun detach(view: JsEvaluator) {
        if (identity === view.identity) {
            evaluator.clear()
            identity = null
        }
    }

    private fun evaluate(script: String, onResult: ((String?) -> Unit)? = null) {
        evaluator.get()?.evaluate(script, onResult)
    }

    fun nextPage() {
        evaluate("window.lrTurn && window.lrTurn(1)")
    }

    fun previousPage() {
        evaluate("window.lrTurn && window.lrTurn(-1)")
    }

    fun jumpToPage(page: Int) {
        evaluate("window.lrSetPage && window.lrSetPage($page)")
    }

    private var lastSavedWords: String? = null

    fun setSavedWords(words: List<String>) {
        val key = words.distinct().sorted().joinToString("\u0000")
        if (key == lastSavedWords) return
        lastSavedWords = key
        evaluate(ReaderScripts.savedWordsScript(words.distinct().take(300)))
    }

    fun applyPreferences(preferences: ReaderPreferences) {
        evaluate(ReaderScripts.preferenceScript(preferences))
    }

    /** Enables "tap a sentence to play from it" instead of word lookup. */
    fun setListenMode(enabled: Boolean) {
        evaluate("window.lrSetListenMode && window.lrSetListenMode($enabled)")
    }

    /** Highlights [text] in the current chapter and scrolls it into view. */
    fun highlightSentence(text: String) {
        val encoded = JSONObject.quote(text)
        evaluate("window.lrHighlightSentence && window.lrHighlightSentence($encoded)")
    }

    fun clearHighlight() {
        evaluate("window.lrClearHighlight && window.lrClearHighlight()")
    }

    /**
     * Returns the normalized text of the first paragraph visible on the
     * current page; used to start listening from the current reading position
     * and to let the queue follow manual page turns.
     */
    fun firstVisibleBlock(callback: (String?) -> Unit) {
        val script = """
            (function() {
              var block = window.lrFirstVisibleBlock ? window.lrFirstVisibleBlock() : null;
              return block == null ? 'null' : JSON.stringify(block);
            })()
        """.trimIndent()
        evaluate(script) { value ->
            val decoded = runCatching { JSONTokener(value ?: "null").nextValue() }.getOrNull()
            callback(decoded as? String)
        }
    }
}
