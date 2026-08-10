package com.linguareader.app

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linguareader.app.ai.AiBookStatus
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.data.Book
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookshelfScreen(
    state: AppUiState,
    onImport: (android.net.Uri) -> Unit,
    onOpen: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    onAiSettingsChange: (AiSettings) -> Unit,
    onRemoveWord: (String) -> Unit,
    onReviewModeChange: (ReviewMode) -> Unit,
    onCustomReviewChange: (ReviewPace) -> Unit,
    onRemindersChange: (ReviewReminders) -> Unit,
    onReviewWord: (String, Boolean, (Boolean) -> Unit) -> Unit,
    onExportVocabulary: (android.net.Uri) -> Unit,
    onSpeak: (String) -> Unit,
    onDismissMessage: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) onImport(it)
    }
    var deleteCandidate by remember { mutableStateOf<Book?>(null) }
    var showVocabulary by rememberSaveable { mutableStateOf(false) }
    var showAiSettings by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("语境阅读", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (showVocabulary) "${state.savedWords.size} 个生词"
                            else "${state.books.size} 本书",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkSoft
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showVocabulary = !showVocabulary }) {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (showVocabulary) Accent else InkSoft
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(if (showVocabulary) "书架" else "生词本 ${state.savedWords.size}")
                    }
                    if (!showVocabulary) {
                        TextButton(onClick = { showAiSettings = true }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (state.aiSettings.enabled) Accent else InkSoft
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (state.aiSettings.enabled) "AI 已启用" else "AI 设置")
                        }
                        Button(
                            onClick = {
                                launcher.launch(
                                    arrayOf(
                                        "application/epub+zip",
                                        "application/zip",
                                        "application/octet-stream",
                                        "text/plain",
                                        "application/x-fictionbook+xml",
                                        "application/pdf"
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = Color.White
                            ),
                            shape = PillShape
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("导入")
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showVocabulary) {
                VocabularyScreen(
                    words = state.savedWords,
                    reviewPreset = state.reviewPreset,
                    customReview = state.customReview,
                    reminders = state.reminders,
                    onReviewModeChange = onReviewModeChange,
                    onCustomReviewChange = onCustomReviewChange,
                    onRemindersChange = onRemindersChange,
                    onRemove = onRemoveWord,
                    onReview = onReviewWord,
                    onExport = onExportVocabulary,
                    onSpeak = onSpeak
                )
            } else if (state.books.isEmpty() && !state.loading) {
                EmptyBookshelf(onImport = {
                    launcher.launch(
                        arrayOf(
                            "application/epub+zip",
                            "application/octet-stream",
                            "text/plain",
                            "application/x-fictionbook+xml",
                            "application/pdf"
                        )
                    )
                })
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(148.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(state.books, key = { it.id }) { book ->
                        BookCard(
                            book = book,
                            aiEnabled = state.aiSettings.enabled,
                            aiStatus = state.aiStatuses[book.id],
                            onOpen = { onOpen(book) },
                            onLongPressDelete = { deleteCandidate = book }
                        )
                    }
                }
            }
            if (state.loading) {
                Box(
                    Modifier.fillMaxSize().background(Paper.copy(alpha = .72f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Accent)
                        Spacer(Modifier.height(12.dp))
                        Text("正在整理图书…", color = InkSoft)
                    }
                }
            }
        }
    }

    state.message?.let {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            confirmButton = { TextButton(onClick = onDismissMessage) { Text("知道了") } },
            title = { Text(state.messageTitle) },
            text = { Text(it) },
            containerColor = CardSurface,
            shape = CardShape
        )
    }

    deleteCandidate?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(book)
                    deleteCandidate = null
                }) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("取消") }
            },
            title = { Text("删除《${book.title}》？") },
            text = { Text("本地书籍副本与阅读进度会一并删除。") },
            containerColor = CardSurface,
            shape = CardShape
        )
    }

    if (showAiSettings) {
        AiSettingsDialog(
            settings = state.aiSettings,
            onSave = onAiSettingsChange,
            onDismiss = { showAiSettings = false }
        )
    }
}

@Composable
private fun AiSettingsDialog(
    settings: AiSettings,
    onSave: (AiSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var enabled by remember(settings) { mutableStateOf(settings.enabled) }
    var apiKey by remember(settings) { mutableStateOf(settings.apiKey) }
    var baseUrl by remember(settings) { mutableStateOf(settings.baseUrl) }
    var model by remember(settings) { mutableStateOf(settings.model) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    settings.copy(
                        enabled = enabled,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = model
                    )
                )
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("AI 语境翻译") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (enabled) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("DeepSeek API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("接口地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("模型") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "启用后，书籍章节文本与点击的词句会发送到 DeepSeek 以生成/更新本书语境档案；" +
                            "未填 API Key 时自动使用不联网的本地轻量语境。",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSoft
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "关闭时查词保持纯本地词典，不发送任何文本。",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSoft
                    )
                }
            }
        },
        containerColor = CardSurface,
        shape = CardShape
    )
}

@Composable
private fun EmptyBookshelf(onImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = Accent.copy(alpha = .55f)
        )
        Spacer(Modifier.height(20.dp))
        Text("从一本英文书开始", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "导入无 DRM 的可重排 EPUB、TXT、FB2 或文字版 PDF。图书和阅读进度只保存在本机。",
            color = InkSoft
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onImport,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
            shape = PillShape
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("选择电子书文件")
        }
    }
}

@Composable
private fun BookCard(
    book: Book,
    aiEnabled: Boolean,
    aiStatus: AiBookStatus?,
    onOpen: () -> Unit,
    onLongPressDelete: () -> Unit
) {
    Column(modifier = Modifier.clickable(onClick = onOpen)) {
        Box {
            Card(
                modifier = Modifier.fillMaxWidth().height(205.dp),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = BookCoverFallback),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                val coverFile = book.coverRelativePath?.let { File(book.extractedDir, it) }
                val bitmap by produceState<android.graphics.Bitmap?>(null, coverFile?.absolutePath) {
                    value = withContext(Dispatchers.IO) {
                        coverFile?.takeIf { it.exists() }?.let { decodeSampledCover(it, 300) }
                    }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize()
                            .background(Brush.verticalGradient(listOf(AccentSoft, BookCoverFallback)))
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                book.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(book.author, style = MaterialTheme.typography.labelMedium, color = InkSoft)
                        }
                    }
                }
            }
            if (book.progress > 0f) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(38.dp)
                        .background(Paper.copy(alpha = .94f), CircleShape)
                        .padding(4.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { book.progress },
                        modifier = Modifier.fillMaxSize(),
                        color = Accent,
                        trackColor = Accent.copy(alpha = .14f),
                        strokeWidth = 3.dp
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            book.title,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            if (book.progress > 0f) "已读 ${(book.progress * 100).roundToInt()}%" else book.author,
            style = MaterialTheme.typography.bodySmall,
            color = InkSoft,
            maxLines = 1
        )
        if (aiEnabled) {
            val statusLabel = when {
                aiStatus == null -> "AI 语境：待生成"
                aiStatus.generating -> "AI 语境：生成中…"
                aiStatus.ready -> "AI 语境：就绪"
                else -> "AI 语境：生成失败"
            }
            Text(
                statusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (aiStatus?.ready == true) Success else InkFaint,
                maxLines = 1
            )
        }
        TextButton(onClick = onLongPressDelete, modifier = Modifier.height(30.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = InkFaint
            )
            Spacer(Modifier.width(3.dp))
            Text("移除", style = MaterialTheme.typography.labelSmall, color = InkFaint)
        }
    }
}

private fun decodeSampledCover(file: File, targetWidth: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= targetWidth) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}
