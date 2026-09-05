package com.linguareader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linguareader.shared.app.PreferencesStore
import com.linguareader.shared.data.ReviewPace
import com.linguareader.shared.data.ReviewScheduler
import com.linguareader.shared.data.SavedWord
import com.linguareader.shared.data.VocabularyRepository
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser

/**
 * 复习屏（M2 桌面）：到期词卡片 + 记住了/再学一次。调度真相是 :shared 的
 * ReviewScheduler / ReviewPace，与 Android 侧同一套参数与持久化键。
 */
@Composable
fun ReviewPane(vocabulary: VocabularyRepository, reviewPrefs: PreferencesStore) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var words by remember { mutableStateOf<List<SavedWord>>(emptyList()) }
    var cursor by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    suspend fun reload() {
        words = vocabulary.load()
        cursor = 0
        revealed = false
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    val pace = remember { ReviewPace.fromPreferences(reviewPrefs) }
    val due = words.filter { it.nextReviewAt <= System.currentTimeMillis() }
        .sortedBy { it.nextReviewAt }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("复习 · ${pace.label}", style = MaterialTheme.typography.headlineSmall)
        Text(
            "到期 ${due.size} / 共 ${words.size}（节奏 ×${pace.intervalMultiplier}，" +
                "首隔 ${pace.firstDelayMillis / 60_000} 分钟）",
            style = MaterialTheme.typography.bodyMedium
        )

        val current = due.getOrNull(cursor)
        when {
            loading -> Text("加载中…")
            current == null -> Text(
                if (words.isEmpty()) "生词本为空。桌面端导入与查词将在下一刀接通；" +
                    "当前可把 Android 侧的 vocabulary.json 拷入数据目录体验。"
                else "本轮到期词已清完，稍后再来。"
            )
            else -> ReviewCard(
                word = current,
                revealed = revealed,
                onReveal = { revealed = true },
                onAnswer = { remembered ->
                    val id = current.id
                    revealed = false
                    scope.launch {
                        vocabulary.review(id, remembered, pace)
                        reload()
                    }
                }
            )
        }
    }
}

@Composable
private fun ReviewCard(
    word: SavedWord,
    revealed: Boolean,
    onReveal: () -> Unit,
    onAnswer: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(word.headword, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            if (word.phonetic.isNotBlank()) Text(word.phonetic, style = MaterialTheme.typography.bodyLarge)
            if (word.sentence.isNotBlank()) Text("“${word.sentence}”", style = MaterialTheme.typography.bodyMedium)

            if (revealed) {
                if (word.meaning.isNotBlank()) Text(word.meaning, style = MaterialTheme.typography.titleMedium)
                if (word.aiMeaning.isNotBlank()) Text("AI：${word.aiMeaning}", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onAnswer(true) }) { Text("记住了") }
                    OutlinedButton(onClick = { onAnswer(false) }) { Text("再学一次") }
                }
            } else {
                Button(onClick = onReveal) { Text("显示释义") }
            }
            Text(
                "已复习 ${word.reviewCount} 次 · 下次 " +
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(word.nextReviewAt)),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * 生词本屏（M2 桌面）：全部词条列表 + CSV 导出（JFileChooser，与 Android 的
 * SAF 导出同一 csv 生成逻辑——都在 :shared 的 VocabularyRepository.csv）。
 */
@Composable
fun VocabularyPane(vocabulary: VocabularyRepository) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var words by remember { mutableStateOf<List<SavedWord>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { words = vocabulary.load(); loading = false }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("生词本（${words.size}）", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = {
                val chooser = JFileChooser()
                if (chooser.saveDialog() == JFileChooser.APPROVE_OPTION) {
                    scope.launch {
                        val target = chooser.selectedFile
                        runCatching { File(target.absolutePath).writeText(VocabularyRepository.csv(words), Charsets.UTF_8) }
                            .onSuccess { status = "已导出 ${words.size} 条到 ${target.absolutePath}" }
                            .onFailure { status = "导出失败：${it.message}" }
                    }
                }
            }) { Text("导出 CSV") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelMedium)

        if (loading) Text("加载中…")
        else if (words.isEmpty()) Text("生词本为空。")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(words, key = { it.id }) { word ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(word.headword, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (word.meaning.isNotBlank()) Text(word.meaning, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text(
                            "${word.bookTitle} · ${word.chapterTitle} · level ${word.reviewLevel}/${ReviewScheduler.masteredLevel}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

private fun JFileChooser.saveDialog(): Int {
    dialogTitle = "导出生词本 CSV"
    selectedFile = File("vocabulary.csv")
    return showSaveDialog(null)
}
