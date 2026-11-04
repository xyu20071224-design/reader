package com.linguareader.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.data.Book
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.shared.ai.BookGlossary
import com.linguareader.shared.ai.GlossaryEntry
import com.linguareader.app.shelf.ShelfAiTranslationManageDialog
import com.linguareader.app.shelf.ShelfAiTranslationPrepareDialog
import com.linguareader.app.shelf.ShelfBackgroundLayer
import com.linguareader.app.shelf.ShelfBookCard
import com.linguareader.app.shelf.ShelfDeleteBookDialog
import com.linguareader.app.shelf.ShelfDetachTranslationDialog
import com.linguareader.app.shelf.ShelfEmptyState
import com.linguareader.app.shelf.ShelfGlossaryDialog
import com.linguareader.app.shelf.ShelfManualRejectedDialog
import com.linguareader.app.shelf.ShelfManualTranslationDialog
import com.linguareader.app.shelf.ShelfMessageDialog
import com.linguareader.app.shelf.ShelfRosterSheet
import com.linguareader.app.shelf.ShelfTranslationChoiceDialog
import com.linguareader.app.components.LinguaScaffold
import com.linguareader.app.components.LinguaTopBarAction
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.shared.sync.CloudBook
import com.linguareader.shared.sync.SyncSettings
import com.linguareader.shared.update.AppUpdateUiState

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
                    ShelfEmptyState(onImport = { launcher.launch(IMPORT_MIME_TYPES) })
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
                            ShelfBookCard(
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


    // ── 信息 / 确认类（ShelfDialogs.kt） ─────────────────────────────
    state.message?.let {
        ShelfMessageDialog(
            message = it,
            messageTitle = state.messageTitle,
            onDismiss = onDismissMessage
        )
    }

    deleteCandidate?.let { book ->
        ShelfDeleteBookDialog(
            book = book,
            // 删书连带删该书生词，且不可恢复 —— 条数必须摆在按下去之前（由屏幕算好传入）。
            wordCount = state.savedWords.count { it.bookId == book.id },
            onConfirm = {
                onDelete(book)
                deleteCandidate = null
            },
            onDismiss = { deleteCandidate = null }
        )
    }

    detachTranslationCandidate?.let { book ->
        ShelfDetachTranslationDialog(
            book = book,
            outdated = book.id in state.outdatedTranslations,
            onConfirm = {
                onDetachTranslation(book)
                detachTranslationCandidate = null
            },
            onRealign = {
                detachTranslationCandidate = null
                onRealignTranslation(book)
            },
            onDismiss = { detachTranslationCandidate = null }
        )
    }

    state.manualImportRejected?.let { rejected ->
        ShelfManualRejectedDialog(
            rejected = rejected,
            onDismiss = onClearManualImportRejected
        )
    }

    // ── 菜单 / 选择 / 表单类（ShelfTranslationDialogs.kt） ────────────
    aiTranslationManageCandidate?.let { book ->
        ShelfAiTranslationManageDialog(
            book = book,
            outdated = book.id in state.outdatedTranslations,
            onRegenerate = {
                aiTranslationManageCandidate = null
                onPrepareAiTranslation(book)
            },
            onImportManual = {
                aiTranslationManageCandidate = null
                onPrepareManualTranslation(book)
            },
            onRemove = {
                aiTranslationManageCandidate = null
                detachTranslationCandidate = book
            },
            onRealign = {
                aiTranslationManageCandidate = null
                onRealignTranslation(book)
            },
            onDismiss = { aiTranslationManageCandidate = null }
        )
    }

    translationChoiceCandidate?.let { book ->
        ShelfTranslationChoiceDialog(
            onImportFile = {
                translationChoiceCandidate = null
                pendingTranslationBook = book
                translationLauncher.launch(TRANSLATION_MIME_TYPES)
            },
            onAiGenerate = {
                translationChoiceCandidate = null
                onPrepareAiTranslation(book)
            },
            onManualAi = {
                translationChoiceCandidate = null
                onPrepareManualTranslation(book)
            },
            onDismiss = { translationChoiceCandidate = null }
        )
    }

    state.manualTranslationPrepare?.let { prepare ->
        ShelfManualTranslationDialog(
            prepare = prepare,
            onExport = onExportManualTranslation,
            onImportResults = {
                pendingManualImportBook = prepare.book
                onDismissManualTranslationPrepare()
                manualImportLauncher.launch(MANUAL_RESULT_MIME_TYPES)
            },
            onDismiss = onDismissManualTranslationPrepare
        )
    }

    state.aiTranslationPrepare?.let { prepare ->
        ShelfAiTranslationPrepareDialog(
            prepare = prepare,
            providerDisplayName = state.aiSettings.providerDisplayName,
            onStart = onStartAiTranslation,
            onDismiss = onDismissAiTranslationPrepare
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
        ShelfGlossaryDialog(
            book = book,
            onLoad = onLoadGlossary,
            onAdd = onAddGlossary,
            onUpdate = onUpdateGlossary,
            onRemove = onRemoveGlossary,
            onDismiss = { glossaryBook = null }
        )
    }

    rosterBook?.let { book ->
        ShelfRosterSheet(
            book = book,
            books = state.books,
            onDismiss = { rosterBook = null }
        )
    }
}


// ── 网格与遮罩的几何常量 ──────────────────────────────────────────
//
// 不进间距梯：网格列宽/间隙是**版式决策**（决定 411dp 上排 2 列还是 3 列），
// 梯子里没有对应档位（148 / 14 / 20 硬套都会改变像素，而 P3 的验收前提是
// 「迁移前后视觉等价」）。书卡与对话框自己的几何常量已随代码搬进 shelf/ 各文件。

/** 书架网格的单列最小宽度：决定 411dp 窄屏上是 2 列还是 3 列，属版式决策不是间距。 */
private val ShelfColumnMinWidth = 148.dp

/** 书卡之间的横向间隙（比纵向小：书封并排时留白过大会显得散）。 */
private val ShelfColumnGap = 14.dp

/** 书卡行之间的纵向间隙（要容得下卡下方那两行操作按钮的视觉呼吸）。 */
private val ShelfRowGap = 20.dp

/** 书架「正在整理图书」遮罩的不透明度：能看清下面有书、又读得清提示字。 */
private const val LoadingScrimAlpha = 0.72f
