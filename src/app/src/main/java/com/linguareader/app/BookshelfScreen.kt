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
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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
import com.linguareader.app.update.AppUpdateUiState
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

/** 「加译本 → 导入译本文件」的可选文件类型（中文译本）。 */
private val TRANSLATION_MIME_TYPES = arrayOf(
    "application/epub+zip",
    "application/zip",
    "application/octet-stream",
    "text/plain"
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
    onPrepareAiTranslation: (Book) -> Unit,
    onStartAiTranslation: (Book, String, String) -> Unit,
    onCancelAiTranslation: (Book) -> Unit,
    onDismissAiTranslationPrepare: () -> Unit,
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
    update: AppUpdateUiState,
    onCheckForUpdate: () -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelUpdateDownload: () -> Unit,
    onDismissMessage: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) onImport(it)
    }
    var deleteCandidate by remember { mutableStateOf<Book?>(null) }
    // 「加译本」用独立的文件选择器：回调里要知道是给哪本英文书配的译本。
    var pendingTranslationBook by remember { mutableStateOf<Book?>(null) }
    var detachTranslationCandidate by remember { mutableStateOf<Book?>(null) }
    // 「加译本」点击后先弹来源选择（导入文件 / AI 生成），再走各自分支。
    var translationChoiceCandidate by remember { mutableStateOf<Book?>(null) }
    val translationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = pendingTranslationBook
            pendingTranslationBook = null
            if (uri != null && target != null) onAttachTranslation(target, uri)
        }
    var showVocabulary by rememberSaveable { mutableStateOf(false) }
    var showAiDrawer by rememberSaveable { mutableStateOf(false) }
    var showUpdateSheet by rememberSaveable { mutableStateOf(false) }
    var glossaryBook by remember { mutableStateOf<Book?>(null) }
    var rosterBook by remember { mutableStateOf<Book?>(null) }

    // 书架外观：本地加载/保存（照听书设置弹层的先例，不走 AppViewModel），
    // 状态在这里持有，背景与弹层共用。
    val context = LocalContext.current
    var shelfAppearance by remember { mutableStateOf(ShelfAppearance.load(context)) }
    var showAppearanceSheet by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        if (shelfAppearance.isCustomized) {
            ShelfBackgroundLayer(shelfAppearance)
        }
        Scaffold(
            containerColor = if (shelfAppearance.isCustomized) Color.Transparent else Paper,
            topBar = {
                TopAppBar(
                    title = {
                        // 只显示书/词计数，不放应用名：顶栏动作多，放名字会把「语境阅读」挤成两行。
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
                            style = MaterialTheme.typography.titleMedium,
                            color = InkSoft
                        )
                    },
                    actions = {
                        IconButton(onClick = { showAppearanceSheet = true }) {
                            Icon(
                                Icons.Default.Palette,
                                contentDescription = stringResource(R.string.shelf_appearance_title),
                                modifier = Modifier.size(20.dp),
                                tint = if (shelfAppearance.isCustomized) Accent else InkSoft
                            )
                        }
                        // 检查更新入口：本会话发现新版时给个小红点。
                        IconButton(onClick = { showUpdateSheet = true }) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.update_sheet_title),
                                    modifier = Modifier.size(20.dp),
                                    tint = InkSoft
                                )
                                if (update.updateAvailable) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .background(Danger, CircleShape)
                                    )
                                }
                            }
                        }
                        // 生词本/书架切换：只留图标，名字在标题（次级视图）与无障碍描述里。
                        IconButton(
                            onClick = { showVocabulary = !showVocabulary },
                            modifier = Modifier.testTag(UiTags.SHELF_VOCABULARY)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = stringResource(
                                    if (showVocabulary) R.string.shelf_tab_shelf
                                    else R.string.shelf_vocabulary
                                ),
                                modifier = Modifier.size(20.dp),
                                tint = if (showVocabulary) Accent else InkSoft
                            )
                        }
                        if (!showVocabulary) {
                            // AI 中心：图标入口，名字在抽屉头部与无障碍描述里。
                            IconButton(
                                onClick = { showAiDrawer = true },
                                modifier = Modifier.testTag(UiTags.SHELF_AI_CENTER)
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = stringResource(R.string.shelf_ai_center),
                                    modifier = Modifier.size(20.dp),
                                    tint = if (state.aiSettings.enabled) Accent else InkSoft
                                )
                            }
                            IconButton(
                                onClick = { launcher.launch(IMPORT_MIME_TYPES) },
                                modifier = Modifier.testTag(UiTags.SHELF_IMPORT)
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.shelf_import),
                                    modifier = Modifier.size(22.dp),
                                    tint = Accent
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (shelfAppearance.isCustomized) Color.Transparent else Paper
                    )
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
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .testTag(UiTags.SHELF_GRID),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        items(state.books, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                aiEnabled = state.aiSettings.enabled,
                                aiStatus = state.aiStatuses[book.id],
                                attachingTranslation = book.id in state.attachingTranslation,
                                aiTranslationProgress = state.aiTranslationProgress[book.id],
                                containerAlpha = if (shelfAppearance.isCustomized) 0.9f else 1f,
                                onOpen = { onOpen(book) },
                                onGlossary = { glossaryBook = book },
                                onTranslation = {
                                    when {
                                        // 生成中：按钮变「取消生成」。
                                        book.id in state.aiTranslationProgress -> onCancelAiTranslation(book)
                                        book.hasTranslation -> detachTranslationCandidate = book
                                        else -> translationChoiceCandidate = book
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
    }

    if (showAppearanceSheet) {
        ShelfAppearanceSheet(
            appearance = shelfAppearance,
            onAppearanceChange = { next ->
                shelfAppearance = next
                ShelfAppearance.save(context, next)
            },
            onDismiss = { showAppearanceSheet = false }
        )
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

    translationChoiceCandidate?.let { book ->
        AlertDialog(
            onDismissRequest = { translationChoiceCandidate = null },
            confirmButton = {
                TextButton(onClick = {
                    translationChoiceCandidate = null
                    pendingTranslationBook = book
                    translationLauncher.launch(TRANSLATION_MIME_TYPES)
                }) { Text(stringResource(R.string.shelf_translation_choice_import)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    translationChoiceCandidate = null
                    onPrepareAiTranslation(book)
                }) { Text(stringResource(R.string.shelf_translation_choice_ai)) }
            },
            title = { Text(stringResource(R.string.shelf_translation_choice_title)) },
            text = { Text(stringResource(R.string.shelf_translation_choice_body)) },
            containerColor = CardSurface,
            shape = CardShape
        )
    }

    state.aiTranslationPrepare?.let { prepare ->
        // 确认框内的可编辑项：翻译模式（记住上次）与风格说明（书级，随开随改）。
        var mode by remember(prepare.book.id) { mutableStateOf(prepare.mode) }
        var styleNotes by remember(prepare.book.id) { mutableStateOf(prepare.styleNotes) }
        AlertDialog(
            onDismissRequest = onDismissAiTranslationPrepare,
            confirmButton = {
                TextButton(onClick = { onStartAiTranslation(prepare.book, mode, styleNotes) }) {
                    Text(stringResource(R.string.shelf_translation_ai_start))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissAiTranslationPrepare) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            title = { Text(stringResource(R.string.shelf_translation_ai_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.shelf_translation_ai_confirm_body,
                            prepare.book.title,
                            prepare.chapters,
                            prepare.batches,
                            prepare.glossaryTerms
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.shelf_translation_ai_mode_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = InkSoft
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mode == "standard",
                            onClick = { mode = "standard" }
                        )
                        Text(
                            stringResource(R.string.shelf_translation_ai_mode_standard),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.width(12.dp))
                        RadioButton(
                            selected = mode == "polish",
                            onClick = { mode = "polish" }
                        )
                        Text(
                            stringResource(R.string.shelf_translation_ai_mode_polish),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.shelf_translation_ai_style_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = InkSoft
                    )
                    OutlinedTextField(
                        value = styleNotes,
                        onValueChange = { styleNotes = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.shelf_translation_ai_style_placeholder),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            containerColor = CardSurface,
            shape = CardShape
        )
    }

    if (showUpdateSheet) {
        UpdateSheet(
            update = update,
            onDismiss = { showUpdateSheet = false },
            onCheckNow = onCheckForUpdate,
            onAutoCheckChange = onAutoCheckChange,
            onDownload = onDownloadUpdate,
            onCancelDownload = onCancelUpdateDownload
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
 * 书架自定义背景层：自定义图片（Crop 铺满）优先，否则用预设渐变；
 * 最上面统一盖一层当前调色板纸色蒙版（浓度可在弹层调），保证顶栏与文字可读。
 */
@Composable
private fun ShelfBackgroundLayer(appearance: ShelfAppearance) {
    val scrim = Paper.copy(alpha = appearance.dimOpacity)
    Box(Modifier.fillMaxSize()) {
        val context = LocalContext.current
        val imageFile = appearance.imageFile
            ?.let { ShelfBackgroundStore.backgroundFile(context, it) }
            ?.takeIf { it.isFile }
        if (imageFile != null) {
            val bitmap by produceState<android.graphics.Bitmap?>(null, imageFile.lastModified()) {
                value = withContext(Dispatchers.IO) {
                    runCatching { decodeSampledCover(imageFile, 1080) }.getOrNull()
                }
            }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        } else {
            appearance.preset?.let { preset ->
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(preset.topColor), Color(preset.bottomColor)))
                    )
                )
            }
        }
        Box(Modifier.fillMaxSize().background(scrim))
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
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag(UiTags.SHELF_EMPTY),
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
    aiTranslationProgress: AiTranslationProgress?,
    containerAlpha: Float = 1f,
    onOpen: () -> Unit,
    onGlossary: () -> Unit,
    onManageRoster: () -> Unit,
    onTranslation: () -> Unit,
    onLongPressDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .testTag(UiTags.bookCard(book.id))
            .clickable(onClick = onOpen)
    ) {
        Box {
            Card(
                modifier = Modifier.fillMaxWidth().height(205.dp),
                shape = CardShape,
                // 自定义背景时书卡略透，与背景融合；有封面的书本来就完全被图盖住。
                colors = CardDefaults.cardColors(
                    containerColor = BookCoverFallback.copy(alpha = containerAlpha)
                ),
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
        if (attachingTranslation || book.hasTranslation || aiTranslationProgress != null) {
            val aiProgress = aiTranslationProgress
            Text(
                when {
                    attachingTranslation -> stringResource(R.string.shelf_translation_aligning)
                    aiProgress?.preparing == true ->
                        stringResource(R.string.shelf_translation_ai_preparing)
                    aiProgress?.aligning == true ->
                        stringResource(R.string.shelf_translation_ai_aligning)
                    aiProgress?.polish == true ->
                        stringResource(R.string.shelf_translation_ai_polish_progress, aiProgress.percent)
                    aiProgress != null ->
                        stringResource(R.string.shelf_translation_ai_progress, aiProgress.percent)
                    else -> book.translationTitle.ifBlank { stringResource(R.string.shelf_translation_ready) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    attachingTranslation || aiProgress != null -> InkFaint
                    else -> Success
                },
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
                    tint = when {
                        aiTranslationProgress != null -> InkFaint
                        book.hasTranslation -> Success
                        else -> Accent
                    }
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    when {
                        aiTranslationProgress != null ->
                            stringResource(R.string.shelf_translation_ai_cancel)
                        book.hasTranslation -> stringResource(R.string.shelf_translation_ready)
                        else -> stringResource(R.string.shelf_translation_add)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        aiTranslationProgress != null -> InkFaint
                        book.hasTranslation -> Success
                        else -> Accent
                    }
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

@Preview(name = "空书架 · 日间", showBackground = true, widthDp = 380, heightDp = 620)
@Composable
private fun EmptyBookshelfLightPreview() {
    PreviewScaffold { EmptyBookshelf(onImport = {}) }
}

@Preview(name = "空书架 · 夜间", showBackground = true, widthDp = 380, heightDp = 620)
@Composable
private fun EmptyBookshelfDarkPreview() {
    PreviewScaffold(dark = true) { EmptyBookshelf(onImport = {}) }
}

private fun decodeSampledCover(file: File, targetWidth: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= targetWidth) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}
