package com.linguareader.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguareader.app.ai.AiLookupResult
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.ai.SentenceTranslationResult
import com.linguareader.app.ai.SentenceTranslatorFactory
import com.linguareader.app.data.Book
import com.linguareader.app.data.ContextualDictionaryEntry
import com.linguareader.app.data.PartOfSpeech
import com.linguareader.app.data.ReaderFont
import com.linguareader.app.data.ReaderPreferences
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.app.data.ReaderTheme
import com.linguareader.app.data.SavedWord
import com.linguareader.app.data.WordLookup
import com.linguareader.app.reader.EpubPage
import com.linguareader.app.reader.ReaderBackAction
import com.linguareader.app.reader.ReaderController
import com.linguareader.app.reader.ReaderLookupSession
import com.linguareader.app.reader.ReaderOverlays
import com.linguareader.app.reader.ReaderPosition
import com.linguareader.app.reader.ReaderScripts
import com.linguareader.app.reader.SentenceTranslationCache
import com.linguareader.app.translation.TranslationLookupResult
import com.linguareader.app.translation.TranslationMatchLevel
import com.linguareader.app.tts.TtsPlaybackController
import com.linguareader.app.tts.TtsPlaybackState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Highlights the sentence the player is currently speaking. Prefers the exact
 * block/offset location published by the service so repeated sentences never
 * highlight the first occurrence; text search stays as a fallback.
 */
/** 查词请求旋转屏后可恢复（字段全为 Bundle 安全类型），恢复后由 LaunchedEffect 重建结果。 */
private val WordLookupSaver = Saver<WordLookup?, List<Any>>(
    save = { it?.let { l -> listOf(l.word, l.sentence, l.paragraph, l.sentenceOffset, l.x, l.y) } ?: emptyList() },
    restore = { values ->
        if (values.isEmpty()) null
        else WordLookup(
            word = values[0] as String,
            sentence = values[1] as String,
            paragraph = values[2] as String,
            sentenceOffset = values[3] as Int,
            x = values[4] as Float,
            y = values[5] as Float
        )
    }
)

/**
 * 阅读位置整体可跨旋转恢复（全部字段都是 Bundle 安全类型）。
 * 重构前只有章节/还原页/滚动三项存得住，页数与「待落盘」标记会在旋转时丢；
 * 现在一起存，旋转后不再白白重存一次进度。
 */
private val ReaderPositionSaver = Saver<ReaderPosition, List<Any>>(
    save = {
        listOf(
            it.chapter, it.restorePage, it.page, it.pageCount,
            it.scrollMode, it.scrollRatio, it.scrollPageCount,
            it.savedPage, it.savedCount, it.dirty
        )
    },
    restore = { values ->
        ReaderPosition(
            chapter = values[0] as Int,
            restorePage = values[1] as Int,
            page = values[2] as Int,
            pageCount = values[3] as Int,
            scrollMode = values[4] as Boolean,
            scrollRatio = values[5] as Float,
            scrollPageCount = values[6] as Int,
            savedPage = values[7] as Int,
            savedCount = values[8] as Int,
            dirty = values[9] as Boolean
        )
    }
)

/**
 * 弹层状态只把「选择起点」态存过旋转，其余弹层沿用重构前的行为（旋转即关闭）。
 * 故意不整体保存：让目录/设置/跳页在旋转后自动重开是行为变更，不该混在重构里。
 */
private val ReaderOverlaysSaver = Saver<ReaderOverlays, Boolean>(
    save = { it.choosingStart },
    restore = { ReaderOverlays(choosingStart = it) }
)

private fun highlightCurrentTts(controller: ReaderController, ttsState: TtsPlaybackState) {
    if (ttsState.highlightBlockIndex >= 0 && ttsState.highlightLength > 0) {
        controller.highlightBlock(
            ttsState.highlightBlockIndex,
            ttsState.highlightOffset,
            ttsState.highlightLength
        )
    } else if (ttsState.currentSentence.isNotBlank()) {
        controller.highlightSentence(ttsState.currentSentence)
    }
}

@Composable
internal fun ReaderScreen(
    book: Book,
    viewModel: AppViewModel,
    aiSettings: AiSettings,
    savedWords: List<SavedWord>,
    reviewPace: ReviewPace,
    reviewPreset: ReviewMode?,
    customReview: ReviewPace,
    reminders: ReviewReminders,
    onReviewModeChange: (ReviewMode) -> Unit,
    onCustomReviewChange: (ReviewPace) -> Unit,
    onRemindersChange: (ReviewReminders) -> Unit,
    onSpeak: (String) -> Unit,
    onClose: () -> Unit,
    /** 阅读主题变化时通知外层，让书架/弹层等外壳配色跟着切换（日间/夜间）。 */
    onAppearanceChanged: (ReaderTheme) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { ReaderController() }
    val density = LocalDensity.current.density
    // 实测顶栏/底栏高度（px → dp，1dp = 1 CSS px），注入 WebView 作为正文
    // 预留量。targetSdk 35 强制 edge-to-edge 后底栏含导航栏 inset，写死的
    // 70px 预留不够，会遮住正文最后一行；改为动态注入后永远精确。
    var chromeTopPx by remember {
        mutableFloatStateOf(ReaderScripts.DEFAULT_CHROME_TOP_PX.toFloat())
    }
    var chromeBottomPx by remember {
        mutableFloatStateOf(ReaderScripts.DEFAULT_CHROME_BOTTOM_PX.toFloat())
    }
    val ttsState by TtsPlaybackController.state.collectAsStateWithLifecycle()
    val ttsForThisBook = ttsState.bookId == book.id
    var ttsPositionReportJob by remember { mutableStateOf<Job?>(null) }
    var locusRefreshJob by remember { mutableStateOf<Job?>(null) }
    // 阅读位置（章节/页码/滚动/待落盘快照）与弹层编排都抽成了不依赖 Android 的
    // 状态机：迁移规则和它们的回归测试在 reader/ReaderScreenState.kt 与
    // ReaderScreenStateTest.kt，这里只负责把事件转成迁移、把结果画出来。
    var position by rememberSaveable(book.id, stateSaver = ReaderPositionSaver) {
        mutableStateOf(
            ReaderPosition.forBook(
                chapter = book.chapterIndex,
                page = book.pageIndex,
                chapterCount = book.chapters.size,
                locusBlock = book.locusBlockIndex,
                locusOffset = book.locusCharOffset,
                locusAnchor = book.locusAnchor
            )
        )
    }
    var overlays by rememberSaveable(stateSaver = ReaderOverlaysSaver) {
        mutableStateOf(ReaderOverlays())
    }
    var lookup by rememberSaveable(stateSaver = WordLookupSaver) { mutableStateOf<WordLookup?>(null) }
    // 查词弹层（ModalBottomSheet）会盖住全局 Snackbar：收藏/移出生词的反馈
    // 必须画在弹层内部（SettingsStatus 行内模式），否则用户得到「毫无动静」。
    // 会话整体抽成 ReaderLookupSession：开新查词的「重置矩阵」在里面定义并单测。
    val sentenceTranslationCache = remember(book.id) { SentenceTranslationCache() }
    var lookupSession by remember { mutableStateOf(ReaderLookupSession.empty(sentenceTranslationCache)) }
    // 复习卡组旋转屏后按 id 从 ViewModel 的 savedWords 恢复（SavedWord 本身不可 Bundle 化）。
    var reviewDeckIds by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val reviewDeck = remember(savedWords, reviewDeckIds) {
        reviewDeckIds?.let { ids -> savedWords.filter { it.id in ids } }?.takeIf { it.isNotEmpty() }
    }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val dueWords = remember(savedWords, nowTick) {
        savedWords.filter { it.nextReviewAt <= nowTick }.sortedBy { it.nextReviewAt }
    }

    val preferenceStore = remember {
        context.getSharedPreferences("reader_preferences", android.content.Context.MODE_PRIVATE)
    }
    var preferences by remember {
        mutableStateOf(
            ReaderPreferences(
                fontPercent = preferenceStore.getInt("fontPercent", 100),
                lineHeight = preferenceStore.getFloat("lineHeight", 1.65f),
                theme = runCatching {
                    ReaderTheme.valueOf(preferenceStore.getString("theme", null) ?: "")
                }.getOrDefault(ReaderTheme.PAPER),
                fontFamily = runCatching {
                    ReaderFont.valueOf(preferenceStore.getString("font", null) ?: "")
                }.getOrDefault(ReaderFont.SERIF)
            )
        )
    }

    fun persistPreferences(value: ReaderPreferences) {
        preferences = value
        preferenceStore.edit()
            .putInt("fontPercent", value.fontPercent)
            .putFloat("lineHeight", value.lineHeight)
            .putString("theme", value.theme.name)
            .putString("font", value.fontFamily.name)
            .apply()
        controller.applyPreferences(value)
        // 外壳（书架、弹层、听书条）跟随正文主题切换日间/夜间。
        onAppearanceChanged(value.theme)
    }

    /** 有脏数据才写盘；取快照与清脏标记都在 [ReaderPosition] 里，这里只负责发协程。 */
    fun flushProgressAsync() {
        val save = position.saveRequest(book.chapters.size) ?: return
        position = position.markSaved()
        scope.launch {
            viewModel.saveProgress(
                book, save.chapter, save.page, save.progress,
                save.locusBlock, save.locusOffset, save.locusAnchor
            )
        }
    }

    fun performClose() {
        val save = position.saveRequest(book.chapters.size)
        position = position.markSaved()
        scope.launch {
            if (save != null) {
                viewModel.saveProgress(
                    book, save.chapter, save.page, save.progress,
                    save.locusBlock, save.locusOffset, save.locusAnchor
                )
            }
            onClose()
        }
    }

    fun closeWithFlush() {
        // 到期生词拦截（先弹提示条、记住「关闭待办」）的规则在 ReaderOverlays 里。
        val decision = overlays.requestClose(
            hasDueWords = dueWords.isNotEmpty(),
            pausePrompt = reminders.pausePrompt
        )
        overlays = decision.overlays
        if (!decision.promptReview) performClose()
    }

    fun selectChapter(
        index: Int,
        fromEnd: Boolean = false,
        fromTts: Boolean = false,
        keepScrollMode: Boolean = false
    ) {
        // 越界返回 null = 什么都别做（含不落盘、不通知 TTS），与旧代码提前 return 等价。
        val next = position.selectChapter(
            index = index,
            chapterCount = book.chapters.size,
            fromEnd = fromEnd,
            keepScrollMode = keepScrollMode
        ) ?: return
        // 先把旧章的进度落盘，再换位置。
        flushProgressAsync()
        position = next
        if (ttsForThisBook && !fromTts) {
            TtsPlaybackController.onReaderChapterSelected(book.id, index)
        }
    }

    fun changeChapter(direction: Int, keepScrollMode: Boolean = false) {
        selectChapter(
            index = position.chapter + direction,
            fromEnd = direction < 0,
            keepScrollMode = keepScrollMode
        )
    }

    /**
     * 取一次语义锚点写回状态。
     *
     * 锚点要问 WebView（一次 JS 求值 + 块内二分），所以走去抖：翻页、重排、
     * 滚动都会触发，但只在安静 250ms 后取一次。落盘仍由 800ms 的节流轮询做，
     * 它读的就是这里写进 position 的锚点，因此保存路径依然是同步的。
     */
    fun refreshLocusDelayed() {
        locusRefreshJob?.cancel()
        locusRefreshJob = scope.launch {
            delay(250)
            controller.readLocus { blockIndex, charOffset ->
                position = position.withLocus(blockIndex, charOffset)
            }
        }
    }

    fun reportTtsPositionDelayed() {
        ttsPositionReportJob?.cancel()
        ttsPositionReportJob = scope.launch {
            delay(350)
            controller.firstVisibleBlock { block ->
                if (block != null) {
                    TtsPlaybackController.onReaderPositionChanged(book.id, position.chapter, block)
                }
            }
        }
    }

    fun startOrToggleListening() {
        if (ttsState.isActive && ttsState.bookId == book.id) {
            if (ttsState.isPlaying) {
                // Playing: the top button only pauses; it never re-enters the
                // start-point chooser (that used to restart/confuse playback).
                TtsPlaybackController.pause(context)
            } else {
                overlays = overlays.copy(choosingStart = true)
                controller.setChoosingStart(true)
            }
            return
        }
        // Opening listening never auto-plays: enter standby and let the user
        // tap a word/sentence to choose the start point.
        TtsPlaybackController.startStandby(context, book, position.chapter)
        overlays = overlays.copy(choosingStart = true)
        controller.setChoosingStart(true)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(800)
            flushProgressAsync()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowTick = System.currentTimeMillis()
        }
    }

    LaunchedEffect(Unit) {
        TtsPlaybackController.chapterRequests.collect { requested ->
            // 只有真的换章才动位置：与阅读器位置回报形成回路曾导致章末死循环。
            if (requested != position.chapter) {
                selectChapter(requested, fromTts = true)
            }
        }
    }

    LaunchedEffect(ttsForThisBook) {
        if (!ttsForThisBook) {
            overlays = overlays.copy(choosingStart = false)
            controller.setChoosingStart(false)
            controller.clearHighlight()
        }
    }

    LaunchedEffect(
        ttsState.highlightBlockIndex,
        ttsState.highlightOffset,
        ttsState.chapterIndex,
        position.chapter
    ) {
        if (ttsForThisBook && ttsState.chapterIndex == position.chapter) {
            highlightCurrentTts(controller, ttsState)
        }
    }

    BackHandler {
        // 优先级链（查词 > 设置 > 目录 > 跳页 > 退出）由 ReaderOverlays 定义并单测。
        val back = overlays.onBack(lookupOpen = lookup != null)
        overlays = back.overlays
        when (back.action) {
            ReaderBackAction.DismissLookup -> lookup = null
            ReaderBackAction.CloseSheet -> Unit
            ReaderBackAction.LeaveReader -> closeWithFlush()
        }
    }

    LaunchedEffect(lookup) {
        val request = lookup ?: return@LaunchedEffect
        val dictionary = viewModel.lookup(request)
        // begin() 一次性归零上一次的 AI/译文/错误/行内反馈；归零矩阵在状态类里单测。
        lookupSession = lookupSession.begin(
            dictionaryResult = dictionary,
            hasTranslation = book.hasTranslation,
            sentence = request.sentence
        )
        // 译本对照是纯本地查询，先于联网 AI 出结果。
        if (book.hasTranslation) {
            lookupSession = lookupSession.withTranslation(
                viewModel.translationLookup(book, position.chapter, request)
            )
        }
        if (aiSettings.enabled) {
            lookupSession = lookupSession.copy(aiLoading = true)
            val outcome = runCatching {
                viewModel.aiLookup(book, request, dictionary.entry)
            }.onFailure { lookupSession = lookupSession.markAiFailed() }.getOrNull()
            // outcome 非 null 时 result 仍可能为 null（本地兜底也没东西可给），
            // remoteFailed 与 result 正交，两条失败路径都要能反馈。
            if (outcome != null) {
                lookupSession = lookupSession.withAiResult(outcome.result, outcome.remoteFailed == true)
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(android.graphics.Color.parseColor(preferences.theme.background)))
    ) {
        key(position.chapter) {
            EpubPage(
                chapterFile = File(book.extractedDir, book.chapters[position.chapter].relativePath),
                initialPage = position.restorePage,
                initialLocusBlock = position.locusBlock,
                initialLocusOffset = position.locusOffset,
                initialLocusAnchor = position.locusAnchor,
                initialScrollMode = position.scrollMode,
                initialScrollRatio = position.scrollRatio,
                initialScrollPageCount = position.scrollPageCount.coerceAtLeast(1),
                preferences = preferences,
                savedWords = if (reminders.contextHighlight) {
                    savedWords.flatMap { word -> listOf(word.headword) + word.surfaceForms }
                } else {
                    emptyList()
                },
                chromeTopPx = chromeTopPx.roundToInt(),
                chromeBottomPx = chromeBottomPx.roundToInt(),
                controller = controller,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(UiTags.READER_PAGE),
                onReady = { page, count ->
                    position = position.onReady(page, count)
                    // 落位后立刻取一次锚点：老书（没有锚点）在这里完成迁移，
                    // 新书则把「重排后的真实位置」写回，供下次还原。
                    refreshLocusDelayed()
                    TtsPlaybackController.onReaderChapterLoaded(book.id, position.chapter)
                    controller.setChoosingStart(overlays.choosingStart)
                    if (ttsForThisBook && ttsState.chapterIndex == position.chapter) {
                        highlightCurrentTts(controller, ttsState)
                    }
                },
                onPageChanged = { page, count ->
                    position = position.onPageChanged(page, count)
                    refreshLocusDelayed()
                    // A manual page turn scrolls the highlight away with the
                    // text; clear it here so no stale block lingers on the new
                    // page before the next sentence re-applies it.
                    if (ttsForThisBook) controller.clearHighlight()
                    if (ttsForThisBook && ttsState.isPlaying) {
                        reportTtsPositionDelayed()
                    }
                },
                onChapterRequested = { direction ->
                    if (reminders.pausePrompt && dueWords.isNotEmpty() && !overlays.reviewPrompt) {
                        overlays = overlays.copy(reviewPrompt = true)
                    }
                    changeChapter(direction, keepScrollMode = position.scrollMode)
                },
                onWord = {
                    lookup = it
                    // 清空会话：新查词的正式重置在 LaunchedEffect(lookup) 的 begin() 里做。
                    lookupSession = ReaderLookupSession.empty(sentenceTranslationCache)
                    overlays = overlays.copy(toolbarVisible = false)
                },
                onSentenceTapped = { block, offset ->
                    if (overlays.choosingStart) {
                        // Only the first tap after entering choose mode is
                        // consumed as the start point.
                        overlays = overlays.copy(choosingStart = false)
                        controller.setChoosingStart(false)
                        TtsPlaybackController.startFromBlockOffset(
                            context,
                            book,
                            position.chapter,
                            block,
                            offset
                        )
                    }
                },
                onScrollModeChanged = { active -> position = position.onScrollModeChanged(active) },
                onScrollProgress = { ratio, page, count ->
                    position = position.onScrollProgress(ratio, page, count)
                },
                onToolbarRequested = { overlays = overlays.toggleToolbar() }
            )
        }

        AnimatedVisibility(
            visible = overlays.toolbarVisible,
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .onSizeChanged { size ->
                        // 工具栏隐藏时高度归零，保留最后一次实测值，
                        // 避免每次开关工具栏都触发整章重排。
                        if (size.height > 0) chromeTopPx = size.height / density
                    }
                    .background(Paper.copy(alpha = .96f))
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag(UiTags.READER_TOP_BAR),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = ::closeWithFlush) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.reader_back)
                        )
                    }
                    Text(
                        book.chapters[position.chapter].title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium
                    )
                    if (reminders.toolbarBadge && dueWords.isNotEmpty()) {
                        TextButton(onClick = { reviewDeckIds = dueWords.take(reviewPace.sessionMaxWords).map { it.id } }) {
                            Text(
                                pluralStringResource(R.plurals.reader_review_badge, dueWords.size, dueWords.size),
                                color = Accent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    TextButton(onClick = { overlays = overlays.copy(contents = true) }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.reader_contents))
                    }
                    TextButton(onClick = ::startOrToggleListening) {
                        Icon(
                            Icons.Filled.Headphones,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (ttsForThisBook) Accent else InkSoft
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (ttsForThisBook) stringResource(R.string.reader_listening_active)
                            else stringResource(R.string.reader_listening),
                            color = Ink
                        )
                    }
                    TextButton(onClick = { overlays = overlays.copy(settings = true) }) {
                        Text("Aa", fontWeight = FontWeight.Bold)
                    }
                }
                LinearProgressIndicator(
                    progress = { position.progress(book.chapters.size) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Accent,
                    trackColor = Accent.copy(alpha = .12f)
                )
            }
        }

        // semantics 作用域不是 @Composable，无障碍标签先在这里取出来。
        val prevPageLabel = stringResource(R.string.reader_prev_page)
        val nextPageLabel = stringResource(R.string.reader_next_page)
        val pageIndicatorLabel = stringResource(R.string.reader_page_indicator)
        Row(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    if (size.height > 0) chromeBottomPx = size.height / density
                }
                .background(Color(android.graphics.Color.parseColor(preferences.theme.background)).copy(alpha = .94f))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 18.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = controller::previousPage,
                modifier = Modifier.semantics {
                    contentDescription = prevPageLabel
                }
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = Color(android.graphics.Color.parseColor(preferences.theme.foreground))
                )
            }
            if (position.scrollMode) {
                Text(
                    stringResource(
                        R.string.reader_scroll_progress,
                        position.chapter + 1,
                        book.chapters.size,
                        (position.scrollRatio * 100).roundToInt()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(android.graphics.Color.parseColor(preferences.theme.foreground)).copy(alpha = .6f),
                    modifier = Modifier
                        .semantics { contentDescription = pageIndicatorLabel }
                        .clickable { overlays = overlays.copy(pageJump = true) }
                )
                TextButton(onClick = controller::exitScrollMode) {
                    Text(
                        stringResource(R.string.reader_pagination),
                        color = Color(android.graphics.Color.parseColor(preferences.theme.foreground))
                    )
                }
            } else {
                Text(
                    stringResource(
                        R.string.reader_pages_label,
                        position.chapter + 1,
                        book.chapters.size,
                        position.page + 1,
                        position.pageCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(android.graphics.Color.parseColor(preferences.theme.foreground)).copy(alpha = .6f),
                    modifier = Modifier
                        .semantics { contentDescription = pageIndicatorLabel }
                        .clickable { overlays = overlays.copy(pageJump = true) }
                )
            }
            IconButton(
                onClick = controller::nextPage,
                modifier = Modifier.semantics {
                    contentDescription = nextPageLabel
                }
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color(android.graphics.Color.parseColor(preferences.theme.foreground))
                )
            }
        }

        AnimatedVisibility(
            visible = ttsForThisBook,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ListeningBar(
                state = ttsState,
                modifier = Modifier
                    .padding(bottom = 62.dp)
                    .testTag(UiTags.READER_LISTENING_BAR),
                onToggle = { TtsPlaybackController.toggle(context) },
                onPrevious = { TtsPlaybackController.previous(context) },
                onNext = { TtsPlaybackController.next(context) },
                onStop = { TtsPlaybackController.stop(context) },
                onRateChange = { TtsPlaybackController.setRate(context, it) },
                onCacheBook = { TtsPlaybackController.cacheWholeBook(context) },
                choosingStart = overlays.choosingStart,
                onChooseStart = {
                    overlays = overlays.copy(choosingStart = !overlays.choosingStart)
                    controller.setChoosingStart(overlays.choosingStart)
                }
            )
        }

        AnimatedVisibility(
            visible = overlays.reviewPrompt && dueWords.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier
                    .padding(bottom = 62.dp)
                    .testTag(UiTags.REVIEW_PROMPT_BANNER)
            ) {
                ReviewPromptBanner(
                    count = dueWords.size,
                    dwellMillis = reviewPace.dwellMillis,
                    onStart = {
                        overlays = overlays.startReviewFromPrompt()
                        reviewDeckIds = dueWords.take(reviewPace.sessionMaxWords).map { it.id }
                    },
                    onDismiss = {
                        val dismissal = overlays.dismissReviewPrompt()
                        overlays = dismissal.overlays
                        if (dismissal.leaveReader) performClose()
                    }
                )
            }
        }
    }

    if (overlays.contents) {
        ContentsSheet(
            book = book,
            currentChapter = position.chapter,
            onSelect = {
                selectChapter(it)
                overlays = overlays.copy(contents = false)
            },
            onDismiss = { overlays = overlays.copy(contents = false) }
        )
    }

    if (overlays.settings) {
        SettingsSheet(
            preferences = preferences,
            reviewPace = reviewPace,
            onOpenReviewSettings = {
                overlays = overlays.copy(settings = false, reviewSettings = true)
            },
            onOpenListeningSettings = {
                overlays = overlays.copy(settings = false, listeningSettings = true)
            },
            onChange = ::persistPreferences,
            onDismiss = { overlays = overlays.copy(settings = false) }
        )
    }

    if (overlays.listeningSettings) {
        // Multi-voice M4: the reader knows the book, so its character list and
        // voice assignments can be managed right here.
        ListeningSettingsSheet(
            onDismiss = { overlays = overlays.copy(listeningSettings = false) },
            book = book
        )
    }

    if (overlays.pageJump) {
        PageJumpDialog(
            currentPage = position.page,
            pageCount = position.pageCount,
            onJump = controller::jumpToPage,
            onDismiss = { overlays = overlays.copy(pageJump = false) }
        )
    }

    if (overlays.reviewSettings) {
        ReviewSettingsSheet(
            preset = reviewPreset,
            custom = customReview,
            reminders = reminders,
            onChangePreset = onReviewModeChange,
            onChangeCustom = onCustomReviewChange,
            onChangeReminders = onRemindersChange,
            onDismiss = { overlays = overlays.copy(reviewSettings = false) }
        )
    }

    reviewDeck?.let { deck ->
        ReviewSheet(
            deck = deck,
            onReview = viewModel::reviewWord,
            onSpeak = onSpeak,
            onDismiss = { reviewDeckIds = null }
        )
    }

    lookup?.let { currentLookup ->
        val displayedEntry = if (lookupSession.showingRelatedPhrase) lookupSession.dictionaryResult?.relatedPhrase
        else lookupSession.dictionaryResult?.entry
        val savedId = (displayedEntry?.matchedPhrase ?: displayedEntry?.headword)
            ?.lowercase(Locale.ROOT)
        LookupSheet(
            lookup = currentLookup,
            entry = displayedEntry,
            relatedPhrase = lookupSession.dictionaryResult?.relatedPhrase,
            loading = lookupSession.dictionaryLoading,
            saveStatus = lookupSession.status,
            aiContext = lookupSession.aiResult,
            aiLoading = lookupSession.aiLoading,
            aiFailed = lookupSession.aiFailed,
            aiRemoteFailed = lookupSession.aiRemoteFailed,
            sentenceTranslationReady = SentenceTranslatorFactory.isConfigured(aiSettings),
            hasTranslation = book.hasTranslation,
            translation = lookupSession.translation,
            translationLoading = lookupSession.translationLoading,
            sentenceTranslation = lookupSession.sentenceTranslation,
            sentenceTranslationError = lookupSession.sentenceTranslationError,
            sentenceTranslationLoading = lookupSession.sentenceTranslationLoading,
            isSaved = savedId != null && savedWords.any { word -> word.id == savedId },
            isPhraseView = lookupSession.showingRelatedPhrase,
            showReviewEntry = reminders.contextHighlight,
            retranslateAvailable = aiSettings.powerEnabled && aiSettings.remoteReady && book.isAiTranslation,
            retranslateLoading = lookupSession.retranslateLoading,
            retranslateDoneTick = lookupSession.retranslateDoneTick,
            onRetranslate = { feedback ->
                val hit = lookupSession.translation ?: return@LookupSheet
                if (lookupSession.retranslateLoading) return@LookupSheet
                lookupSession = lookupSession.startRetranslate()
                viewModel.retranslateTranslation(book, hit, feedback.ifBlank { null }) { ok ->
                    // 弹层开着时全局 Snackbar 不可见，结果走行内 lookupStatus。
                    val status = if (ok) {
                        SettingsStatus.success(
                            context.getString(R.string.reader_translation_retranslated)
                        )
                    } else {
                        SettingsStatus.danger(
                            context.getString(R.string.reader_translation_retranslate_failed)
                        )
                    }
                    if (ok) {
                        // 重查拿新译文与新词级对齐（WordAligner 查询时现算）。
                        scope.launch {
                            lookupSession = lookupSession.finishRetranslate(
                                status = status,
                                translation = viewModel.translationLookup(book, position.chapter, currentLookup)
                            )
                        }
                    } else {
                        lookupSession = lookupSession.finishRetranslate(status = status)
                    }
                }
            },
            onReviewSaved = {
                val word = savedId?.let { id -> savedWords.firstOrNull { w -> w.id == id } }
                if (word != null) {
                    lookup = null
                    reviewDeckIds = listOf(word.id)
                }
            },
            onShowPhrase = { lookupSession = lookupSession.setShowingRelatedPhrase(true) },
            onShowWord = { lookupSession = lookupSession.setShowingRelatedPhrase(false) },
            onSpeak = {
                onSpeak(displayedEntry?.matchedPhrase ?: displayedEntry?.headword ?: currentLookup.word)
            },
            onToggleSave = {
                val entry = displayedEntry ?: return@LookupSheet
                if (savedId != null && savedWords.any { word -> word.id == savedId }) {
                    val removedHeadword = savedWords
                        .firstOrNull { word -> word.id == savedId }?.headword
                    viewModel.removeSavedWord(savedId)
                    // 弹层开着时全局 Snackbar 不可见，反馈改在弹层内联展示。
                    lookupSession = lookupSession.setStatus(
                        SettingsStatus.success(
                            context.getString(
                                R.string.notice_word_removed,
                                removedHeadword ?: currentLookup.word
                            )
                        )
                    )
                } else {
                    viewModel.saveWord(
                        book,
                        book.chapters[position.chapter].title,
                        currentLookup,
                        entry,
                        lookupSession.aiResult
                    )
                    lookupSession = lookupSession.setStatus(
                        SettingsStatus.success(
                            context.getString(R.string.notice_word_saved, currentLookup.word)
                        )
                    )
                }
            },
            onTranslateSentence = {
                val key = currentLookup.sentence.trim()
                val cached = lookupSession.cachedSentenceOrNull(key)
                when {
                    lookupSession.sentenceTranslationLoading -> Unit
                    cached != null -> {
                        lookupSession = lookupSession.withSentenceTranslation(cached)
                    }
                    else -> scope.launch {
                        lookupSession = lookupSession.beginSentenceTranslation()
                        // 成功与失败都必须留下可见结果：静默失败是历史缺陷。
                        runCatching { viewModel.translateSentence(book, currentLookup.sentence) }
                            .onSuccess { result ->
                                lookupSession = lookupSession.cacheSentence(key, result)
                            }
                            .onFailure {
                                lookupSession = lookupSession.withSentenceTranslationError(
                                    context.getString(R.string.translate_sentence_failed)
                                )
                            }
                    }
                }
            },
            onDismiss = {
                lookup = null
                lookupSession = lookupSession.setShowingRelatedPhrase(false).clearStatus()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(
    book: Book,
    currentChapter: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        modifier = Modifier.testTag(UiTags.READER_CONTENTS_SHEET)
    ) {
        Text(
            stringResource(R.string.reader_contents),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        LazyColumn(Modifier.fillMaxHeight(.72f)) {
            itemsIndexed(book.chapters) { index, chapter ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    // 序号作为标题文本一部分渲染（书源标题常不带编号，
                    // 纯序号列太淡易被当成没有序号）。
                    Text(
                        "${index + 1}. ${chapter.title}",
                        color = if (index == currentChapter) Accent else Ink,
                        fontWeight = if (index == currentChapter) FontWeight.Bold else FontWeight.Normal
                    )
                }
                HorizontalDivider(color = Ink.copy(alpha = .08f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    preferences: ReaderPreferences,
    reviewPace: ReviewPace,
    onOpenReviewSettings: () -> Unit,
    onOpenListeningSettings: () -> Unit,
    onChange: (ReaderPreferences) -> Unit,
    onDismiss: () -> Unit
) {
    var fontSize by remember(preferences.fontPercent) { mutableFloatStateOf(preferences.fontPercent.toFloat()) }
    var lineHeight by remember(preferences.lineHeight) { mutableFloatStateOf(preferences.lineHeight) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        modifier = Modifier.testTag(UiTags.READER_SETTINGS_SHEET)
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.reader_settings_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.reader_font_size, fontSize.roundToInt()))
            Slider(
                value = fontSize,
                onValueChange = {
                    fontSize = it
                    onChange(preferences.copy(fontPercent = it.roundToInt()))
                },
                valueRange = 80f..150f,
                steps = 6
            )
            Text(stringResource(R.string.reader_line_height, "%.1f".format(lineHeight)))
            Slider(
                value = lineHeight,
                onValueChange = {
                    lineHeight = it
                    onChange(preferences.copy(lineHeight = it))
                },
                valueRange = 1.2f..2.2f,
                steps = 4
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.reader_theme_label), color = InkSoft)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.Top
            ) {
                ReaderTheme.entries.forEach { theme ->
                    val selected = theme == preferences.theme
                    // 命中区盖住「色块 + 文字标签」整列：真机曾实测只有色块可点、
                    // 点文字落空还容易误触相邻选项，clickable 必须放在外层容器。
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onChange(preferences.copy(theme = theme)) }
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(theme.background)))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) Accent else Ink.copy(alpha = .18f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(android.graphics.Color.parseColor(theme.foreground)),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(theme.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) Accent else InkSoft,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.reader_font_label), color = InkSoft)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReaderFont.entries.forEach { font ->
                    val fontLabel = stringResource(font.labelRes)
                    TextButton(onClick = { onChange(preferences.copy(fontFamily = font)) }) {
                        Text(
                            if (font == preferences.fontFamily) {
                                stringResource(R.string.reader_font_selected, fontLabel)
                            } else {
                                fontLabel
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Ink.copy(alpha = .1f))
            // 整行（文字 + 右侧 › 箭头）都是命中区：此前只有箭头按钮可点，
            // 真机两次点文字均无反应。heightIn 保持原 TextButton 的 48dp 触控高度。
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onOpenReviewSettings),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.review_pace_section),
                    modifier = Modifier.weight(1f),
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${stringResource(reviewPace.labelRes)} ›",
                    color = Accent,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onOpenListeningSettings),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.tts_settings_title),
                    modifier = Modifier.weight(1f),
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("›", color = Accent, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun PageJumpDialog(
    currentPage: Int,
    pageCount: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf((currentPage + 1).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    // onClick 不是 @Composable 作用域，错误文案先在这里取出来。
    val jumpErrorText = stringResource(R.string.reader_jump_error, pageCount)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(UiTags.READER_PAGE_JUMP_DIALOG),
        title = { Text(stringResource(R.string.reader_jump_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.reader_jump_hint, pageCount, pageCount),
                    color = InkSoft,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { raw ->
                        input = raw.filter { it.isDigit() }.take(6)
                        error = null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.reader_jump_page_label)) },
                    isError = error != null,
                    supportingText = {
                        val message = error
                        if (message != null) Text(message)
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = parsePageInput(input, pageCount)
                    if (target == null) {
                        error = jumpErrorText
                    } else {
                        onJump(target)
                        onDismiss()
                    }
                }
            ) { Text(stringResource(R.string.reader_jump_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        containerColor = CardSurface,
        shape = CardShape
    )
}

internal fun parsePageInput(raw: String, pageCount: Int): Int? {
    if (pageCount <= 0) return null
    val page = raw.toIntOrNull() ?: return null
    if (page < 1 || page > pageCount) return null
    return page - 1
}

/**
 * 译本对照的展示文本：词级对照命中时把中文句里对应的词着色加粗；未命中（或
 * 偏移越界）就原样显示整句 —— 宁可不高亮，不可错标。
 */
@Composable
private fun highlightedTranslation(result: TranslationLookupResult): AnnotatedString {
    val text = result.chinese
    val alignment = result.wordAlignment
    if (alignment == null ||
        alignment.start < 0 ||
        alignment.endExclusive > text.length ||
        alignment.start >= alignment.endExclusive
    ) {
        return AnnotatedString(text)
    }
    val accent = Accent
    return buildAnnotatedString {
        append(text.substring(0, alignment.start))
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
            append(text.substring(alignment.start, alignment.endExclusive))
        }
        append(text.substring(alignment.endExclusive))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LookupSheet(
    lookup: WordLookup,
    entry: ContextualDictionaryEntry?,
    relatedPhrase: ContextualDictionaryEntry?,
    loading: Boolean,
    saveStatus: SettingsStatus?,
    aiContext: AiLookupResult?,
    aiLoading: Boolean,
    aiFailed: Boolean,
    aiRemoteFailed: Boolean,
    sentenceTranslationReady: Boolean,
    hasTranslation: Boolean,
    translation: TranslationLookupResult?,
    translationLoading: Boolean,
    sentenceTranslation: SentenceTranslationResult?,
    sentenceTranslationError: String?,
    sentenceTranslationLoading: Boolean,
    isSaved: Boolean,
    isPhraseView: Boolean,
    showReviewEntry: Boolean,
    /** 句级重翻入口：AI 可用 + 当前命中是句级时才出现。 */
    retranslateAvailable: Boolean,
    retranslateLoading: Boolean,
    /** 每次重翻结束（无论成败）自增；编辑态据此折叠并清空反馈。 */
    retranslateDoneTick: Int,
    onRetranslate: (String) -> Unit,
    onReviewSaved: () -> Unit,
    onShowPhrase: () -> Unit,
    onShowWord: () -> Unit,
    onSpeak: () -> Unit,
    onToggleSave: () -> Unit,
    onTranslateSentence: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        modifier = Modifier.testTag(UiTags.READER_LOOKUP_SHEET)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 30.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    lookup.word,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (!entry?.phonetic.isNullOrBlank()) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "/${entry?.phonetic}/",
                        color = Ink.copy(alpha = .58f),
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
            if (entry?.matchedPhrase != null) {
                Text(
                    if (isPhraseView) {
                        stringResource(R.string.reader_phrase_view, entry.matchedPhrase)
                    } else {
                        stringResource(R.string.reader_phrase_first, entry.matchedPhrase)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = Accent
                )
            } else if (entry != null && !entry.headword.equals(lookup.word, ignoreCase = true)) {
                Text(
                    stringResource(R.string.reader_lemma, entry.headword),
                    style = MaterialTheme.typography.labelMedium,
                    color = Accent
                )
            }
            if (relatedPhrase != null && !isPhraseView) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(
                            R.string.reader_related_phrase,
                            relatedPhrase.matchedPhrase ?: relatedPhrase.headword
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = Ink.copy(alpha = .72f),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onShowPhrase) { Text(stringResource(R.string.reader_view)) }
                }
            }
            if (isPhraseView) {
                TextButton(onClick = onShowWord) { Text(stringResource(R.string.reader_back_to_word)) }
            }
            if (entry != null && entry.inferredPartOfSpeech != PartOfSpeech.UNKNOWN) {
                Text(
                    stringResource(
                        R.string.reader_pos_inferred,
                        stringResource(entry.inferredPartOfSpeech.labelRes)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.copy(alpha = .56f)
                )
            }
            if (entry != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onSpeak) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_speak))
                    }
                    if (isSaved && showReviewEntry) {
                        TextButton(onClick = onReviewSaved) {
                            Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.common_review))
                        }
                    }
                    TextButton(onClick = onToggleSave) {
                        Icon(
                            Icons.Filled.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isSaved) Accent else InkSoft
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isSaved) stringResource(R.string.reader_remove_from_vocab)
                            else stringResource(R.string.reader_add_to_vocab)
                        )
                    }
                }
            }
            // 收藏/移出生词的行内反馈：弹层开着时全局 Snackbar 被盖住，只能画在面板里。
            if (saveStatus != null) {
                Spacer(Modifier.height(4.dp))
                SettingsStatusText(saveStatus)
            }
            if (aiLoading) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .width(72.dp)
                            .height(4.dp),
                        color = Accent,
                        trackColor = Accent.copy(alpha = .12f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.reader_ai_loading),
                        style = MaterialTheme.typography.labelMedium,
                        color = InkSoft
                    )
                }
            }
            // 远程 AI 尝试失败：无论本地兜底有没有给出结果，都要留下可见痕迹
            // （#3：Key/端点配错后点词「毫无动静」的根因就是失败被静默吞掉）。
            if (!aiLoading && aiRemoteFailed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        if (aiContext != null) R.string.reader_ai_lookup_degraded
                        else R.string.reader_ai_lookup_failed
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = Danger
                )
            }
            if (!aiLoading && aiFailed && aiContext == null && !aiRemoteFailed) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.reader_ai_lookup_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSoft
                )
            }
            if (!aiLoading && aiContext != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.reader_ai_context_title, aiContext.source),
                    style = MaterialTheme.typography.labelLarge,
                    color = Accent,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    aiContext.contextualMeaning,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                aiContext.phrase?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.reader_ai_phrase, it),
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink.copy(alpha = .62f)
                    )
                }
                if (aiContext.explanation.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        aiContext.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.copy(alpha = .72f)
                    )
                }
            }
            if (translationLoading) {
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.reader_translation_loading),
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSoft
                )
            }
            var pairingExpanded by remember(lookup.word, lookup.sentence) { mutableStateOf(false) }
            // 句级重翻的编辑态：反馈文本与展开折叠都是纯 UI 状态，放这里；
            // doneTick 参与 key，重翻结束（成败皆然）自动折叠清空。
            var retranslateEditing by remember(lookup.word, lookup.sentence, retranslateDoneTick) {
                mutableStateOf(false)
            }
            var retranslateFeedback by remember(lookup.word, lookup.sentence, retranslateDoneTick) {
                mutableStateOf("")
            }
            translation?.let { result ->
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.reader_translation_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = Accent,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    highlightedTranslation(result),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        if (result.matchLevel == TranslationMatchLevel.SENTENCE) {
                            R.string.reader_translation_meta_sentence
                        } else {
                            R.string.reader_translation_meta_paragraph
                        },
                        result.translationTitle,
                        (result.confidence * 100).roundToInt()
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.copy(alpha = .62f)
                )
                if (pairingExpanded) {
                    // 整句对照：并排给出配对到的英文原句与译文段落，便于判断配对是否正确。
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.reader_translation_english, result.english),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.copy(alpha = .78f)
                    )
                    if (result.chineseParagraph.isNotBlank() &&
                        result.chineseParagraph != result.chinese
                    ) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(
                                R.string.reader_translation_paragraph,
                                result.chineseParagraph
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink.copy(alpha = .78f)
                        )
                    }
                }
            }
            // 配了译本却没查到：直说是「这段/这章没参与对齐」，而不是让面板凭空少一块。
            if (hasTranslation && translation == null && !translationLoading) {
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.reader_translation_miss),
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSoft
                )
            }
            if (sentenceTranslationReady || translation != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 云端整句翻译只在真配好了提供方时出现：没配就别摆一个按不动的按钮。
                    if (sentenceTranslationReady) {
                        TextButton(
                            onClick = onTranslateSentence,
                            enabled = !sentenceTranslationLoading
                        ) {
                            Icon(
                                Icons.Filled.Translate,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Accent
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.translate_sentence), color = Accent)
                        }
                        if (sentenceTranslationLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .width(72.dp)
                                    .height(4.dp),
                                color = Accent,
                                trackColor = Accent.copy(alpha = .12f)
                            )
                        }
                    }
                    // 有中文译本时，用完全离线的「整句对照」顶上这个位置。
                    if (translation != null) {
                        TextButton(onClick = { pairingExpanded = !pairingExpanded }) {
                            Icon(
                                Icons.Filled.CompareArrows,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Accent
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(
                                    if (pairingExpanded) R.string.reader_translation_collapse
                                    else R.string.reader_translation_expand
                                ),
                                color = Accent
                            )
                        }
                    }
                    // 句级定点重翻：AI 可用且命中是句级时才出现（段落级兜底没有
                    // 确定的句对条目，不开放）。
                    val retranslateHit = translation
                    if (retranslateHit != null &&
                        retranslateHit.matchLevel == TranslationMatchLevel.SENTENCE &&
                        retranslateAvailable
                    ) {
                        TextButton(
                            onClick = { retranslateEditing = !retranslateEditing },
                            enabled = !retranslateLoading
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Accent
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.reader_translation_retranslate),
                                color = Accent
                            )
                        }
                    }
                }
                // 重翻编辑态：反馈可留空（= 原样重试换一次结果）；进行态保留旧译文。
                if (retranslateEditing &&
                    translation?.matchLevel == TranslationMatchLevel.SENTENCE &&
                    retranslateAvailable
                ) {
                    Spacer(Modifier.height(6.dp))
                    if (retranslateLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                modifier = Modifier.width(72.dp).height(4.dp),
                                color = Accent,
                                trackColor = Accent.copy(alpha = .12f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.reader_translation_retranslating),
                                style = MaterialTheme.typography.labelMedium,
                                color = InkSoft
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = retranslateFeedback,
                            onValueChange = { retranslateFeedback = it },
                            placeholder = {
                                Text(
                                    stringResource(R.string.reader_translation_retranslate_hint),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { retranslateEditing = false }) {
                                Text(stringResource(R.string.common_cancel), color = InkSoft)
                            }
                            TextButton(onClick = { onRetranslate(retranslateFeedback) }) {
                                Text(
                                    stringResource(R.string.reader_translation_retranslate_go),
                                    color = Accent
                                )
                            }
                        }
                    }
                }
            }
            sentenceTranslationError?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Danger
                )
            }
            sentenceTranslation?.let { result ->
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.translate_sentence_result, result.provider, result.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink
                )
            }
            Spacer(Modifier.height(14.dp))
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                entry != null -> {
                    entry.senses.take(8).forEachIndexed { index, sense ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                if (sense.contextPreferred && index == 0) {
                                    stringResource(R.string.reader_sense_preferred)
                                } else {
                                    "•"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sense.contextPreferred && index == 0) Accent
                                else Ink.copy(alpha = .38f),
                                modifier = Modifier.width(if (sense.contextPreferred && index == 0) 64.dp else 20.dp)
                            )
                            Text(
                                sense.text,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (entry.senses.isEmpty()) {
                        Text(
                            stringResource(R.string.reader_no_senses),
                            color = Ink.copy(alpha = .62f)
                        )
                    }
                    if (entry.definitions.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            entry.definitions.take(2).joinToString("\n"),
                            color = Ink.copy(alpha = .62f),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                else -> Text(
                    stringResource(R.string.reader_word_not_found),
                    color = Ink.copy(alpha = .62f)
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.reader_current_context),
                style = MaterialTheme.typography.labelLarge,
                color = Accent
            )
            Spacer(Modifier.height(5.dp))
            Text(
                lookup.sentence.ifBlank { lookup.paragraph },
                fontFamily = FontFamily.Serif,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.reader_dict_source),
                style = MaterialTheme.typography.labelSmall,
                color = Ink.copy(alpha = .45f)
            )
        }
    }
}
