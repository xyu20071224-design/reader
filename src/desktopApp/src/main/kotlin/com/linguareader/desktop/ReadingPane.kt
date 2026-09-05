package com.linguareader.desktop

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linguareader.shared.ai.ChapterTextExtractor
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.DictionaryDatabase
import com.linguareader.shared.data.DictionaryLookupResult
import com.linguareader.shared.data.DictionaryRepository
import com.linguareader.shared.data.ReviewPace
import com.linguareader.shared.data.VocabularyRepository
import com.linguareader.shared.data.WordLookup
import com.linguareader.shared.tts.SentenceSplitter
import kotlinx.coroutines.launch

/**
 * 阅读屏（M2 桌面）：章内正文纯文本渲染，点词 → 语境查词 → 收藏。
 * 查词链路与 Android 完全同源：:shared 的 DictionaryRepository（桌面 sqlite-jdbc
 * 引擎）+ ContextAnalyzer 语境排序；收藏走 VocabularyRepository（同一 JSON/键）。
 * 无 WebView——真正的 WebView/JCEF 阅读器是 M4。
 */
@Composable
fun ReadingPane(
    book: Book,
    dictionary: DictionaryDatabase?,
    vocabulary: VocabularyRepository,
    reviewPrefs: com.linguareader.shared.app.PreferencesStore,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var chapterIndex by remember { mutableStateOf(book.chapterIndex.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))) }
    var chapters by remember { mutableStateOf<List<String>>(emptyList()) }
    var lookupResult by remember { mutableStateOf<DictionaryLookupResult?>(null) }
    var lookupRequest by remember { mutableStateOf<WordLookup?>(null) }
    var status by remember { mutableStateOf("") }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    LaunchedEffect(book.id) {
        chapters = ChapterTextExtractor().extract(book).map { it.text }
    }

    val chapterText = chapters.getOrNull(chapterIndex).orEmpty()

    // 句子切分（复用 :shared 的 SentenceSplitter）+ 每句在全文里的起点。
    val sentences = remember(chapterText) {
        var start = 0
        SentenceSplitter.split(chapterText).map { s ->
            val at = chapterText.indexOf(s, start)
            val from = if (at >= 0) at else start
            start = from + s.length
            from to s
        }
    }
    val annotated = remember(chapterText) { buildAnnotatedString { append(chapterText) } }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("← 书架") }
            Text(book.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                if (chapterIndex > 0) { chapterIndex--; lookupResult = null; scope.launch { scroll.scrollTo(0) } }
            }, enabled = chapterIndex > 0) { Text("上一章") }
            Text("${chapterIndex + 1}/${book.chapters.size}")
            OutlinedButton(onClick = {
                if (chapterIndex < book.chapters.size - 1) { chapterIndex++; lookupResult = null; scope.launch { scroll.scrollTo(0) } }
            }, enabled = chapterIndex < book.chapters.size - 1) { Text("下一章") }
        }
        HorizontalDivider()

        if (dictionary == null) {
            Text("未找到离线词典。把 ecdict.sqlite 放到数据目录 dictionary/ 下（或 -Dlr.dict 指定）后重启。")
        } else {
            Text(
                "点按任意单词查词并收藏。${if (status.isNotBlank()) status else ""}",
                style = MaterialTheme.typography.labelMedium
            )
            lookupResult?.let { result ->
                LookupCard(
                    result = result,
                    onSave = {
                        val entry = result.entry ?: return@LookupCard
                        val request = lookupRequest ?: return@LookupCard
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
                                .onFailure { status = "收藏失败：${it.message}" }
                        }
                    }
                )
            }

            Text(
                annotated,
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .pointerInput(chapterText) {
                        detectTapGestures { position ->
                            val l = layout ?: return@detectTapGestures
                            val offset = l.getOffsetForPosition(position)
                            val word = wordAt(chapterText, offset) ?: return@detectTapGestures
                            val sentenceInfo = sentences.firstOrNull { (from, s) -> offset in from until from + s.length }
                                ?: (0 to chapterText.take(200))
                            val request = WordLookup(
                                word = word,
                                sentence = sentenceInfo.second,
                                paragraph = sentenceInfo.second,
                                sentenceOffset = offset - sentenceInfo.first,
                                x = 0f,
                                y = 0f
                            )
                            scope.launch {
                                runCatching {
                                    DictionaryRepository(dictionary).lookup(request)
                                }.onSuccess {
                                    lookupResult = it
                                    lookupRequest = request
                                }.onFailure { status = "查词失败：${it.message}" }
                            }
                        }
                    },
                onTextLayout = { layout = it },
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun LookupCard(
    result: DictionaryLookupResult,
    onSave: () -> Unit
) {
    val entry = result.entry
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (entry == null) {
                Text("词典无命中", style = MaterialTheme.typography.titleMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(entry.headword, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (entry.phonetic.isNotBlank()) Text(entry.phonetic, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onSave) { Text("★ 收藏") }
                }
                entry.senses.take(3).forEach { sense -> Text("· ${sense.text}") }
                result.relatedPhrase?.let { rel ->
                    Text("相关词组：${rel.headword}（${rel.senses.firstOrNull()?.text.orEmpty()}）",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** 点按处的单词（含撇号/连字符内的字母），偏移越界或非词返回 null。 */
private fun wordAt(text: String, offset: Int): String? {
    if (offset !in text.indices) return null
    if (!text[offset].isLetter()) return null
    var start = offset
    var end = offset
    while (start > 0 && (text[start - 1].isLetter() || text[start - 1] == '’' || text[start - 1] == '\'')) start--
    while (end < text.length - 1 && (text[end].isLetter() || text[end] == '’' || text[end] == '\'')) end++
    return text.substring(start, end + 1).takeIf { it.any(Char::isLetter) }
}
