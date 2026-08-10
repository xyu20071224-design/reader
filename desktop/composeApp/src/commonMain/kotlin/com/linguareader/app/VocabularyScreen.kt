package com.linguareader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.app.data.ReviewScheduler
import com.linguareader.app.data.SavedWord
import com.linguareader.app.platform.OutputTarget
import com.linguareader.app.platform.rememberFileSaveLauncher

@Composable
internal fun VocabularyScreen(
    words: List<SavedWord>,
    reviewPreset: ReviewMode?,
    customReview: ReviewPace,
    reminders: ReviewReminders,
    onReviewModeChange: (ReviewMode) -> Unit,
    onCustomReviewChange: (ReviewPace) -> Unit,
    onRemindersChange: (ReviewReminders) -> Unit,
    onRemove: (String) -> Unit,
    onReview: (String, Boolean, (Boolean) -> Unit) -> Unit,
    onExport: (OutputTarget) -> Unit,
    onSpeak: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var reviewDeck by remember { mutableStateOf<List<SavedWord>?>(null) }
    var confirmReviewAll by remember { mutableStateOf(false) }
    var showReviewSettings by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val dueWords = words.filter { it.nextReviewAt <= now }
    val masteredWords = words.filter { it.reviewLevel >= ReviewScheduler.masteredLevel }
    val filtered = words.filter {
        query.isBlank() ||
            it.headword.contains(query, ignoreCase = true) ||
            it.meaning.contains(query, ignoreCase = true) ||
            it.sentence.contains(query, ignoreCase = true)
    }
    val exportSaver = rememberFileSaveLauncher("LinguaReader-vocabulary.csv") { target ->
        if (target != null) onExport(target)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("我的生词", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (dueWords.isEmpty()) "今日复习已完成" else "${dueWords.size} 个待复习",
                    color = if (dueWords.isEmpty()) InkSoft else Accent,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            TextButton(
                onClick = {
                    // Due words form the review queue. When nothing is due,
                    // reviewing everything is an explicit opt-in so words that
                    // were just reviewed never silently re-enter the queue.
                    if (dueWords.isNotEmpty()) {
                        reviewDeck = dueWords
                    } else if (words.isNotEmpty()) {
                        confirmReviewAll = true
                    }
                },
                enabled = words.isNotEmpty()
            ) {
                Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("复习")
            }
            TextButton(
                onClick = exportSaver,
                enabled = words.isNotEmpty()
            ) {
                Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("导出")
            }
            TextButton(onClick = { showReviewSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("设置")
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatChip("${words.size}", "生词总数", Accent, Modifier.weight(1f))
            StatChip("${dueWords.size}", "待复习", Gold, Modifier.weight(1f))
            StatChip("${masteredWords.size}", "已掌握", Success, Modifier.weight(1f))
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索单词、释义或例句") },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = InkFaint)
            },
            shape = SmallShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                focusedLabelColor = Accent,
                cursorColor = Accent
            )
        )
        Spacer(Modifier.height(10.dp))
        if (filtered.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(if (words.isEmpty()) "还没有收藏生词" else "没有匹配结果")
                Spacer(Modifier.height(6.dp))
                Text(
                    if (words.isEmpty()) "阅读时点击单词，再选择“加入生词本”。" else "尝试缩短搜索内容。",
                    color = InkSoft
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(filtered, key = { _, word -> word.id }) { _, word ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardSurface),
                        shape = CardShape,
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    word.headword,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onSpeak(word.headword) }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "朗读",
                                        tint = Accent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(onClick = { onRemove(word.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除",
                                        tint = InkFaint,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            if (word.phonetic.isNotBlank()) {
                                Text("/${word.phonetic}/", color = InkSoft)
                            }
                            Text(
                                word.meaning,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                word.sentence,
                                fontFamily = FontFamily.Serif,
                                color = InkSoft,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${word.bookTitle} · ${word.chapterTitle} · ${reviewStatus(word, now)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Accent.copy(alpha = .78f)
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showReviewSettings) {
        ReviewSettingsSheet(
            preset = reviewPreset,
            custom = customReview,
            reminders = reminders,
            onChangePreset = onReviewModeChange,
            onChangeCustom = onCustomReviewChange,
            onChangeReminders = onRemindersChange,
            onDismiss = { showReviewSettings = false }
        )
    }

    reviewDeck?.let { deck ->
        ReviewSheet(
            deck = deck,
            onReview = onReview,
            onSpeak = onSpeak,
            onDismiss = { reviewDeck = null }
        )
    }

    if (confirmReviewAll) {
        AlertDialog(
            onDismissRequest = { confirmReviewAll = false },
            confirmButton = {
                TextButton(onClick = {
                    reviewDeck = words
                    confirmReviewAll = false
                }) { Text("复习全部", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReviewAll = false }) { Text("取消") }
            },
            title = { Text("今日复习已完成") },
            text = { Text("当前没有到期待复习的单词。是否复习全部 ${words.size} 个生词？") },
            containerColor = CardSurface,
            shape = CardShape
        )
    }
}

@Composable
private fun StatChip(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(PaperDeep, SmallShape)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = InkSoft)
    }
}

private fun reviewStatus(word: SavedWord, now: Long): String {
    val remaining = word.nextReviewAt - now
    if (remaining <= 0) return "待复习"
    return when {
        remaining < 60_000L -> "1 分钟后复习"
        remaining < 3_600_000L -> "${remaining / 60_000L} 分钟后复习"
        remaining < 86_400_000L -> "${remaining / 3_600_000L} 小时后复习"
        else -> "${(remaining + 86_399_999L) / 86_400_000L} 天后复习"
    }
}
