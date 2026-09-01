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
import com.linguareader.app.R
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
    private val pageChangedCallback: (Int, Int, LocusOrigin) -> Unit,
    private val chapterRequestedCallback: (Int) -> Unit,
    private val wordCallback: (WordLookup) -> Unit,
    private val toolbarRequestedCallback: () -> Unit,
    private val sentenceTappedCallback: (String, Int) -> Unit,
    private val scrollModeChangedCallback: (Boolean) -> Unit,
    private val scrollProgressCallback: (Float, Int, Int) -> Unit,
    private val speakingOffscreenCallback: (Boolean) -> Unit
) {
    private fun post(action: () -> Unit): Unit {
        webView.post(action)
    }

    @JavascriptInterface
    fun onReady(page: Int, pageCount: Int): Unit = post {
        readyCallback(page, pageCount)
    }

    @JavascriptInterface
    fun onPageChanged(page: Int, pageCount: Int, origin: String): Unit = post {
        pageChangedCallback(page, pageCount, LocusOrigin.of(origin))
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

    @JavascriptInterface
    fun onSentenceTapped(block: String, blockOffset: Int): Unit = post {
        sentenceTappedCallback(block.take(20_000), blockOffset.coerceIn(0, 20_000))
    }

    @JavascriptInterface
    fun onScrollModeChanged(active: Boolean): Unit = post {
        scrollModeChangedCallback(active)
    }

    /** 朗读句是否已离开视口（用户接管期间不硬翻页，改在听书条上提示）。 */
    @JavascriptInterface
    fun onSpeakingOffscreen(offscreen: Boolean): Unit = post {
        speakingOffscreenCallback(offscreen)
    }

    @JavascriptInterface
    fun onScrollProgress(progress: Float, page: Int, pageCount: Int): Unit = post {
        scrollProgressCallback(
            progress.coerceIn(0f, 1f),
            page.coerceAtLeast(0),
            pageCount.coerceAtLeast(1)
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubPage(
    chapterFile: File,
    initialPage: Int,
    /** 语义锚点：给了就由它决定落位，[initialPage] 只作迁移期兜底。 */
    initialLocusBlock: Int = ReaderScripts.NO_LOCUS_BLOCK,
    initialLocusOffset: Int = 0,
    initialLocusAnchor: String = ReaderScripts.ANCHOR_EXACT,
    initialScrollMode: Boolean = false,
    initialScrollRatio: Float = 0f,
    initialScrollPageCount: Int = 1,
    preferences: ReaderPreferences,
    savedWords: List<String> = emptyList(),
    chromeTopPx: Int = ReaderScripts.DEFAULT_CHROME_TOP_PX,
    chromeBottomPx: Int = ReaderScripts.DEFAULT_CHROME_BOTTOM_PX,
    controller: ReaderController,
    modifier: Modifier = Modifier,
    onReady: (Int, Int) -> Unit,
    onPageChanged: (Int, Int, LocusOrigin) -> Unit,
    onChapterRequested: (Int) -> Unit,
    onWord: (WordLookup) -> Unit,
    onToolbarRequested: () -> Unit,
    onSentenceTapped: (String, Int) -> Unit = { _, _ -> },
    onScrollModeChanged: (Boolean) -> Unit = {},
    onScrollProgress: (Float, Int, Int) -> Unit = { _, _, _ -> },
    /** 朗读句离屏/回到视口（用户接管期间的主动提示）。 */
    onSpeakingOffscreen: (Boolean) -> Unit = {}
) {
    val latestPreferences by rememberUpdatedState(preferences)
    val latestReady by rememberUpdatedState(onReady)
    val latestPageChanged by rememberUpdatedState(onPageChanged)
    val latestChapterRequested by rememberUpdatedState(onChapterRequested)
    val latestWord by rememberUpdatedState(onWord)
    val latestToolbarRequested by rememberUpdatedState(onToolbarRequested)
    val latestSentenceTapped by rememberUpdatedState(onSentenceTapped)
    val latestInitialScrollMode by rememberUpdatedState(initialScrollMode)
    val latestInitialScrollRatio by rememberUpdatedState(initialScrollRatio)
    val latestInitialScrollPageCount by rememberUpdatedState(initialScrollPageCount)
    val latestScrollModeChanged by rememberUpdatedState(onScrollModeChanged)
    val latestScrollProgress by rememberUpdatedState(onScrollProgress)
    val latestSpeakingOffscreen by rememberUpdatedState(onSpeakingOffscreen)
    val latestSavedWords by rememberUpdatedState(savedWords)
    val latestChromeTop by rememberUpdatedState(chromeTopPx)
    val latestChromeBottom by rememberUpdatedState(chromeBottomPx)
    val latestLocusBlock by rememberUpdatedState(initialLocusBlock)
    val latestLocusOffset by rememberUpdatedState(initialLocusOffset)
    val latestLocusAnchor by rememberUpdatedState(initialLocusAnchor)

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
                        pageChangedCallback = { page, count, origin ->
                            latestPageChanged(page, count, origin)
                        },
                        chapterRequestedCallback = { latestChapterRequested(it) },
                        wordCallback = { latestWord(it) },
                        toolbarRequestedCallback = { latestToolbarRequested() },
                        sentenceTappedCallback = { block, offset -> latestSentenceTapped(block, offset) },
                        scrollModeChangedCallback = { active -> latestScrollModeChanged(active) },
                        scrollProgressCallback = { progress, page, count ->
                            latestScrollProgress(progress, page, count)
                        },
                        speakingOffscreenCallback = { latestSpeakingOffscreen(it) }
                    ),
                    "ReaderBridge"
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(
                            ReaderScripts.bootstrap(
                                initialPage,
                                latestPreferences,
                                initialLocusBlock = latestLocusBlock,
                                initialLocusOffset = latestLocusOffset,
                                initialLocusAnchor = latestLocusAnchor,
                                initialScrollMode = latestInitialScrollMode,
                                initialScrollRatio = latestInitialScrollRatio,
                                initialScrollPageCount = latestInitialScrollPageCount.coerceAtLeast(1),
                                chromeTopPx = latestChromeTop,
                                chromeBottomPx = latestChromeBottom,
                                scrollEndHint = context.getString(R.string.reader_scroll_end_hint),
                                scrollStartHint = context.getString(R.string.reader_scroll_start_hint)
                            ),
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
            controller.applyChromeInsets(latestChromeTop, latestChromeBottom)
        },
        onRelease = { view ->
            controller.detach(view)
            view.removeJavascriptInterface("ReaderBridge")
            view.stopLoading()
            runCatching { view.destroy() }
        }
    )
}
