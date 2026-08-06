package com.linguareader.app.reader

import android.webkit.WebView
import com.linguareader.app.data.ReaderPreferences
import java.lang.ref.WeakReference

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
}
