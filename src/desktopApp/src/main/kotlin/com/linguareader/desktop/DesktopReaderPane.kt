package com.linguareader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linguareader.shared.app.PreferencesStore
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.DictionaryDatabase
import com.linguareader.shared.data.DictionaryLookupResult
import com.linguareader.shared.data.DictionaryRepository
import com.linguareader.shared.data.ReaderPreferences
import com.linguareader.shared.data.ReviewPace
import com.linguareader.shared.data.VocabularyRepository
import com.linguareader.shared.data.WordLookup
import com.linguareader.shared.reader.ReaderScripts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.cef.browser.CefBrowser
import java.io.File

/**
 * 桌面 JCEF 阅读屏（M4，路线 B）：Chromium 承载与 Android WebView 同一份
 * 章节 XHTML + :shared ReaderScripts 注入 JS；`window.ReaderBridge` shim 经
 * CefMessageRouter 把 onWord/onPageChanged/onChapterRequested 等回调转回
 * Kotlin——方法名与参数与 Android `@JavascriptInterface` 契约一一对应。
 * JCEF 初始化失败时由 AppScaffold 降级到纯文本 ReadingPane。
 */
@Composable
fun DesktopReaderPane(
    book: Book,
    home: File,
    dictionary: DictionaryDatabase?,
    vocabulary: VocabularyRepository,
    reviewPrefs: PreferencesStore,
    library: com.linguareader.shared.data.LibraryRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var chapterIndex by remember { mutableStateOf(book.chapterIndex.coerceIn(0, book.chapters.lastIndex.coerceAtLeast(0))) }
    var browser by remember { mutableStateOf<CefBrowser?>(null) }
    var lookupResult by remember { mutableStateOf<DictionaryLookupResult?>(null) }
    var lookupRequest by remember { mutableStateOf<WordLookup?>(null) }
    var status by remember { mutableStateOf("") }
    var preferences by remember { mutableStateOf(ReaderPreferences()) }
    var savedWords by remember { mutableStateOf<List<String>>(emptyList()) }

    fun chapterFile(): File = File(book.extractedDir, book.chapters[chapterIndex].relativePath)

    fun buildScripts(): List<String> {
        val shim = """
            (function(){
              if (window.ReaderBridge && window.ReaderBridge.__cef) return;
              function call(method, args){ try { window.cefQuery({request: method + '|' + JSON.stringify(args)}); } catch(e) {} }
              window.ReaderBridge = {
                __cef: true,
                onReady: function(page, pageCount){ call('onReady', {page: page, pageCount: pageCount}); },
                onPageChanged: function(page, pageCount, origin){ call('onPageChanged', {page: page, pageCount: pageCount, origin: origin}); },
                onChapterRequested: function(direction){ call('onChapterRequested', {direction: direction}); },
                onWord: function(word, sentence, paragraph, sentenceOffset, x, y){ call('onWord', {word: word, sentence: sentence, paragraph: paragraph, sentenceOffset: sentenceOffset, x: x, y: y}); },
                onToolbarRequested: function(){ call('onToolbarRequested', {}); },
                onSentenceTapped: function(block, blockOffset){ call('onSentenceTapped', {block: block, blockOffset: blockOffset}); },
                onScrollModeChanged: function(active){ call('onScrollModeChanged', {active: active}); },
                onSpeakingOffscreen: function(offscreen){ call('onSpeakingOffscreen', {offscreen: offscreen}); },
                onScrollProgress: function(progress, page, pageCount){ call('onScrollProgress', {progress: progress, page: page, pageCount: pageCount}); }
              };
            })();
        """.trimIndent()
        val sameChapter = chapterIndex == book.chapterIndex
        val bootstrap = ReaderScripts.bootstrap(
            initialPage = 0,
            preferences = preferences,
            initialLocusBlock = if (sameChapter) book.locusBlockIndex else ReaderScripts.NO_LOCUS_BLOCK,
            initialLocusOffset = if (sameChapter) book.locusCharOffset else 0,
            initialLocusAnchor = if (sameChapter) book.locusAnchor else ReaderScripts.ANCHOR_EXACT,
            scrollEndHint = "已到本章末尾 · 快滑或点击进入下一章",
            scrollStartHint = "已到本章开头 · 快滑或点击返回上一章"
        )
        return listOf(shim, bootstrap, ReaderScripts.savedWordsScript(savedWords))
    }

    // 桥回调分发（在 AWT 线程被 CefMessageRouter 调用；Compose 快照状态跨线程安全）。
    DesktopCefRuntime.onBridgeCall = { method, argsJson ->
        fun str(json: String, key: String): String =
            Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1) ?: ""
        fun num(json: String, key: String): Double =
            Regex("\"$key\"\\s*:\\s*(-?[\\d.]+)").find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        when (method) {
            "onWord" -> {
                val request = WordLookup(
                    word = str(argsJson, "word").take(120),
                    sentence = str(argsJson, "sentence").take(1_500),
                    paragraph = str(argsJson, "paragraph").take(4_000),
                    sentenceOffset = num(argsJson, "sentenceOffset").toInt().coerceIn(0, 1_500),
                    x = num(argsJson, "x").toFloat(),
                    y = num(argsJson, "y").toFloat()
                )
                lookupRequest = request
                if (dictionary != null) {
                    scope.launch {
                        runCatching { DictionaryRepository(dictionary).lookup(request) }
                            .onSuccess { lookupResult = it }
                            .onFailure { status = "查词失败：${it.message}" }
                    }
                }
            }
            "onPageChanged" -> {
                val page = num(argsJson, "page").toInt().coerceAtLeast(0)
                val count = num(argsJson, "pageCount").toInt().coerceAtLeast(1)
                scope.launch {
                    runCatching { library.saveProgress(book, chapterIndex, page, page.toFloat() / count) }
                }
            }
            "onChapterRequested" -> {
                val direction = num(argsJson, "direction").toInt().coerceIn(-1, 1)
                val next = (chapterIndex + direction).coerceIn(0, book.chapters.lastIndex)
                if (next != chapterIndex) {
                    chapterIndex = next
                    browser?.loadURL(chapterFile().toURI().toString())
                }
            }
        }
    }

    LaunchedEffect(Unit) { savedWords = vocabulary.load().map { it.headword } }
    LaunchedEffect(book.id) {
        DesktopCefRuntime.injector = { buildScripts() }
        val b = DesktopCefRuntime.acquire(home, chapterFile().toURI().toString())
        browser = b
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = {
                scope.launch { library.saveProgress(book, chapterIndex, 0, 0f) }
                onBack()
            }) { Text("← 书架") }
            Text(book.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                if (chapterIndex > 0) {
                    chapterIndex--
                    browser?.loadURL(chapterFile().toURI().toString())
                }
            }, enabled = chapterIndex > 0) { Text("上一章") }
            Text("${chapterIndex + 1}/${book.chapters.size}")
            OutlinedButton(onClick = {
                if (chapterIndex < book.chapters.lastIndex) {
                    chapterIndex++
                    browser?.loadURL(chapterFile().toURI().toString())
                }
            }, enabled = chapterIndex < book.chapters.lastIndex) { Text("下一章") }
        }

        lookupResult?.let { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val entry = result.entry
                    if (entry == null) {
                        Text("词典无命中", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(entry.headword, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            if (entry.phonetic.isNotBlank()) Text(entry.phonetic, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = {
                                val request = lookupRequest ?: return@TextButton
                                scope.launch {
                                    runCatching {
                                        vocabulary.save(
                                            book = book,
                                            chapterTitle = book.chapters.getOrNull(chapterIndex)?.title.orEmpty(),
                                            lookup = request,
                                            entry = entry,
                                            pace = ReviewPace.fromPreferences(reviewPrefs)
                                        )
                                    }.onSuccess { status = "已收藏「${entry.headword}」" }
                                }
                            }) { Text("★ 收藏") }
                        }
                        entry.senses.take(3).forEach { sense -> Text("· ${sense.text}") }
                        result.relatedPhrase?.let { rel ->
                            Text("相关词组：${rel.headword}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelMedium)

        // browser 异步就绪后用 key 重建 SwingPanel，factory 换成真实浏览器组件。
        key(browser) {
            SwingPanel(
                modifier = Modifier.fillMaxSize().weight(1f),
                factory = {
                    val b = browser
                    if (b != null) {
                        b.uiComponent
                    } else {
                        javax.swing.JPanel().apply {
                            add(javax.swing.JLabel("Chromium 启动中…（首次运行需下载内核，约 100-200MB）"))
                        }
                    }
                },
                update = { }
            )
        }
    }
}
