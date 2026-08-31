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
        // 去重键必须与真正注入的那份一致：过去用全量算键、只注入前 300 个，
        // 第 300 名之后的变动会触发一次毫无效果的整章重排。
        val payload = words.distinct().take(ReaderScripts.MAX_SAVED_WORD_FORMS)
        val key = payload.sorted().joinToString("\u0000")
        if (key == lastSavedWords) return
        lastSavedWords = key
        webView.get()?.evaluateJavascript(ReaderScripts.savedWordsScript(payload), null)
    }

    fun applyPreferences(preferences: ReaderPreferences) {
        val script = ReaderScripts.preferenceScript(preferences)
        webView.get()?.evaluateJavascript(script, null)
    }

    private var lastChromeInsets: Pair<Int, Int>? = null

    /**
     * Pushes the measured Compose chrome heights (CSS px) into the reader JS
     * so the pagination box reserves exactly the space the bars occupy.
     * Skips no-op updates to avoid pointless chapter repagination.
     */
    fun applyChromeInsets(topPx: Int, bottomPx: Int) {
        val key = topPx to bottomPx
        if (key == lastChromeInsets) return
        lastChromeInsets = key
        webView.get()?.evaluateJavascript(
            "window.lrSetChromeInsets && window.lrSetChromeInsets($topPx, $bottomPx)",
            null
        )
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

    /** Fallback: highlights the first text occurrence in the current chapter. */
    fun highlightSentence(text: String) {
        val encoded = JSONObject.quote(text)
        webView.get()?.evaluateJavascript(
            "window.lrHighlightSentence && window.lrHighlightSentence($encoded)",
            null
        )
    }

    /** Highlights the exact sentence at [blockIndex]/[offset] (see TtsChapter). */
    fun highlightBlock(blockIndex: Int, offset: Int, length: Int) {
        if (blockIndex < 0 || length <= 0) return
        webView.get()?.evaluateJavascript(
            "window.lrHighlightBlock && window.lrHighlightBlock($blockIndex, $offset, $length)",
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
