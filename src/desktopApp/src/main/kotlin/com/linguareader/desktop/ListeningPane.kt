package com.linguareader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linguareader.shared.app.AppContext
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.LibraryRepository
import com.linguareader.shared.tts.TtsChapter
import com.linguareader.shared.tts.TtsPlaybackEngine
import com.linguareader.shared.tts.TtsPlaybackState
import com.linguareader.shared.tts.SentenceSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 听书屏（M3 桌面）：云 TTS（自建 OpenAI 兼容服务端）+ :shared 播放状态机。
 * 桌面不做系统 TTS（已拍板决策 3）——唯一的桌面后端是 HTTP 合成。
 * 章内容经 ChapterTextExtractor 取纯文本、整章作为单块交给 TtsChapter
 * （块序对齐是 Android WebView 高亮的契约，桌面无阅读器暂不需要）。
 */
@Composable
fun ListeningPane(
    context: AppContext,
    library: LibraryRepository,
    engine: TtsPlaybackEngine,
    ttsState: androidx.compose.runtime.MutableState<TtsPlaybackState>
) {
    val scope = rememberCoroutineScope()
    val ttsPrefs = remember { context.prefs("cloud_tts") }
    var settings by remember { mutableStateOf(DesktopCloudTtsSettings.fromPrefs(ttsPrefs)) }
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var selected by remember { mutableStateOf<Book?>(null) }
    var status by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(Unit) { books = library.loadBooks() }

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("听书（云 TTS）", style = MaterialTheme.typography.headlineSmall)

        Text("服务端（OpenAI 兼容，返回 MP3）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = settings.serverUrl,
            onValueChange = { settings = settings.copy(serverUrl = it) },
            label = { Text("服务器地址，如 http://192.168.1.10:8000") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = settings.serverToken,
                onValueChange = { settings = settings.copy(serverToken = it) },
                label = { Text("Token（可选）") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = settings.voice,
                onValueChange = { settings = settings.copy(voice = it) },
                label = { Text("音色（default 自动中英）") },
                modifier = Modifier.weight(1f)
            )
        }
        Button(onClick = {
            DesktopCloudTtsSettings.write(ttsPrefs, settings)
            status = "已保存（下次播放生效）"
        }) { Text("保存设置") }

        HorizontalDivider()

        Text("选书", style = MaterialTheme.typography.titleMedium)
        if (books.isEmpty()) Text("书架为空，先去书架导入。")
        for (book in books) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${book.title}（${book.chapters.size} 章）",
                    Modifier.weight(1f),
                    fontWeight = if (selected?.id == book.id) FontWeight.Bold else FontWeight.Normal
                )
                OutlinedButton(onClick = {
                    selected = book
                    status = "已选《${book.title}》"
                }) { Text("选择") }
            }
        }

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = selected != null,
                onClick = {
                    val book = selected ?: return@Button
                    DesktopCloudTtsSettings.write(ttsPrefs, settings)
                    engine.startPlayback(book, book.chapterIndex, 0)
                    status = "播放中…"
                }
            ) { Text("▶ 播放") }
            OutlinedButton(onClick = { engine.pause(); status = "已暂停" }) { Text("⏸ 暂停") }
            OutlinedButton(onClick = { engine.next() }) { Text("下一句") }
            OutlinedButton(onClick = { engine.previous() }) { Text("上一句") }
            OutlinedButton(onClick = { engine.stop(); status = "已停止" }) { Text("⏹ 停止") }
        }

        if (ttsState.value.isActive) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "第 ${ttsState.value.chapterIndex + 1} 章 · 句 ${ttsState.value.sentenceIndex + 1}/${ttsState.value.sentenceCount}",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(ttsState.value.currentSentence, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelMedium)
        Text(
            "说明：桌面听书走自建云 TTS（本机/局域网/ frp 均可），每句合成 MP3 后本地播放；" +
                "语速控制与整书缓存暂未实现。播放用的引擎回调在后台线程执行。",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/** 组装桌面播放引擎（在 AppScaffold 层创建一次；引擎内部自带协程异常兜底）。 */
fun createDesktopTtsEngine(
    context: AppContext,
    library: LibraryRepository,
    onState: (TtsPlaybackState) -> Unit
): TtsPlaybackEngine {
    val ttsPrefs = context.prefs("cloud_tts")
    return TtsPlaybackEngine(
        synthesizerFactory = { listener ->
            DesktopCloudTtsSynthesizer(DesktopCloudTtsSettings.fromPrefs(ttsPrefs), listener)
        },
        chapterLoader = { book, chapterIndex ->
            val chapters = com.linguareader.shared.ai.ChapterTextExtractor().extract(book)
            val text = chapters.firstOrNull { it.index == chapterIndex }?.text
                ?: chapters.getOrNull(chapterIndex)?.text.orEmpty()
            val title = book.chapters.getOrNull(chapterIndex)?.title ?: "第 ${chapterIndex + 1} 章"
            // 桌面无 DOM 块序契约：整章一个块，句切分交给 TtsChapter/SentenceSplitter。
            TtsChapter(
                chapterIndex = chapterIndex,
                title = title,
                blocks = listOf(text.ifBlank { "（本章无文本内容）" })
            )
        },
        isSystemEngine = { false },
        onChapterRequest = { /* 桌面无 WebView 阅读器需要同步章节 */ },
        onBookSwitched = { },
        onProgressSave = { book, chapterIndex, sentenceIndex ->
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    library.saveListeningProgress(book, chapterIndex, sentenceIndex)
                }
            }
        },
        onState = onState,
        dispatcher = Dispatchers.Default
    )
}
