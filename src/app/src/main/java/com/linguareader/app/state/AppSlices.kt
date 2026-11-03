package com.linguareader.app.state

import com.linguareader.app.AppUiState
import com.linguareader.app.StatusTone
import com.linguareader.shared.ui.nav.Route
import com.linguareader.shared.ui.state.BookshelfState
import com.linguareader.shared.ui.state.NoticeTone
import com.linguareader.shared.ui.state.ReaderState
import com.linguareader.shared.ui.state.SettingsState
import com.linguareader.shared.ui.state.ShellState
import com.linguareader.shared.ui.state.VocabState

/**
 * AppUiState 拆分后的五切片 + 一份 :app 专属残留。
 *
 * 迁移期（P2）的用法：val slices = state.split()，屏幕只取自己要的那一份。
 * 这里**不改变 AppViewModel 的任何现有写法**——AppUiState 本身保持原样
 * （另有一条开发线在改它）；这里只做「新增的分层」，等 P3 逐屏迁移完旧字段自然荒废。
 */
data class AppSlices(
    val shell: ShellState,
    val bookshelf: BookshelfState,
    val reader: ReaderState,
    val vocab: VocabState,
    val settings: SettingsState,
    val appOnly: AppOnlyState
)

/**
 * 把现有 AppUiState 投影成五个纯 Kotlin 切片。
 *
 * 三条约定（与 :shared/ui/state 的包说明一致）：
 * 1. **投影不是搬家**：AppUiState 原样保留，每次调用重新投影，两边不会漂移。
 * 2. **允许重叠**：reviewPreset / aiSettings / savedWords 同时进两个切片，
 *    因为它们确实被两处消费（阅读页内联复习设置、查词 AI 配置、正文已收藏标记）。
 *    重叠不等于两份拷贝——都是同一次投影的产物。
 * 3. **字段不丢**：每个字段至少落进一个切片；映射账本是 AppUiStateMapping，
 *    由 AppUiStateSplitTest 用反射对账。
 */
fun AppUiState.split(): AppSlices = AppSlices(
    shell = ShellState(
        // 迁移期：把 MainActivity.kt:155 的 currentBook == null 二态判断收成一个路由。
        route = currentBook?.let { Route.Reader(it.id) } ?: Route.Bookshelf,
        loading = loading,
        notice = notice,
        noticeTone = noticeTone.toNoticeTone(),
        message = message,
        messageTitle = messageTitle
    ),
    bookshelf = BookshelfState(
        books = books,
        aiStatuses = aiStatuses,
        attachingTranslation = attachingTranslation,
        outdatedTranslations = outdatedTranslations,
        manualImportRejected = manualImportRejected
    ),
    reader = ReaderState(
        book = currentBook,
        aiSettings = aiSettings,
        savedWords = savedWords,
        reviewPreset = reviewPreset,
        customReview = customReview,
        reminders = reminders
    ),
    vocab = VocabState(
        savedWords = savedWords,
        reviewPreset = reviewPreset,
        customReview = customReview,
        reminders = reminders
    ),
    settings = SettingsState(
        aiSettings = aiSettings,
        update = update,
        storage = storage,
        storageScanning = storageScanning,
        syncSettings = syncSettings,
        syncStatus = syncStatus,
        syncBusy = syncBusy,
        cloudBooks = cloudBooks,
        cloudBooksLoaded = cloudBooksLoaded,
        cloudBooksLoading = cloudBooksLoading
    ),
    appOnly = AppOnlyState(
        launchPrompt = launchPrompt,
        aiTranslationProgress = aiTranslationProgress,
        aiTranslationPrepare = aiTranslationPrepare,
        manualTranslationPrepare = manualTranslationPrepare,
        pendingManualExport = pendingManualExport,
        packs = packs
    )
)

/** 提示语义跨层映射：界面枚举（:app）→ 纯状态枚举（:shared）。 */
fun StatusTone.toNoticeTone(): NoticeTone = when (this) {
    StatusTone.SUCCESS -> NoticeTone.SUCCESS
    StatusTone.DANGER -> NoticeTone.DANGER
    StatusTone.NEUTRAL -> NoticeTone.NEUTRAL
}
