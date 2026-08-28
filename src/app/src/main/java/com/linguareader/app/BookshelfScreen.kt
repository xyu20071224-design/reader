package com.linguareader.app

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linguareader.app.ai.AiBookStatus
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.ai.BookGlossary
import com.linguareader.app.data.Book
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.TtsPlaybackController
import com.linguareader.app.ai.GlossaryEntry
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** 书架两个导入入口（顶栏按钮与空态按钮）共用的可选文件类型。 */
private val IMPORT_MIME_TYPES = arrayOf(
    "application/epub+zip",
    "application/zip",
    "application/octet-stream",
    "text/plain",
    "application/x-fictionbook+xml",
    "application/pdf"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookshelfScreen(
    state: AppUiState,
    onImport: (android.net.Uri) -> Unit,
    onOpen: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    onAttachTranslation: (Book, android.net.Uri) -> Unit,
    onDetachTranslation: (Book) -> Unit,
    onAiSettingsChange: (AiSettings) -> Unit,
    onLoadGlossary: suspend (String) -> BookGlossary,
    onAddGlossary: suspend (String, String, String) -> BookGlossary,
    onUpdateGlossary: suspend (String, GlossaryEntry) -> BookGlossary,
    onRemoveGlossary: suspend (String, String) -> BookGlossary,
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
    // 「加译本」用独立的文件选择器：回调里要知道是给哪本英文书配的译本。
    var pendingTranslationBook by remember { mutableStateOf<Book?>(null) }
    var detachTranslationCandidate by remember { mutableStateOf<Book?>(null) }
    val translationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = pendingTranslationBook
            pendingTranslationBook = null
            if (uri != null && target != null) onAttachTranslation(target, uri)
        }
    var showVocabulary by rememberSaveable { mutableStateOf(false) }
    var showAiDrawer by rememberSaveable { mutableStateOf(false) }
    var glossaryBook by remember { mutableStateOf<Book?>(null) }
    var rosterBook by remember { mutableStateOf<Book?>(null) }

    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (showVocabulary) {
                                pluralStringResource(
                                    R.plurals.shelf_word_count,
                                    state.savedWords.size,
                                    state.savedWords.size
                                )
                            } else {
                                pluralStringResource(
                                    R.plurals.shelf_book_count,
                                    state.books.size,
                                    state.books.size
                                )
                            },
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
                        Text(
                            if (showVocabulary) {
                                stringResource(R.string.shelf_tab_shelf)
                            } else {
                                stringResource(R.string.shelf_tab_words, state.savedWords.size)
                            }
                        )
                    }
                    if (!showVocabulary) {
                        TextButton(onClick = { showAiDrawer = true }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (state.aiSettings.enabled) Accent else InkSoft
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.shelf_ai_center))
                        }
                        Button(
                            onClick = { launcher.launch(IMPORT_MIME_TYPES) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = OnAccent
                            ),
                            shape = PillShape
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.shelf_import))
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
                EmptyBookshelf(onImport = { launcher.launch(IMPORT_MIME_TYPES) })
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
                            attachingTranslation = book.id in state.attachingTranslation,
                            onOpen = { onOpen(book) },
                            onGlossary = { glossaryBook = book },
                            onTranslation = {
                                if (book.hasTranslation) {
                                    detachTranslationCandidate = book
                                } else {
                                    pendingTranslationBook = book
                                    translationLauncher.launch(
                                        arrayOf(
                                            "application/epub+zip",
                                            "application/zip",
                                            "application/octet-stream",
                                            "text/plain"
                                        )
                                    )
                                }
                            },
                            onLongPressDelete = { deleteCandidate = book },
                            onManageRoster = { rosterBook = book }
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
                        Text(stringResource(R.string.shelf_loading), color = InkSoft)
                    }
                }
            }
        }
    }

    state.message?.let {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            confirmButton = {
                TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.common_got_it)) }
            },
            title = { Text(state.messageTitle.ifBlank { stringResource(R.string.common_notice_title) }) },
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
                }) { Text(stringResource(R.string.common_delete), color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            title = { Text(stringResource(R.string.shelf_delete_title, book.title)) },
            text = { Text(stringResource(R.string.shelf_delete_body)) },
            containerColor = CardSurface,
            shape = CardShape
        )
    }

    detachTranslationCandidate?.let { book ->
        AlertDialog(
            onDismissRequest = { detachTranslationCandidate = null },
            confirmButton = {
                TextButton(onClick = {
                    onDetachTranslation(book)
                    detachTranslationCandidate = null
                }) { Text(stringResource(R.string.common_delete), color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { detachTranslationCandidate = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            title = { Text(stringResource(R.string.shelf_translation_remove_title, book.title)) },
            text = { Text(stringResource(R.string.shelf_translation_remove_body)) },
            containerColor = CardSurface,
            shape = CardShape
        )
    }

    if (showAiDrawer) {
        AiDrawerSheet(
            books = state.books,
            aiSettings = state.aiSettings,
            onAiSettingsChange = onAiSettingsChange,
            onLoadGlossary = onLoadGlossary,
            onAddGlossary = onAddGlossary,
            onUpdateGlossary = onUpdateGlossary,
            onRemoveGlossary = onRemoveGlossary,
            onDismiss = { showAiDrawer = false }
        )
    }

    glossaryBook?.let { book ->
        GlossaryDialog(
            book = book,
            onLoad = onLoadGlossary,
            onAdd = onAddGlossary,
            onUpdate = onUpdateGlossary,
            onRemove = onRemoveGlossary,
            onDismiss = { glossaryBook = null }
        )
    }

    rosterBook?.let { book ->
        RosterSheet(
            book = book,
            books = state.books,
            onDismiss = { rosterBook = null }
        )
    }
}

/**
 * 该书的多角色/角色管理（方向 A）：从书卡片「角色」入口打开，复用 [MultiVoiceSection]，
 * 让角色管理入口更浅。设置独立加载与保存（不与听书设置弹层抢占状态）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RosterSheet(
    book: Book,
    books: List<Book>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 30.dp)
        ) {
            var settings by remember(book.id) {
                mutableStateOf(CloudTtsSettings.load(context))
            }
            MultiVoiceSection(
                settings = settings,
                onSettingsChange = { next ->
                    settings = next
                    CloudTtsSettings.save(context, next)
                    TtsPlaybackController.onCloudSettingsChanged(context)
                },
                books = books,
                preselectedBook = book
            )
        }
    }
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
        Text(
            stringResource(R.string.shelf_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.shelf_empty_body),
            color = InkSoft
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onImport,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
            shape = PillShape
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.shelf_empty_action))
        }
    }
}

@Composable
private fun BookCard(
    book: Book,
    aiEnabled: Boolean,
    aiStatus: AiBookStatus?,
    attachingTranslation: Boolean,
    onOpen: () -> Unit,
    onGlossary: () -> Unit,
    onManageRoster: () -> Unit,
    onTranslation: () -> Unit,
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
            if (book.progress > 0f) {
                stringResource(R.string.shelf_progress_read, (book.progress * 100).roundToInt())
            } else {
                book.author
            },
            style = MaterialTheme.typography.bodySmall,
            color = InkSoft,
            maxLines = 1
        )
        if (aiEnabled) {
            val statusLabel = when {
                aiStatus == null -> stringResource(R.string.shelf_ai_pending)
                aiStatus.generating -> stringResource(R.string.shelf_ai_generating)
                // 配了 Key 但生成时降级为本地：不显示「就绪」，留空即可（真正的
                // 诊断交给设置里的「测试连接」）。
                aiStatus.degraded -> ""
                aiStatus.ready -> stringResource(R.string.shelf_ai_ready)
                aiStatus.error != null -> stringResource(R.string.shelf_ai_error, aiStatus.error)
                else -> stringResource(R.string.shelf_ai_failed)
            }
            if (statusLabel.isNotBlank()) {
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (aiStatus?.ready == true) Success else InkFaint,
                    maxLines = 1
                )
            }
        }
        if (attachingTranslation || book.hasTranslation) {
            Text(
                if (attachingTranslation) stringResource(R.string.shelf_translation_aligning)
                else book.translationTitle.ifBlank { stringResource(R.string.shelf_translation_ready) },
                style = MaterialTheme.typography.bodySmall,
                color = if (attachingTranslation) InkFaint else Success,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row {
            TextButton(onClick = onGlossary, modifier = Modifier.height(30.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Accent
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    stringResource(R.string.shelf_glossary),
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent
                )
            }
            TextButton(onClick = onManageRoster, modifier = Modifier.height(30.dp)) {
                Icon(
                    Icons.Default.Groups,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Accent
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    stringResource(R.string.shelf_roster),
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onLongPressDelete, modifier = Modifier.height(30.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = InkFaint
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    stringResource(R.string.shelf_remove),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint
                )
            }
            TextButton(
                onClick = onTranslation,
                enabled = !attachingTranslation,
                modifier = Modifier.height(30.dp)
            ) {
                Icon(
                    Icons.Filled.Translate,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (book.hasTranslation) Success else Accent
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    if (book.hasTranslation) stringResource(R.string.shelf_translation_ready)
                    else stringResource(R.string.shelf_translation_add),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (book.hasTranslation) Success else Accent
                )
            }
        }
    }
}

@Composable
private fun GlossaryDialog(
    book: Book,
    onLoad: suspend (String) -> BookGlossary,
    onAdd: suspend (String, String, String) -> BookGlossary,
    onUpdate: suspend (String, GlossaryEntry) -> BookGlossary,
    onRemove: suspend (String, String) -> BookGlossary,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        title = { Text(stringResource(R.string.glossary_title, book.title)) },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                // 共用编辑器自带滚动；这里只负责限高。
                GlossaryEditorBody(
                    books = listOf(book),
                    lockedBookId = book.id,
                    onLoad = onLoad,
                    onAdd = onAdd,
                    onUpdate = onUpdate,
                    onRemove = onRemove
                )
            }
        },
        containerColor = CardSurface,
        shape = CardShape
    )
}

private fun decodeSampledCover(file: File, targetWidth: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= targetWidth) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}
