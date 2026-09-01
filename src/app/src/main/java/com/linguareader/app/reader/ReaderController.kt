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
     * 读取当前阅读位置的语义锚点 (块下标, 块内字符偏移)。
     *
     * 返回**下标**而不是块文本：正文里重复段落很常见，按文本匹配会指到别的块
     * 去（旧的 lrFirstVisibleBlock 就栽在这里，已随「翻页拽动朗读」一起删除）。
     * 位置落盘走这条。
     */
    fun readLocus(callback: (Int, Int) -> Unit) {
        val script = """
            (function() {
              return window.lrLocusHere ? window.lrLocusHere() : null;
            })()
        """.trimIndent()
        webView.get()?.evaluateJavascript(script) { value ->
            val decoded = runCatching { JSONTokener(value ?: "null").nextValue() }.getOrNull()
            val json = when (decoded) {
                is String -> runCatching { JSONObject(decoded) }.getOrNull()
                is JSONObject -> decoded
                else -> null
            } ?: return@evaluateJavascript
            val block = json.optInt("blockIndex", -1)
            if (block < 0) return@evaluateJavascript
            callback(block, json.optInt("charOffset", 0).coerceAtLeast(0))
        }
    }

    /** 把视口挪到锚点处。[anchor] 见 [ReaderScripts.ANCHOR_EXACT] 等常量。 */
    fun scrollToLocus(blockIndex: Int, charOffset: Int, anchor: String) {
        val encoded = JSONObject.quote(anchor)
        webView.get()?.evaluateJavascript(
            "window.lrScrollToLocus && window.lrScrollToLocus($blockIndex, $charOffset, $encoded)",
            null
        )
    }

    /**
     * 把视口挪回正在朗读的那一句（听书条的「回到朗读处」）。
     *
     * 与 [scrollToLocus] 的区别只在因果：这条同时结束 JS 侧的用户接管窗口
     * （[ReaderScripts.FOLLOW_TAKEOVER_MS]），于是滚动模式的自动跟随立刻复活。
     * 调用方要负责压掉这次落位引发的位置回报，否则引擎会被拉到该块首句。
     */
    fun backToSpeaking(blockIndex: Int, charOffset: Int) {
        if (blockIndex < 0) return
        webView.get()?.evaluateJavascript(
            "window.lrBackToSpeaking && window.lrBackToSpeaking($blockIndex, ${charOffset.coerceAtLeast(0)})",
            null
        )
    }

}
