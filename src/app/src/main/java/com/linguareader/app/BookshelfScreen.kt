package com.linguareader.app

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Translate
import com.linguareader.shared.sync.CloudBook
import com.linguareader.shared.sync.SyncSettings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.linguareader.app.components.EmptyState
import com.linguareader.app.components.LinguaScaffold
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.LinguaTopBarAction
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.components.StatusChip
import com.linguareader.app.components.StatusChipTone
import com.linguareader.app.skin.LocalDesignTokens
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.linguareader.shared.update.AppUpdateUiState
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

/**
 * 「手动 AI 翻译 → 导入翻译结果」的可选文件类型。agent 产出的结果文件可能是
 * .json，也可能被存成 .txt / 未知类型（octet-stream），三种都放行。
 */
private val MANUAL_RESULT_MIME_TYPES = arrayOf(
    "application/json",
    "text/plain",
    "application/octet-stream"
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
    /** 用当前对齐器重跑该书档案（不重新翻译）；档案过期时菜单会主动提示。 */
    onRealignTranslation: (Book) -> Unit = {},
    onPrepareAiTranslation: (Book) -> Unit,
    onStartAiTranslation: (Book, String, String) -> Unit,
    onCancelAiTranslation: (Book) -> Unit,
    onDismissAiTranslationPrepare: () -> Unit,
    onPrepareManualTranslation: (Book) -> Unit,
    onDismissManualTranslationPrepare: () -> Unit,
    /** 构建手动任务文件（IO），就绪后 state.pendingManualExport 非空、界面取走并弹保存面板。 */
    onExportManualTranslation: (Book, String) -> Unit,
    /** 取走构建好的任务文件（状态即刻清空，避免重建组合后重放面板），返回 null = 无待导出。 */
    onTakeManualExport: () -> PendingManualExport?,
    /** 手动任务文件的 SAF 保存面板回调。 */
    onManualExportReady: (android.net.Uri) -> Unit,
    onCancelManualExport: () -> Unit,
    onImportManualResults: (Book, List<android.net.Uri>) -> Unit,
    onClearManualImportRejected: () -> Unit,
    onAiSettingsChange: (AiSettings) -> Unit,
    /** 存储占用页面（D2.4b）：打开时扫一次盘，孤儿只报不删。 */
    onRefreshStorage: () -> Unit = {},
    onCleanOrphans: () -> Unit = {},
    /** 资源包（M1）：安装 / 切换当前词典包 / 卸载 / 校验。 */
    onInstallPack: (android.net.Uri) -> Unit = {},
    onSetActiveDictionary: (String?) -> Unit = {},
    onUninstallPack: (String) -> Unit = {},
    onVerifyPack: (String) -> Unit = {},
    /** 资源包（7-9）：清理未登记目录 / `.tmp` 残留。 */
    onCleanPackResiduals: () -> Unit = {},
    /** Q2-c03：从 GitHub Releases 获取资源包。 */
    onLoadRemotePacks: () -> Unit = {},
    onInstallRemotePack: (com.linguareader.shared.update.GitHubReleaseParser.PackAsset) -> Unit = {},
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
    onDismissMessage: () -> Unit,
    uiAnimSpeed: UiAnimSpeed,
    onAnimSpeedChange: (UiAnimSpeed) -> Unit,
    /** 云同步（F-160）：设置保存、登录、立即同步、登出。 */
    onSaveSyncSettings: (SyncSettings) -> Unit = {},
    onSyncLogin: (SyncSettings, String) -> Unit = { _, _ -> },
    onSyncNow: () -> Unit = {},
    onSyncLogout: () -> Unit = {},
    /** 云端书单（书籍正文 blob）：拉取与点选下载。 */
    onRefreshCloudBooks: () -> Unit = {},
    onDownloadCloudBook: (CloudBook) -> Unit = { _ -> },
    /** 同步明细（F-160 二级界面）：重建本地快照，不发网络。 */
    onRefreshSyncDetail: () -> Unit = {},
    /** Q2-c07：书架内改阅读主题时通知外壳，整体配色即时生效。 */
    onReaderThemeChanged: (com.linguareader.app.data.ReaderTheme) -> Unit
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) onImport(it)
    }
    var deleteCandidate by remember { mutableStateOf<Book?>(null) }
    // 「加译本」用独立的文件选择器：回调里要知道是给哪本英文书配的译本。
    var pendingTranslationBook by remember { mutableStateOf<Book?>(null) }
    var detachTranslationCandidate by remember { mutableStateOf<Book?>(null) }
    var aiTranslationManageCandidate by remember { mutableStateOf<Book?>(null) }
    // 「加译本」点击后先弹来源选择（导入文件 / AI 生成），再走各自分支。
    var translationChoiceCandidate by remember { mutableStateOf<Book?>(null) }
    val translationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = pendingTranslationBook
            pendingTranslationBook = null
            if (uri != null && target != null) onAttachTranslation(target, uri)
        }
    // 手动 AI 翻译：任务文件导出（SAF 保存面板）与结果文件导入（可多选）。
    // 任务文件内容由 ViewModel 异步构建，构建完成后在 LaunchedEffect 里先取走
    // 状态再弹面板——状态清空后，旋转屏幕重建组合不会重放出第二个面板。
    var pendingManualImportBook by remember { mutableStateOf<Book?>(null) }
    val manualExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) onManualExportReady(uri) else onCancelManualExport()
    }
    val manualImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val target = pendingManualImportBook
        pendingManualImportBook = null
        if (!uris.isNullOrEmpty() && target != null) onImportManualResults(target, uris)
    }
    LaunchedEffect(state.pendingManualExport) {
        val pending = onTakeManualExport() ?: return@LaunchedEffect
        manualExportLauncher.launch(pending.fileName)
    }
    var showVocabulary by rememberSaveable { mutableStateOf(false) }
    // Q2-c08：生词本是次级视图，系统返回/左滑应回书架，而不是直接退出应用。
    BackHandler(enabled = showVocabulary) { showVocabulary = false }
    // 同步明细同样是次级视图：返回箭头与标题由顶栏提供（Lead 裁决 A′）。
    var showSyncDetail by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSyncDetail) { showSyncDetail = false }
    var showAiDrawer by rememberSaveable { mutableStateOf(false) }
    var showUpdateSheet by rememberSaveable { mutableStateOf(false) }
    var showStorageSheet by rememberSaveable { mutableStateOf(false) }
    var showPacksSheet by rememberSaveable { mutableStateOf(false) }
    var showSyncSheet by rememberSaveable { mutableStateOf(false) }
    // 同步明细页的刷新：先重建本地快照（纯本地），已启用同步时再拉云端书单（**这步会出网**）。
    // 命名刻意与 AppViewModel.refreshSyncDetail()（纯本地、零网络）区分，避免误读。
    val refreshDetailAndCloud = {
        onRefreshSyncDetail()
        if (state.syncSettings.enabled) onRefreshCloudBooks()
    }
    var glossaryBook by remember { mutableStateOf<Book?>(null) }
    var rosterBook by remember { mutableStateOf<Book?>(null) }

    // 书架外观：本地加载/保存（照听书设置弹层的先例，不走 AppViewModel），
    // 状态在这里持有，背景与弹层共用。
    val context = LocalContext.current
    var shelfAppearance by remember { mutableStateOf(ShelfAppearance.load(context)) }
    var showAppearanceSheet by rememberSaveable { mutableStateOf(false) }

    // 设计 token：本屏所有样式从这里取（P3 硬约束）。背景层与书卡仍用旧调色板 API
    // 的语义色名（Ink/Accent…）——它们由同一份 SkinSpec 派生，与 token 永远同源。
    val t = LocalDesignTokens.current

    Box(Modifier.fillMaxSize()) {
        if (shelfAppearance.isCustomized) {
            ShelfBackgroundLayer(shelfAppearance)
        }
        // 外壳换成原语 LinguaScaffold：顶栏行高、图标尺寸、返回箭头语义（AutoMirrored）
        // 与窗口 insets 全部由组件统一；本屏只提供「标题文本 + 动作槽 + 内容槽」。
        //
        // containerColor 只在**有自定义背景**时传透明 —— 背景层画在这个 Box 的最底层，
        // 要能透过外壳露出来（那是书架外观设置的全部意义）。其余时候传 null，
        // 组件用 token 的 paper，与迁移前的 Scaffold(containerColor = Paper) 等价。
        LinguaScaffold(
            // 文案一个字都没改：同步明细页报页面名，其余报「N 本书 / N 个词」。
            title = if (showSyncDetail) {
                stringResource(R.string.sync_detail_title)
            } else if (showVocabulary) {
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
            // 同步明细页是二级视图：顶栏是唯一返回位（系统返回由 BackHandler 兜底）。
            onBack = if (showSyncDetail) {
                { showSyncDetail = false }
            } else {
                null
            },
            containerColor = if (shelfAppearance.isCustomized) Color.Transparent else null,
            actions = {
                // 同步明细页独占顶栏：书架动作与生词本切换图标全部隐藏（Lead 裁决 A′）。
                if (!showSyncDetail) {
                    // Q2-c08：生词本是独立的次级视图，不再显示书架专属动作
                    // （存储占用 / 外观 / 检查更新）。此前这些图标在生词本里照旧渲染，
                    // 用户看到的是「生词本还有一部分书架的顶部图标」。
                    if (!showVocabulary) {
                        // 云同步（F-160）：自托管服务端的登录与手动同步入口。
                        LinguaTopBarAction(
                            icon = Icons.Default.Cloud,
                            contentDescription = stringResource(R.string.sync_entry),
                            onClick = { showSyncSheet = true }
                        )
                        // 存储占用：打开即扫盘（不在启动时扫，那会拖慢冷启动）。
                        LinguaTopBarAction(
                            icon = Icons.Default.Storage,
                            contentDescription = stringResource(R.string.storage_title),
                            onClick = {
                                showStorageSheet = true
                                onRefreshStorage()
                            }
                        )
                        // 激活态用 accent：这一项当前「已生效」（装了自定义背景）。
                        LinguaTopBarAction(
                            icon = Icons.Default.Palette,
                            contentDescription = stringResource(R.string.shelf_appearance_title),
                            onClick = { showAppearanceSheet = true },
                            tint = if (shelfAppearance.isCustomized) t.colors.accent else null
                        )
                        // 检查更新入口：本会话发现新版时给个小红点。
                        // 读屏语义也在 contentDescription 里（只画红点读屏用户收不到）。
                        LinguaTopBarAction(
                            icon = Icons.Default.Refresh,
                            contentDescription = if (update.updateAvailable) {
                                // 读屏语义：红点本身对无障碍用户不可见，必须落到描述里。
                                // 复用既有文案「发现新版本 %s」，不为一个红点新增 key。
                                stringResource(
                                    R.string.update_available_title,
                                    update.info?.versionName ?: ""
                                )
                            } else {
                                stringResource(R.string.update_sheet_title)
                            },
                            onClick = { showUpdateSheet = true },
                            badge = update.updateAvailable
                        )
                    }

                    // 生词本/书架切换：只留图标，名字在标题（次级视图）与无障碍描述里。
                    LinguaTopBarAction(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = stringResource(
                            if (showVocabulary) R.string.shelf_tab_shelf
                            else R.string.shelf_vocabulary
                        ),
                        onClick = { showVocabulary = !showVocabulary },
                        modifier = Modifier.testTag(UiTags.SHELF_VOCABULARY),
                        tint = if (showVocabulary) t.colors.accent else null
                    )
                    if (!showVocabulary) {
                        // AI 中心：图标入口，名字在抽屉头部与无障碍描述里。
                        LinguaTopBarAction(
                            icon = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.shelf_ai_center),
                            onClick = { showAiDrawer = true },
                            modifier = Modifier.testTag(UiTags.SHELF_AI_CENTER),
                            tint = if (state.aiSettings.enabled) t.colors.accent else null
                        )
                        // 导入是这一屏的主动作：恒定 accent（未激活态的语义不适用）。
                        LinguaTopBarAction(
                            icon = Icons.Default.Add,
                            contentDescription = stringResource(R.string.shelf_import),
                            onClick = { launcher.launch(IMPORT_MIME_TYPES) },
                            modifier = Modifier.testTag(UiTags.SHELF_IMPORT),
                            tint = t.colors.accent
                        )
                    }
                }
            }
        ) {
            // 内容区不再需要给顶栏留出 padding：LinguaScaffold 的顶栏是 Column 的第一段，
            // 内容槽天然接在它下面；窗口 insets 也已由组件处理。
            Box(Modifier.fillMaxSize()) {
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
                } else if (showSyncDetail) {
                    // 同步明细（F-160 二级界面）：离线渲染快照，拉取只走 onRefresh。
                    SyncDetailScreen(
                        detail = state.syncDetail,
                        loading = state.syncDetailLoading,
                        settings = state.syncSettings,
                        status = state.syncStatus,
                        lastSyncAt = state.lastSyncAt,
                        busy = state.syncBusy,
                        onRefresh = refreshDetailAndCloud,
                        onSyncNow = onSyncNow,
                        onDownloadCloudBook = onDownloadCloudBook
                    )
                } else if (state.books.isEmpty() && !state.loading) {
                    EmptyBookshelf(onImport = { launcher.launch(IMPORT_MIME_TYPES) })
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(ShelfColumnMinWidth),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = t.spacing.m)
                            .testTag(UiTags.SHELF_GRID),
                        horizontalArrangement = Arrangement.spacedBy(ShelfColumnGap),
                        verticalArrangement = Arrangement.spacedBy(ShelfRowGap)
                    ) {
                        items(state.books, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                aiEnabled = state.aiSettings.enabled,
                                aiStatus = state.aiStatuses[book.id],
                                attachingTranslation = book.id in state.attachingTranslation,
                                aiTranslationProgress = state.aiTranslationProgress[book.id],
                                translationOutdated = book.id in state.outdatedTranslations,
                                containerAlpha = if (shelfAppearance.isCustomized) 0.9f else 1f,
                                onOpen = { onOpen(book) },
                                onGlossary = { glossaryBook = book },
                                onTranslation = {
                                    when {
                                        // 生成中：按钮变「取消生成」。
                                        book.id in state.aiTranslationProgress -> onCancelAiTranslation(book)
                                        // AI 译本：可重新生成（检查点复用、只补失败批）也可删除。
                                        book.hasTranslation && book.isAiTranslation ->
                                            aiTranslationManageCandidate = book
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
                    // 半透明纸底压在书架内容上：只是「正在整理」的提示，不是遮断式loading 页。
                    Box(
                        Modifier.fillMaxSize().background(t.colors.paper.copy(alpha = LoadingScrimAlpha)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = t.colors.accent)
                            Spacer(Modifier.height(t.spacing.m))
                            Text(stringResource(R.string.shelf_loading), color = t.colors.inkSoft)
                        }
                    }
                }
            }
        }
    }

    if (showStorageSheet) {
        StorageSheet(
            report = state.storage,
            scanning = state.storageScanning,
            packCount = state.packs.items.size,
            packBytes = state.packs.totalBytes,
            onRescan = onRefreshStorage,
            onCleanOrphans = onCleanOrphans,
            onManagePacks = {
                showStorageSheet = false
                showPacksSheet = true
            },
            onDismiss = { showStorageSheet = false }
        )
    }

    if (showSyncSheet) {
        SyncSheet(
            settings = state.syncSettings,
            status = state.syncStatus,
            busy = state.syncBusy,
            onSave = onSaveSyncSettings,
            onLogin = onSyncLogin,
            onSyncNow = onSyncNow,
            onLogout = onSyncLogout,
            onDismiss = { showSyncSheet = false },
            // 打开明细页：先关弹层，再进二级页并立即刷一次（本地快照 + 已启用时拉云端）。
            onOpenDetail = {
                showSyncSheet = false
                showSyncDetail = true
                refreshDetailAndCloud()
            },
            cloudBooks = state.cloudBooks,
            cloudBooksLoaded = state.cloudBooksLoaded,
            cloudBooksLoading = state.cloudBooksLoading,
            onRefreshCloudBooks = onRefreshCloudBooks,
            onDownloadCloudBook = onDownloadCloudBook
        )
    }

    if (showPacksSheet) {
        PacksSheet(
            state = state.packs,
            onInstall = onInstallPack,
            onSetActiveDictionary = onSetActiveDictionary,
            onUninstall = onUninstallPack,
            onVerify = onVerifyPack,
            onCleanResiduals = onCleanPackResiduals,
            onLoadRemotePacks = onLoadRemotePacks,
            onInstallRemotePack = onInstallRemotePack,
            onDismiss = { showPacksSheet = false }
        )
    }

    if (showAppearanceSheet) {
        ShelfAppearanceSheet(
            appearance = shelfAppearance,
            onAppearanceChange = { next ->
                shelfAppearance = next
                ShelfAppearance.save(context, next)
            },
            animSpeed = uiAnimSpeed,
            onAnimSpeedChange = onAnimSpeedChange,
            onReaderThemeChange = onReaderThemeChanged,
            onDismiss = { showAppearanceSheet = false }
        )
    }

    state.message?.let {
        ShelfNoticeDialog(
            title = state.messageTitle.ifBlank { stringResource(R.string.common_notice_title) },
            body = it,
            confirmText = stringResource(R.string.common_got_it),
            onConfirm = onDismissMessage,
            onDismiss = onDismissMessage
        )
    }

    deleteCandidate?.let { book ->
        // 删书连带删该书生词，且不可恢复 —— 条数必须摆在按下去之前。
        val wordCount = state.savedWords.count { it.bookId == book.id }
        ShelfNoticeDialog(
            title = stringResource(R.string.shelf_delete_title, book.title),
            body = if (wordCount == 0) {
                stringResource(R.string.shelf_delete_body)
            } else {
                pluralStringResource(R.plurals.shelf_delete_body_words, wordCount, wordCount)
            },
            confirmText = stringResource(R.string.common_delete),
            dangerConfirm = true,
            onConfirm = {
                onDelete(book)
                deleteCandidate = null
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { deleteCandidate = null }
        )
    }

    detachTranslationCandidate?.let { book ->
        val t = LocalDesignTokens.current
        ShelfNoticeDialog(
            title = stringResource(R.string.shelf_translation_remove_title, book.title),
            confirmText = stringResource(R.string.common_delete),
            dangerConfirm = true,
            onConfirm = {
                onDetachTranslation(book)
                detachTranslationCandidate = null
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { detachTranslationCandidate = null },
            bodyContent = {
                Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                    Text(
                        stringResource(R.string.shelf_translation_remove_body),
                        style = t.typography.body,
                        color = t.colors.ink
                    )
                    RealignTranslationEntry(
                        outdated = book.id in state.outdatedTranslations,
                        onRealign = {
                            detachTranslationCandidate = null
                            onRealignTranslation(book)
                        }
                    )
                }
            }
        )
    }

    // AI 译本的管理对话框：重新生成（检查点复用）或删除。
    // AI 译本的管理对话框：**菜单类**，正文里有 3 个动作。
    // 不套 ShelfNoticeDialog：它的按钮槽是「确认 + 取消」，而这里是「重新生成 + 取消」
    // 加正文内 3 个动作 —— 结构不同，硬套会得到一个要传菜单项的万能接口。
    // 本处只换样式（容器色 / 圆角 / 字梯 / 间距），正文结构与动作顺序一行未动。
    aiTranslationManageCandidate?.let { book ->
        val t = LocalDesignTokens.current
        AlertDialog(
            onDismissRequest = { aiTranslationManageCandidate = null },
            confirmButton = {
                TextButton(onClick = {
                    aiTranslationManageCandidate = null
                    onPrepareAiTranslation(book)
                }) { Text(stringResource(R.string.shelf_translation_manage_regenerate)) }
            },
            dismissButton = {
                TextButton(onClick = { aiTranslationManageCandidate = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            title = {
                Text(
                    stringResource(R.string.shelf_translation_manage_title),
                    style = t.typography.title,
                    color = t.colors.ink
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                    Text(
                        stringResource(R.string.shelf_translation_manage_body),
                        style = t.typography.body,
                        color = t.colors.ink
                    )
                    RealignTranslationEntry(
                        outdated = book.id in state.outdatedTranslations,
                        onRealign = {
                            aiTranslationManageCandidate = null
                            onRealignTranslation(book)
                        }
                    )
                    // 手动补翻/修正入口：agent 返工后的新结果文件从这里导入
                    //（检查点按批覆盖，齐批自动重新生成），不必删掉译本重来。
                    TextButton(onClick = {
                        aiTranslationManageCandidate = null
                        onPrepareManualTranslation(book)
                    }) {
                        Text(stringResource(R.string.shelf_translation_manage_import))
                    }
                    TextButton(onClick = {
                        aiTranslationManageCandidate = null
                        detachTranslationCandidate = book
                    }) {
                        Text(
                            stringResource(R.string.shelf_translation_manage_remove),
                            color = t.colors.danger
                        )
                    }
                }
            },
            containerColor = t.colors.cardSurface,
            shape = t.cardShape
        )
    }

    // 「加译本来源」：**选择类**。两个按钮都是「选一条路」，没有主次之分，
    // 所以两个都走 TextButton 的 accent（迁移前 cancel 槽用的是 Material 默认色，
    // 这里与生词本「复习全部」同口径：文字按钮只有一种主色，主次由位置与文案承担）。
    translationChoiceCandidate?.let { book ->
        val t = LocalDesignTokens.current
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
            title = {
                Text(
                    stringResource(R.string.shelf_translation_choice_title),
                    style = t.typography.title,
                    color = t.colors.ink
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                    Text(
                        stringResource(R.string.shelf_translation_choice_body),
                        style = t.typography.body,
                        color = t.colors.ink
                    )
                    // 第三条路：手动 AI 翻译——导出任务文件交给外部 agent，再把
                    // 结果文件导回来。不要求应用内配置 API Key。
                    TextButton(onClick = {
                        translationChoiceCandidate = null
                        onPrepareManualTranslation(book)
                    }) {
                        Text(stringResource(R.string.shelf_translation_choice_manual))
                    }
                }
            },
            containerColor = t.colors.cardSurface,
            shape = t.cardShape
        )
    }

    // 「手动 AI 翻译」：**表单类**（正文含输入框），结构与确认类不同，不套封装。
    // 只换样式：容器色 / 圆角 / 字梯 / 间距。正文结构与动作顺序一行未动。
    state.manualTranslationPrepare?.let { prepare ->
        var styleNotes by remember(prepare.book.id) { mutableStateOf(prepare.styleNotes) }
        val t = LocalDesignTokens.current
        AlertDialog(
            onDismissRequest = onDismissManualTranslationPrepare,
            confirmButton = {
                TextButton(onClick = { onExportManualTranslation(prepare.book, styleNotes) }) {
                    Text(stringResource(R.string.shelf_translation_manual_export))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissManualTranslationPrepare) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            title = {
                Text(
                    stringResource(R.string.shelf_translation_manual_title),
                    style = t.typography.title,
                    color = t.colors.ink
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                    Text(
                        stringResource(R.string.shelf_translation_manual_body, prepare.batches),
                        style = t.typography.body,
                        color = t.colors.ink
                    )
                    if (prepare.coveredBatches > 0) {
                        Text(
                            stringResource(
                                R.string.shelf_translation_manual_coverage,
                                prepare.coveredBatches,
                                prepare.batches
                            ),
                            style = t.typography.caption,
                            color = t.colors.inkSoft
                        )
                    }
                    Spacer(Modifier.height(t.spacing.xs))
                    Text(
                        stringResource(R.string.shelf_translation_ai_style_label),
                        style = t.typography.label,
                        color = t.colors.inkSoft
                    )
                    OutlinedTextField(
                        value = styleNotes,
                        onValueChange = { styleNotes = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.shelf_translation_ai_style_placeholder),
                                style = t.typography.caption
                            )
                        },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 导入入口放在正文区（AlertDialog 只有两个按钮槽位，加第三个
                    // 动作的管理对话框先例见「AI 译本管理」）。
                    TextButton(onClick = {
                        pendingManualImportBook = prepare.book
                        onDismissManualTranslationPrepare()
                        manualImportLauncher.launch(MANUAL_RESULT_MIME_TYPES)
                    }) {
                        Text(stringResource(R.string.shelf_translation_manual_import))
                    }
                }
            },
            containerColor = t.colors.cardSurface,
            shape = t.cardShape
        )
    }

    // 手动导入的逐条拒绝原因：「拒绝 N 条」的条数不告诉用户该改什么，
    // 典型错误（agent 把段落编号重新编号）只有看到具体原因才能定位。
    state.manualImportRejected?.let { rejected ->
        val t = LocalDesignTokens.current
        ShelfNoticeDialog(
            title = stringResource(R.string.shelf_translation_manual_rejected_title),
            confirmText = stringResource(R.string.common_got_it),
            onConfirm = onClearManualImportRejected,
            onDismiss = onClearManualImportRejected,
            bodyContent = {
                // 限高 + 自带滚动：拒绝原因可能有几十条，"拒绝 N 条"不告诉用户该改什么，
                // 典型错误（agent 把段落编号重新编号）只有看到具体原因才能定位。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = ManualRejectedMaxHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        stringResource(R.string.shelf_translation_manual_rejected_hint),
                        style = t.typography.caption,
                        color = t.colors.inkSoft
                    )
                    Spacer(Modifier.height(t.spacing.s))
                    rejected.forEach { reason ->
                        Text(reason, style = t.typography.caption, color = t.colors.ink)
                        Spacer(Modifier.height(t.spacing.xs))
                    }
                }
            }
        )
    }

    // 「AI 生成译本」确认：**表单类**（单选 + 输入框），结构与确认类不同，不套封装。
    // 只换样式；翻译模式的两个取值（"standard"/"polish"）是与 ViewModel 的契约字符串，
    // 原样保留。
    state.aiTranslationPrepare?.let { prepare ->
        // 确认框内的可编辑项：翻译模式（记住上次）与风格说明（书级，随开随改）。
        var mode by remember(prepare.book.id) { mutableStateOf(prepare.mode) }
        var styleNotes by remember(prepare.book.id) { mutableStateOf(prepare.styleNotes) }
        val t = LocalDesignTokens.current
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
            title = {
                Text(
                    stringResource(R.string.shelf_translation_ai_title),
                    style = t.typography.title,
                    color = t.colors.ink
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                    Text(
                        stringResource(
                            R.string.shelf_translation_ai_confirm_body,
                            prepare.book.title,
                            state.aiSettings.providerDisplayName,
                            prepare.chapters,
                            prepare.batches,
                            prepare.glossaryTerms,
                            prepare.glossaryInjected
                        ),
                        style = t.typography.body,
                        color = t.colors.ink
                    )
                    if (prepare.oversizedParagraphs > 0) {
                        Text(
                            stringResource(
                                R.string.shelf_translation_ai_oversized,
                                prepare.oversizedParagraphs
                            ),
                            style = t.typography.caption,
                            color = t.colors.inkSoft
                        )
                    }
                    Spacer(Modifier.height(DialogTightGap))
                    Text(
                        stringResource(R.string.shelf_translation_ai_mode_label),
                        style = t.typography.label,
                        color = t.colors.inkSoft
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = mode == "standard",
                            onClick = { mode = "standard" }
                        )
                        Text(
                            stringResource(R.string.shelf_translation_ai_mode_standard),
                            style = t.typography.caption
                        )
                        Spacer(Modifier.width(t.spacing.s))
                        RadioButton(
                            selected = mode == "polish",
                            onClick = { mode = "polish" }
                        )
                        Text(
                            stringResource(R.string.shelf_translation_ai_mode_polish),
                            style = t.typography.caption
                        )
                    }
                    Spacer(Modifier.height(DialogTightGap))
                    Text(
                        stringResource(R.string.shelf_translation_ai_style_label),
                        style = t.typography.label,
                        color = t.colors.inkSoft
                    )
                    OutlinedTextField(
                        value = styleNotes,
                        onValueChange = { styleNotes = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.shelf_translation_ai_style_placeholder),
                                style = t.typography.caption
                            )
                        },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            containerColor = t.colors.cardSurface,
            shape = t.cardShape
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
 * 译本菜单里的「重新对齐」入口（出版译本、AI 译本两个管理对话框共用）。
 *
 * 档案由旧版对齐器写出时换成过期提示（[R.string.shelf_translation_realign_outdated]）；
 * 动作在 ViewModel 里跑，期间书卡显示「对齐中…」，完成后出全局 Snackbar。
 */
@Composable
private fun RealignTranslationEntry(outdated: Boolean, onRealign: () -> Unit) {
    val t = LocalDesignTokens.current
    TextButton(onClick = onRealign) {
        Text(stringResource(R.string.shelf_translation_realign))
    }
    Text(
        stringResource(
            if (outdated) R.string.shelf_translation_realign_outdated
            else R.string.shelf_translation_realign_hint
        ),
        style = t.typography.caption,
        color = if (outdated) t.colors.accent else t.colors.inkSoft
    )
}

/**
 * 书架自定义背景层：自定义图片（Crop 铺满）优先，否则用预设渐变；
 * 最上面统一盖一层当前调色板纸色蒙版（浓度可在弹层调），保证顶栏与文字可读。
 */
@Composable
private fun ShelfBackgroundLayer(appearance: ShelfAppearance) {
    val context = LocalContext.current
    val t = LocalDesignTokens.current
    // 深色外壳下浅色预设不可读（审查 5-1：叠加后对比度低到 1.02–2.56:1），
    // 此时预设整体不渲染，只留蒙版，等同回到调色板纸色；见 ShelfAppearanceSheet 的同类判定。
    val dark = chromeIsDark(storedReaderTheme(context), isSystemInDarkTheme())
    // 深色外壳 + 自定义背景时加重蒙版，保住顶栏与文字可读。
    val scrimAlpha = if (dark) maxOf(appearance.dimOpacity, DARK_CHROME_MIN_SCRIM) else appearance.dimOpacity
    val scrim = t.colors.paper.copy(alpha = scrimAlpha)
    Box(Modifier.fillMaxSize()) {
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
        } else if (!dark) {
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
 * 深色外壳 + 自定义背景时的蒙版下限。0.35（用户默认档）在深色墨色下不足以把浅色背景
 * 压到可读；0.72 与 [BookshelfScreen] 无书空态所用的遮罩同档（审查 5-1 建议"提到能保
 * 4.5:1 需 ~0.8，等于预设失去意义"，故这里仅对图片背景兜底，不作用于预设）。
 */
private const val DARK_CHROME_MIN_SCRIM = 0.72f

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
    // 外壳换成统一弹层 [LinguaSheet]（纸色 / 圆角 / 拖柄 / 底部留白一并统一）。
    // ⚠️ 滚动**必须由内容体自己拥有**：LinguaSheet 的内容槽只是普通 Column，不给滚动，
    // 而 MultiVoiceSection 在手机上远超一屏。迁移前是 ModalBottomSheet 里手写
    // verticalScroll —— 换容器时最容易把它一起删掉（PacksSheet 就踩过这个坑）。
    LinguaSheet(onDismiss = onDismiss, contentArrangement = Arrangement.Top) {
        // 水平内边距与底部留白由 LinguaSheet 统一给（spacing.l / spacing.xl），
        // 这里不再自己补 —— 迁移前那两处 20/30dp 是 ModalBottomSheet 裸壳的补偿。
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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

/**
 * 空书架引导（P3 迁移第一笔）。
 *
 * 换成原语 [EmptyState]：图标底盘、标题/说明的字号与行宽、动作按钮的间距全部由
 * 设计系统统一，本屏不再各写一套。**文案一个字没改**（三条都在 strings.xml 里）。
 *
 * 保留了外层这个 Column：它负责「在剩余空间里垂直居中」，而 [EmptyState] 自己只
 * fillMaxWidth（与生词本空态同一处理，见 VocabularyScreen.VocabList 的注释）。
 */
@Composable
private fun EmptyBookshelf(onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.SHELF_EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            title = stringResource(R.string.shelf_empty_title),
            body = stringResource(R.string.shelf_empty_body),
            action = {
                PrimaryButton(
                    text = stringResource(R.string.shelf_empty_action),
                    onClick = onImport,
                    icon = Icons.Default.Add
                )
            }
        )
    }
}

@Composable
private fun BookCard(
    book: Book,
    aiEnabled: Boolean,
    aiStatus: AiBookStatus?,
    attachingTranslation: Boolean,
    aiTranslationProgress: AiTranslationProgress?,
    /** 对齐档案由更旧版对齐器写出：书卡上要看得见（入口在译本菜单里）。 */
    translationOutdated: Boolean = false,
    containerAlpha: Float = 1f,
    onOpen: () -> Unit,
    onGlossary: () -> Unit,
    onManageRoster: () -> Unit,
    onTranslation: () -> Unit,
    onLongPressDelete: () -> Unit
) {
    // 书卡是本屏最密的区域：书封 + 标题 + 作者/进度 + 至多三条状态徽章 + 两行操作按钮。
    // 样式一律取 token；BookCard 自身不接受颜色参数（与 LinguaCard 的约束同口径）。
    val t = LocalDesignTokens.current
    Column(
        modifier = Modifier
            .testTag(UiTags.bookCard(book.id))
            .clickable(onClick = onOpen)
    ) {
        Box {
            // 书封用 Card 而不是 LinguaCard：LinguaCard 内部固定带 CardTitleRow 式内边距、
            // 且把内容包在 Column 里；书封需要「图/Crop 铺满整块、无内边距」的纯容器。
            // 样式仍然是 token 驱动 —— 圆角、底色、层级全部来自 LocalDesignTokens。
            Card(
                modifier = Modifier.fillMaxWidth().height(BookCoverHeight),
                shape = t.cardShape,
                // 自定义背景时书卡略透，与背景融合；有封面的书本来就完全被图盖住。
                colors = CardDefaults.cardColors(
                    containerColor = t.colors.bookCoverFallback.copy(alpha = containerAlpha)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = t.elevation.card)
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
                    // 无封面：书脊渐变 + 书名/作者。渐变两端都取 token
                    // （accentSoft → bookCoverFallback），换肤时跟着走。
                    Box(
                        Modifier.fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(t.colors.accentSoft, t.colors.bookCoverFallback)
                                )
                            )
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(BookCoverPadding),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                book.title,
                                // 排版梯的 title 本身就是衬线（SkinTypography.titleFamily =
                                // SERIF），与原来显式指定的 FontFamily.Serif 同义。
                                style = t.typography.title,
                                color = t.colors.ink,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(t.spacing.s))
                            Text(book.author, style = t.typography.label, color = t.colors.inkSoft)
                        }
                    }
                }
            }
            if (book.progress > 0f) {
                // 进度环浮在书封右上角：外面一圈纸色底盘把它与封面图隔开，
                // 否则深色封面上那条 accent 细环几乎看不见。
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(BookProgressInset)
                        .size(BookProgressSize)
                        .background(t.colors.paper.copy(alpha = BookProgressScrimAlpha), CircleShape)
                        .padding(t.spacing.xs)
                ) {
                    CircularProgressIndicator(
                        progress = { book.progress },
                        modifier = Modifier.fillMaxSize(),
                        color = t.colors.accent,
                        trackColor = t.colors.accent.copy(alpha = BookProgressTrackAlpha),
                        strokeWidth = BookProgressStroke
                    )
                }
            }
        }
        Spacer(Modifier.height(t.spacing.xs))
        Text(
            book.title,
            style = t.typography.body.copy(fontWeight = FontWeight.Medium),
            color = t.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            if (book.progress > 0f) {
                stringResource(R.string.shelf_progress_read, (book.progress * 100).roundToInt())
            } else {
                book.author
            },
            style = t.typography.caption,
            color = t.colors.inkSoft,
            maxLines = 1
        )
        if (aiEnabled) {
            // AiBookStatus 已迁入 :shared：跨模块属性不做智能转换，先捕获局部量。
            val statusError = aiStatus?.error
            val statusLabel = when {
                aiStatus == null -> stringResource(R.string.shelf_ai_pending)
                aiStatus.generating -> stringResource(R.string.shelf_ai_generating)
                // 配了 Key 但生成时降级为本地：不显示「就绪」，留空即可（真正的
                // 诊断交给设置里的「测试连接」）。
                aiStatus.degraded -> ""
                aiStatus.ready -> stringResource(R.string.shelf_ai_ready)
                statusError != null -> stringResource(R.string.shelf_ai_error, statusError)
                else -> stringResource(R.string.shelf_ai_failed)
            }
            if (statusLabel.isNotBlank()) {
                // 状态徽章：成功态用 SUCCESS 语义色、其余（待生成/生成中/失败）
                // 用中性 —— 与原来「ready → Success，其它 → InkFaint」的分档一致。
                //
                // 刻意换成 StatusChip 而不是继续用一行小字：书卡上三种状态
                // （AI 语境 / 译本 / 档案过期）会横向叠加，纯文字三行颜色相近、
                // 挤在一起分不清谁是谁；徽章有底色，能一眼分开。
                StatusChip(
                    text = statusLabel,
                    tone = if (aiStatus?.ready == true) StatusChipTone.SUCCESS else StatusChipTone.NEUTRAL
                )
            }
        }
        if (attachingTranslation || book.hasTranslation || aiTranslationProgress != null) {
            val aiProgress = aiTranslationProgress
            // 「进行中」与「已就绪」在原来就分了色（InkFaint vs Success），
            // 换徽章后仍按同一分档：进行中 = 中性、已就绪 = 成功。
            StatusChip(
                text = when {
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
                tone = if (attachingTranslation || aiProgress != null) {
                    StatusChipTone.NEUTRAL
                } else {
                    StatusChipTone.SUCCESS
                }
            )
            // 版本闸门（阶段 2）：档案由更旧版对齐器写出。这里只是可见提示，
            // 动作入口在译本菜单里（书卡不放第二个按钮）。
            // 与上面那条徽章**并存**（截图里 Pride and Prejudice 同时有
            // 「AI building」+「Pairing outdated」+ 译本名），所以是第二个 chip 而不是替身。
            if (translationOutdated && !attachingTranslation && aiProgress == null) {
                // 用 ACCENT 而不是 WARNING：迁移前这条提示就是 accent 色，
                // P3 的验收前提是「迁移前后视觉等价」；「该不该改成警示色」是设计决策，
                // 不该混在结构迁移里悄悄发生。要改另开一条。
                StatusChip(
                    text = stringResource(R.string.shelf_translation_outdated),
                    tone = StatusChipTone.ACCENT
                )
            }
        }
        // 书卡操作区：两行、每行两个「图标 + 文字」按钮。
        //
        // 刻意**不**用 LinguaTextButton：那个按 token 的 controlHeight(48dp) 给最小高度，
        // 两行就会把书卡撑高一倍，而网格里每张卡的纵向空间是固定的（书封 205dp 之外
        // 只剩一屏能放几张的余量）。BookActionHeight(30dp) 是本屏专有几何常量，
        // 与 ReviewUi 保留图标/胶囊尺寸同一口径。颜色仍然全部来自 token。
        Row {
            ShelfCardAction(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                text = stringResource(R.string.shelf_glossary),
                tint = t.colors.accent,
                onClick = onGlossary
            )
            ShelfCardAction(
                icon = Icons.Default.Groups,
                text = stringResource(R.string.shelf_roster),
                tint = t.colors.accent,
                onClick = onManageRoster
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShelfCardAction(
                icon = Icons.Default.Delete,
                text = stringResource(R.string.shelf_remove),
                tint = t.colors.inkFaint,
                onClick = onLongPressDelete
            )
            // 译本按钮的三态色（进行中/已有/未加）本来就是语义分档，
            // 原样搬进参数，不改判定。
            ShelfCardAction(
                icon = Icons.Filled.Translate,
                text = when {
                    aiTranslationProgress != null ->
                        stringResource(R.string.shelf_translation_ai_cancel)
                    book.hasTranslation -> stringResource(R.string.shelf_translation_ready)
                    else -> stringResource(R.string.shelf_translation_add)
                },
                tint = when {
                    aiTranslationProgress != null -> t.colors.inkFaint
                    book.hasTranslation -> t.colors.success
                    else -> t.colors.accent
                },
                enabled = !attachingTranslation,
                onClick = onTranslation
            )
        }
    }
}

/**
 * 书卡上的紧凑「图标 + 文字」按钮（术语表 / 角色 / 移除 / 译本）。
 *
 * 为什么不用 [com.linguareader.app.components.LinguaTextButton]：那个按 token 的
 * `controlHeight`(48dp) 给最小高度，而书卡里这排按钮排两行、每行至多 4 个字，
 * 用 48dp 会把书卡撑高一倍，网格一屏就少放半排书。这是**本屏的形状参数**，
 * 与 ReviewUi 保留图标尺寸、ShelfAppearanceSheet 保留色块直径同一口径。
 *
 * 颜色仍然全部由调用方从 [LocalDesignTokens] 取，本组件不自造色值。
 */
@Composable
private fun ShelfCardAction(
    icon: ImageVector,
    text: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val t = LocalDesignTokens.current
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(BookActionHeight),
        contentPadding = PaddingValues(horizontal = t.spacing.xs)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(BookActionIconSize),
            tint = if (enabled) tint else t.colors.inkFaint
        )
        Spacer(Modifier.width(BookActionIconGap))
        Text(
            text = text,
            style = t.typography.label,
            color = if (enabled) tint else t.colors.inkFaint,
            maxLines = 1
        )
    }
}

/**
 * 书架的**确认 / 通知类对话框**统一封装。
 *
 * ## 覆盖范围（只有这三处同构）
 *
 * 删书确认、删译本确认、通知提示、手动导入被拒原因（正文可换成自定义内容）。
 * 它们的按钮槽结构完全一样：一到两个 TextButton（确认 + 可选取消），
 * 容器色/圆角/标题字梯走 token。
 *
 * ## 为什么放在本文件而不是 components/
 *
 * 本组件是**这一屏的 3~4 处同构收敛**，不是通用件。全项目的确认框形态差异很大
 * （生词本的「复习全部」只有一个确认按钮；资源包卸载确认带文件名与体积；
 * 多角色面板 7 个各有正文结构），现在抽到 components/ 等于拍脑袋定一个万能接口。
 *
 * **升级判定条件**：其它屏出现**按钮槽结构相同**（不是文案相似）的用法时，
 * 把这个类整体搬到 components/LinguaConfirmDialog.kt 并删除这里 —— 那是一次纯搬移，
 * 不是重新设计。
 *
 * @param body 纯文本正文；需要自定义正文（列表、输入框、额外动作）时改用 [bodyContent]
 * @param dangerConfirm 确认按钮用 danger 色。这是**状态表达**（该动作不可撤销 /
 *   会连带删除别的东西），不是样式选择 —— 与 LinguaTopBarAction.tint 同口径。
 * @param dismissText null = 只有确认按钮（通知类）
 */
@Composable
private fun ShelfNoticeDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    body: String? = null,
    bodyContent: (@Composable () -> Unit)? = null,
    dismissText: String? = null,
    dangerConfirm: Boolean = false
) {
    val t = LocalDesignTokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            // 用 TextButton + token 配色，而不是 LinguaTextButton：后者只有一种主色
            // （accent），而确认按钮需要能表达 danger（不可撤销动作）。
            // 「给 LinguaTextButton 加 color 参数」属于组件层改动，本轮不越界。
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    // 危险动作用 danger 语义色：与迁移前「删除按钮 color = Danger」一致。
                    contentColor = if (dangerConfirm) t.colors.danger else t.colors.accent
                )
            ) {
                Text(text = confirmText, style = t.typography.body)
            }
        },
        dismissButton = dismissText?.let { text ->
            {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = t.colors.accent)
                ) {
                    Text(text = text, style = t.typography.body)
                }
            }
        },
        title = { Text(text = title, style = t.typography.title, color = t.colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                if (body != null) {
                    Text(text = body, style = t.typography.body, color = t.colors.ink)
                }
                bodyContent?.invoke()
            }
        },
        containerColor = t.colors.cardSurface,
        shape = t.cardShape
    )
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
    // 术语表对话框：正文内嵌整个编辑器，结构自成一类，不套 ShelfNoticeDialog。
    val t = LocalDesignTokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        title = {
            Text(
                stringResource(R.string.glossary_title, book.title),
                style = t.typography.title,
                color = t.colors.ink
            )
        },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(max = GlossaryDialogMaxHeight)) {
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
        containerColor = t.colors.cardSurface,
        shape = t.cardShape
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

// ── 组件专有几何常量 ──────────────────────────────────────────────
//
// 与 ShelfAppearanceSheet / ReviewUi 同一口径：这些值**不进间距梯**。
// 间距梯的语义是「同一语义在不同屏上表现一致」（区块间距 / 卡内边距 / 行内间隙）；
// 「一张书封多高」「网格列最小多宽」是这一屏自己的形状参数，塞进梯子只会让梯子
// 变成杂物间。梯子里也确实没有对应档位（4/8/16/24/32/48）：148、14、205 硬套
// 都会改变像素，而 P3 的验收前提是「迁移前后视觉等价」。集中命名，可读性不比裸 dp 差。
//
// 全屏 55 → 14（三笔累计）：只剩这张表里的 14 个几何/限高常量 + 2 个透明度常量，
// 裸 dp 字面量 0 处。剩下这 14 个都不是「间距」，换成梯子档位都会改变像素。

/** 书架网格的单列最小宽度：决定 411dp 窄屏上是 2 列还是 3 列，属版式决策不是间距。 */
private val ShelfColumnMinWidth = 148.dp

/** 书卡之间的横向间隙（比纵向小：书封并排时留白过大会显得散）。 */
private val ShelfColumnGap = 14.dp

/** 书卡行之间的纵向间隙（要容得下卡下方那两行操作按钮的视觉呼吸）。 */
private val ShelfRowGap = 20.dp

/** 书封区域的固定高度（网格列宽会变，书封高度不变，保证一排书卡顶部齐平）。 */
private val BookCoverHeight = 205.dp

/** 书封内文字（无封面时的书名/作者）的内边距。 */
private val BookCoverPadding = 18.dp

/** 书封右上角进度环的直径与离书封边缘的距离（内圈的 4dp 用 spacing.xs）。 */
private val BookProgressSize = 38.dp
private val BookProgressInset = 10.dp

/** 进度环笔画宽度。 */
private val BookProgressStroke = 3.dp

/** 书卡操作按钮的行高与行内图标（见 [ShelfCardAction] 的注释：不能用 token 的 48）。 */
private val BookActionHeight = 30.dp
private val BookActionIconSize = 14.dp
private val BookActionIconGap = 3.dp

/** 术语表对话框的最大高度（超出后由 GlossaryEditor 自带滚动接管）。 */
private val GlossaryDialogMaxHeight = 480.dp

/** 「手动导入被拒原因」列表的最高可视高度（超出后列表自己滚）。 */
private val ManualRejectedMaxHeight = 360.dp

/** 对话框正文里「模式/风格标签」与输入框之间的小间距（比 spacing.xs 更松一点）。 */
private val DialogTightGap = 6.dp

/** 书架「正在整理图书」遮罩的不透明度：能看清下面有书、又读得清提示字。 */
private const val LoadingScrimAlpha = 0.72f

/** 进度环纸色底盘的透明度：压得住封面图，又不至于把下面的画完全盖住。 */
private const val BookProgressScrimAlpha = 0.94f

/** 进度环未走完那段轨道的透明度（比环本身淡，避免双环打架）。 */
private const val BookProgressTrackAlpha = 0.14f

private fun decodeSampledCover(file: File, targetWidth: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= targetWidth) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}
