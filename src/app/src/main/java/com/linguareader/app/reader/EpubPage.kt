package com.linguareader.app.reader

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.linguareader.app.data.ReaderPreferences
import com.linguareader.app.data.WordLookup
import java.io.ByteArrayInputStream
import java.io.File

private class ReaderBridge(
    private val webView: WebView,
    private val readyCallback: (Int, Int) -> Unit,
    private val pageChangedCallback: (Int, Int) -> Unit,
    private val chapterRequestedCallback: (Int) -> Unit,
    private val wordCallback: (WordLookup) -> Unit,
    private val toolbarRequestedCallback: () -> Unit
) {
    private fun post(action: () -> Unit): Unit {
        webView.post(action)
    }

    @JavascriptInterface
    fun onReady(page: Int, pageCount: Int): Unit = post {
        readyCallback(page, pageCount)
    }

    @JavascriptInterface
    fun onPageChanged(page: Int, pageCount: Int): Unit = post {
        pageChangedCallback(page, pageCount)
    }

    @JavascriptInterface
    fun onChapterRequested(direction: Int): Unit = post {
        chapterRequestedCallback(direction.coerceIn(-1, 1))
    }

    @JavascriptInterface
    fun onWord(
        word: String,
        sentence: String,
        paragraph: String,
        sentenceOffset: Int,
        x: Float,
        y: Float
    ): Unit = post {
        wordCallback(
            WordLookup(
                word = word.take(120),
                sentence = sentence.take(1_500),
                paragraph = paragraph.take(4_000),
                sentenceOffset = sentenceOffset.coerceIn(0, 1_500),
                x = x,
                y = y
            )
        )
    }

    @JavascriptInterface
    fun onToolbarRequested(): Unit = post(toolbarRequestedCallback)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubPage(
    chapterFile: File,
    initialPage: Int,
    preferences: ReaderPreferences,
    savedWords: List<String> = emptyList(),
    controller: ReaderController,
    modifier: Modifier = Modifier,
    onReady: (Int, Int) -> Unit,
    onPageChanged: (Int, Int) -> Unit,
    onChapterRequested: (Int) -> Unit,
    onWord: (WordLookup) -> Unit,
    onToolbarRequested: () -> Unit
) {
    val latestPreferences by rememberUpdatedState(preferences)
    val latestReady by rememberUpdatedState(onReady)
    val latestPageChanged by rememberUpdatedState(onPageChanged)
    val latestChapterRequested by rememberUpdatedState(onChapterRequested)
    val latestWord by rememberUpdatedState(onWord)
    val latestToolbarRequested by rememberUpdatedState(onToolbarRequested)
    val latestSavedWords by rememberUpdatedState(savedWords)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.parseColor(preferences.theme.background))
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false

                addJavascriptInterface(
                    ReaderBridge(
                        webView = this,
                        readyCallback = { page, count -> latestReady(page, count) },
                        pageChangedCallback = { page, count -> latestPageChanged(page, count) },
                        chapterRequestedCallback = { latestChapterRequested(it) },
                        wordCallback = { latestWord(it) },
                        toolbarRequestedCallback = { latestToolbarRequested() }
                    ),
                    "ReaderBridge"
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(
                            ReaderScripts.bootstrap(initialPage, latestPreferences),
                            null
                        )
                        view.evaluateJavascript(
                            ReaderScripts.savedWordsScript(latestSavedWords),
                            null
                        )
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val scheme = request.url.scheme.orEmpty()
                        return scheme != "file" && scheme != "about"
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        return if (request.url.scheme in listOf("http", "https")) {
                            WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        } else {
                            super.shouldInterceptRequest(view, request)
                        }
                    }
                }
                controller.attach(this)
                loadUrl(Uri.fromFile(chapterFile).toString())
            }
        },
        update = { view ->
            view.setBackgroundColor(Color.parseColor(preferences.theme.background))
            view.evaluateJavascript(ReaderScripts.preferenceScript(preferences), null)
            controller.setSavedWords(latestSavedWords)
        },
        onRelease = { view ->
            controller.detach(view)
            view.removeJavascriptInterface("ReaderBridge")
            view.stopLoading()
            runCatching { view.destroy() }
        }
    )
}
