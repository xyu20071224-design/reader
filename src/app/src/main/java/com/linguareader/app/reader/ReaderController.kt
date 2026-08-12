package com.linguareader.app.reader

import android.webkit.WebView
import com.linguareader.app.data.ReaderPreferences
import java.lang.ref.WeakReference
import org.json.JSONObject
import org.json.JSONTokener

class ReaderController {
    private var webView = WeakReference<WebView>(null)

    internal fun attach(view: WebView) {
        webView = WeakReference(view)
    }

    internal fun detach(view: WebView) {
        if (webView.get() === view) webView.clear()
    }

    fun nextPage() {
        webView.get()?.evaluateJavascript("window.lrTurn && window.lrTurn(1)", null)
    }

    fun previousPage() {
        webView.get()?.evaluateJavascript("window.lrTurn && window.lrTurn(-1)", null)
    }

    /** Leaves scroll (roller) mode and restores paginated layout at the same position. */
    fun exitScrollMode() {
        webView.get()?.evaluateJavascript("window.lrExitScrollMode && window.lrExitScrollMode()", null)
    }

    fun jumpToPage(page: Int) {
        webView.get()?.evaluateJavascript("window.lrSetPage && window.lrSetPage($page)", null)
    }

    private var lastSavedWords: String? = null

    fun setSavedWords(words: List<String>) {
        val key = words.distinct().sorted().joinToString("\u0000")
        if (key == lastSavedWords) return
        lastSavedWords = key
        webView.get()?.evaluateJavascript(ReaderScripts.savedWordsScript(words.distinct().take(300)), null)
    }

    fun applyPreferences(preferences: ReaderPreferences) {
        val script = ReaderScripts.preferenceScript(preferences)
        webView.get()?.evaluateJavascript(script, null)
    }

    /**
     * Enables "choose start point" mode: while enabled, the next text tap is
     * consumed as the playback start sentence instead of opening a lookup.
     */
    fun setChoosingStart(enabled: Boolean) {
        webView.get()?.evaluateJavascript(
            "window.lrSetChoosingStart && window.lrSetChoosingStart($enabled)",
            null
        )
    }

    /** Highlights [text] in the current chapter and scrolls it into view. */
    fun highlightSentence(text: String) {
        val encoded = JSONObject.quote(text)
        webView.get()?.evaluateJavascript(
            "window.lrHighlightSentence && window.lrHighlightSentence($encoded)",
            null
        )
    }

    fun clearHighlight() {
        webView.get()?.evaluateJavascript(
            "window.lrClearHighlight && window.lrClearHighlight()",
            null
        )
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
        webView.get()?.evaluateJavascript(script) { value ->
            val decoded = runCatching { JSONTokener(value ?: "null").nextValue() }.getOrNull()
            callback(decoded as? String)
        }
    }
}
