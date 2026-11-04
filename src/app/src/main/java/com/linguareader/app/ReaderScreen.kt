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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.linguareader.app.components.LinguaIconTextButton
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.components.SettingRow
import com.linguareader.app.components.SheetDivider
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
import com.linguareader.app.reader.LocusOrigin
import com.linguareader.app.reader.ReaderBackAction
import com.linguareader.app.reader.ReaderController
import com.linguareader.app.reader.ReaderLookupSession
import com.linguareader.app.reader.ReaderOverlays
import com.linguareader.app.reader.ReaderPosition
import com.linguareader.app.reader.ReaderScripts
import com.linguareader.app.reader.SentenceTranslationCache
import com.linguareader.app.res.resolve
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.translation.TranslationLookupResult
import com.linguareader.app.translation.TranslationMatchLevel
import com.linguareader.app.tts.TtsPlaybackController
import com.linguareader.app.tts.TtsPlaybackState
import com.linguareader.shared.reader.SavedWordMarks
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
 *
 * 存档格式故意放在 [ReaderPosition.toSaveList] / [ReaderPosition.fromSaveList]：
 * 它在 reader 包里，能被 JVM 单测直接盯住。放在这里当匿名 lambda 时漏过一次
 * 字段——M1 加的三个锚点字段没进存档，旋转后锚点被还原成 -1，重排漂移复现，
 * 而单测全绿。存档与状态定义必须住在一起。
 */
private val ReaderPositionSaver = Saver<ReaderPosition, List<Any>>(
    save = { it.toSaveList() },
    restore = { values -> ReaderPosition.fromSaveList(values) }
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

/**
 * 阅读页外壳（顶栏 / 底栏 / 目录 / 设置 / 查词面板）+ WebView 宿主装配。
 *
 * ## 本屏**刻意不用** `LinguaScaffold`
 *
 * 三条理由，第一条是硬伤（套上去会引入真机事故，不是观感问题）：
 *
 * 1. **顶栏高度是 WebView 正文预留量的输入**。全屏工具栏的 `onSizeChanged` 把实测
 *    高度写进 `chromeTopPx` → `EpubPage` → `ReaderController.applyChromeInsets` →
 *    `window.lrSetChromeInsets` → CSS 变量 `--lr-chrome-top`，直接决定正文从哪里开始。
 *    `LinguaScaffold` 把顶栏封在内部、不暴露测量点，套进去就拿不到这个值 ——
 *    等于改正文布局，而正文布局正是 `known-pitfalls` 里反复出事的地方。
 * 2. **主体是 Box 叠层，不是 Column 流式内容**：`EpubPage` 打底，顶栏 / 底栏 /
 *    听书条 / 复习横幅各自 `align` 到同一个 Box 的 TopCenter / BottomCenter，
 *    并且顶栏显隐用 `AnimatedVisibility` 包着。`LinguaScaffold` 的 content 槽是
 *    `Column(weight(1f))`，塞进去会改变叠放语义。
 * 3. **配色不归皮肤管**：顶栏取外壳调色板的 `Paper`，底栏与听书条取 `ReaderTheme`
 *    的 `background`/`foreground` —— 阅读页外壳必须跟正文同色系，
 *    见 `ListeningBar` 的类注释（护眼绿主题下的历史事故）。
 *
 * **结论**：外壳统一不适用于「主体是 WebView + 多层叠放」的屏幕。迁移本屏时只换
 * 内部排版（间距 / 图标尺寸 / 排版梯），不换外壳、不换色源。
 *
 * ## 红线（迁移时一行都不能动）
 *
 * - 16 个 `window.lrXxx` 的调用时序（桌面 JCEF 依赖同一套协议）；
 * - `followRangeIntoView` 的三道护栏（只在句子不在当前页时翻 / 300ms 合并 /
 *   用户接管窗口内不翻）；
 * - `ReaderBridge` 的回调链；
 * - [ReaderBackAction] 的返回键优先级链（lookup > settings > contents > pageJump > 退出）；
 * - TTS 高亮定位（`showTtsHighlight` / `scrollToLocus` / `backToSpeaking` 的调用点）；
 * - `EpubPage` / `ReaderController` / `ReaderScreenState` 的任何一行（引擎侧，不是 UI）。
 */
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
    // 设计 token：本屏只用来取「布局/间距/图标尺寸」。
    // **配色不在这里取**——顶栏 Paper 来自外壳调色板，底栏/听书条来自 ReaderTheme，
    // 两者都是「贴正文」的语义，换成皮肤 token 会与正文不同源（ListeningBar 同款理由）。
    val t = LocalDesignTokens.current
    // 实测顶栏/底栏高度（px → dp，1dp = 1 CSS px），注入 WebView 作为正文
    // 预留量。targetSdk 35 强制 edge-to-edge 后底栏含导航栏 inset，写死的
    // 70px 预留不够，会遮住正文最后一行；改为动态注入后永远精确。
    var chromeTopPx by remember {
        mutableFloatStateOf(ReaderScripts.DEFAULT_CHROME_TOP_PX.toFloat())
    }
    var chromeBottomPx by remember {
        mutableFloatStateOf(ReaderScripts.DEFAULT_CHROME_BOTTOM_PX.toFloat())
    }
    // 听书条实测高度（px）。它与底部翻页栏**重叠**而非堆叠（听书时底栏隐藏），
    // 所以预留量取两者较大值而非相加。此前听书条从未参与测量，正文末行被它盖住
    // （BUG-033）。
    var listeningBarPx by remember { mutableFloatStateOf(0f) }
    val ttsState by TtsPlaybackController.state.collectAsStateWithLifecycle()
    val ttsForThisBook = ttsState.bookId == book.id
    // 交给 WebView 的底部预留量：听书时以听书条为准，但听书条首帧还没测到时
    // （listeningBarPx 仍为 0）先沿用底栏高度，避免正文先跳一下再回落。
    val reservedBottomPx = if (ttsForThisBook) {
        maxOf(chromeBottomPx, listeningBarPx)
    } else {
        chromeBottomPx
    }
    // 朗读句已跑出视口（用户接管期间不硬翻页，改在听书条上点亮「回到朗读处」）。
    var speakingOffscreen by remember { mutableStateOf(false) }

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
    // 词面标记载荷：普通生词=点线，待复习词=实线。到期口径与上面的 dueWords 同源
    // （同一个 nowTick），展开/去重/截断的纯逻辑在 shared/reader/SavedWordMarks.kt，可单测。
    val wordMarks = remember(savedWords, nowTick) {
        SavedWordMarks.of(savedWords, nowTick)
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

    /**
     * 听书条的「回到朗读处」：把视口挪回正在朗读的那句并恢复自动跟随。
     *
     * 不需要再压制任何回报——「翻页拽动朗读」那条路已经整条删除（方案 §8.1
     * 拍板：页面跟朗读，不是朗读跟页面）。落位后重画一次高亮即可。
     */
    fun backToSpeaking() {
        if (ttsState.highlightBlockIndex < 0) return
        controller.backToSpeaking(ttsState.highlightBlockIndex, ttsState.highlightOffset)
        scope.launch {
            delay(400)
            highlightCurrentTts(controller, ttsState)
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
                savedWords = if (reminders.contextHighlight) wordMarks.forms else emptyList(),
                dueWords = if (reminders.contextHighlight) wordMarks.dueForms else emptyList(),
                chromeTopPx = chromeTopPx.roundToInt(),
                chromeBottomPx = reservedBottomPx.roundToInt(),
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
                onPageChanged = { page, count, origin ->
                    position = position.onPageChanged(page, count)
                    refreshLocusDelayed()
                    // 只有**用户自己**翻页才清高亮：那一下确实把高亮的文字翻走了。
                    // 听书跟随（Tts）与「回到朗读处」（Jump）恰恰是奔着高亮去的，
                    // 清掉会让人看见高亮闪一下没了，要等下一句才回来。
                    if (ttsForThisBook && origin == LocusOrigin.User) {
                        controller.clearHighlight()
                    }
                },
                onSpeakingOffscreen = { offscreen -> speakingOffscreen = offscreen },
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
                        // ⚠️ 这行把实测高度喂给 WebView 的 --lr-chrome-top，
                        // 是正文预留量的唯一来源，**不能删也不能换成固定值**。
                        if (size.height > 0) chromeTopPx = size.height / density
                    }
                    // 顶栏底色刻意仍取外壳调色板的 Paper（不换成 token）：
                    // 迁移范围只到「布局/间距/图标尺寸」，换色源属于行为改动。
                    // 底栏相反，本来就取 preferences.theme.background —— 两者
                    // 在自定义皮肤下会不同源，那是既有实现，不是本次引入的。
                    .background(Paper.copy(alpha = ChromeScrimAlpha))
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = t.spacing.xs, vertical = t.spacing.xs)
                        .testTag(UiTags.READER_TOP_BAR),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = ::closeWithFlush) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.reader_back),
                            tint = InkSoft,
                            modifier = Modifier.size(t.iconSize)
                        )
                    }
                    Text(
                        text = book.chapters[position.chapter].title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = t.typography.body,
                        fontWeight = FontWeight.Medium,
                        color = Ink
                    )
                    if (reminders.toolbarBadge && dueWords.isNotEmpty()) {
                        // BUG-032 余项（Q1-t05）：复习牌组给**全部**到期词。
                        // 原先 take(sessionMaxWords) 让卡片分母恒为 5，用户看到「1/5」
                        // 却不知道还有更多待复习；sessionMaxWords 不再截断牌组。
                        TextButton(onClick = { reviewDeckIds = dueWords.map { it.id } }) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.reader_review_badge,
                                    dueWords.size,
                                    dueWords.size
                                ),
                                style = t.typography.label,
                                color = Accent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    LinguaIconTextButton(
                        text = stringResource(R.string.reader_contents),
                        icon = Icons.AutoMirrored.Filled.List,
                        onClick = { overlays = overlays.copy(contents = true) }
                    )
                    // 听书按钮**不用 LinguaIconTextButton**：它的图标与文字不是一个颜色
                    // （图标在「本书正在听」时转 accent，文字恒为 ink），而该组件的 tone
                    // 会把两者一起染色。这是状态指示，不是样式选择，故保留手写结构，
                    // 只把尺寸/间距换成 token。
                    TextButton(onClick = ::startOrToggleListening) {
                        Icon(
                            imageVector = Icons.Filled.Headphones,
                            contentDescription = null,
                            modifier = Modifier.size(t.iconSize),
                            tint = if (ttsForThisBook) Accent else InkSoft
                        )
                        Spacer(Modifier.width(t.spacing.xs))
                        Text(
                            text = if (ttsForThisBook) {
                                stringResource(R.string.reader_listening_active)
                            } else {
                                stringResource(R.string.reader_listening)
                            },
                            style = t.typography.body,
                            color = Ink
                        )
                    }
                    // 「Aa」是设置入口的图形化表达（不是文字）。文字色保持 TextButton 的
                    // 默认强调色（迁移前就是这个观感，不要改成 Ink）。
                    TextButton(onClick = { overlays = overlays.copy(settings = true) }) {
                        Text("Aa", style = t.typography.body, fontWeight = FontWeight.Bold)
                    }
                }
                LinearProgressIndicator(
                    progress = { position.progress(book.chapters.size) },
                    modifier = Modifier.fillMaxWidth().height(t.hairline * 2),
                    color = Accent,
                    trackColor = Accent.copy(alpha = ChromeTrackAlpha)
                )
            }
        }

        // semantics 作用域不是 @Composable，无障碍标签先在这里取出来。
        val prevPageLabel = stringResource(R.string.reader_prev_page)
        val nextPageLabel = stringResource(R.string.reader_next_page)
        val pageIndicatorLabel = stringResource(R.string.reader_page_indicator)
        if (!ttsForThisBook) {
            Row(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 听书时整行移出组合，让位给盖在同一位置的听书条（不用 alpha=0：
                    // 那会留下看不见却能点中的幽灵控件，见 known-pitfalls 第 22 条）。
                    // 移出后 chromeBottomPx 保留最后一次实测值，正好作为听书条消失后
                    // 的回落基准，不会退回 70px 默认值。
                    .onSizeChanged { size ->
                        if (size.height > 0) chromeBottomPx = size.height / density
                    }
                    .background(Color(android.graphics.Color.parseColor(preferences.theme.background)).copy(alpha = ChromeBarAlpha))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = t.spacing.m, vertical = t.spacing.s),
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
                        style = t.typography.label,
                        color = Color(android.graphics.Color.parseColor(preferences.theme.foreground))
                            .copy(alpha = ChromeIndicatorAlpha),
                        modifier = Modifier
                            .semantics { contentDescription = pageIndicatorLabel }
                            .clickable { overlays = overlays.copy(pageJump = true) }
                    )
                    // 「分页」按钮的文字色跟正文前景（阅读主题），不是外壳 token：
                    // 底栏贴在正文底部，必须与正文同色系，理由见 ListeningBar 的 KDoc。
                    TextButton(onClick = controller::exitScrollMode) {
                        Text(
                            text = stringResource(R.string.reader_pagination),
                            style = t.typography.body,
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
                        style = t.typography.label,
                        color = Color(android.graphics.Color.parseColor(preferences.theme.foreground))
                            .copy(alpha = ChromeIndicatorAlpha),
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
        }

        AnimatedVisibility(
            visible = ttsForThisBook,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ListeningBar(
                state = ttsState,
                theme = preferences.theme,
                modifier = Modifier
                    // 沉到屏幕底缘：与底栏同一位置（听书时底栏已移出组合）。
                    // 导航栏 inset 由条自身补，避免被系统导航条盖住。
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .onSizeChanged { size ->
                        if (size.height > 0) listeningBarPx = size.height / density
                    }
                    .testTag(UiTags.READER_LISTENING_BAR),
                onToggle = { TtsPlaybackController.toggle(context) },
                onPrevious = { TtsPlaybackController.previous(context) },
                onNext = { TtsPlaybackController.next(context) },
                onStop = { TtsPlaybackController.stop(context) },
                onRateChange = { TtsPlaybackController.setRate(context, it) },
                choosingStart = overlays.choosingStart,
                onChooseStart = {
                    overlays = overlays.copy(choosingStart = !overlays.choosingStart)
                    controller.setChoosingStart(overlays.choosingStart)
                },
                speakingOffscreen = speakingOffscreen && ttsState.isPlaying,
                onBackToSpeaking = { backToSpeaking() },
                // 滑动模式的唯一出口：听书时底栏已隐藏，出口必须跟着搬过来。
                onExitScrollMode = if (position.scrollMode) {
                    { controller.exitScrollMode() }
                } else {
                    null
                }
            )
        }

        AnimatedVisibility(
            visible = overlays.reviewPrompt && dueWords.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier
                    // 听书时听书条占着底缘，横幅要抬到它上面；否则两者叠在一起。
                    .padding(bottom = if (ttsForThisBook) maxOf(chromeBottomPx, listeningBarPx).dp else chromeBottomPx.dp)
                    .testTag(UiTags.REVIEW_PROMPT_BANNER)
            ) {
                ReviewPromptBanner(
                    count = dueWords.size,
                    dwellMillis = reviewPace.dwellMillis,
                    onStart = {
                        overlays = overlays.startReviewFromPrompt()
                        // 同工具栏入口：一次复习全部到期词，不再按 sessionMaxWords 截断。
                        reviewDeckIds = dueWords.map { it.id }
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
                            context.getString(
                                R.string.notice_word_saved,
                                entry.matchedPhrase ?: entry.headword
                            )
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

/**
 * 目录弹层：外壳走 [LinguaSheet]，内容体是 [ContentsBody]。
 *
 * 迁移前这里是手写 `ModalBottomSheet(containerColor = Paper)` + 自己写的标题排版
 * 与 24dp 内边距。**只换外壳与样式，章节列表的渲染口径一个字没改**：
 * 序号仍然拼进标题文本、当前章仍然用强调色 + 粗体、点击仍然整行命中。
 */
@Composable
private fun ContentsSheet(
    book: Book,
    currentChapter: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    LinguaSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.reader_contents),
        // 内容体自己带 LazyColumn（列表自身不产生块间距），关掉容器的默认 spacedBy，
        // 否则标题与列表之间会多出一份 spacing.m。
        contentArrangement = Arrangement.Top
    ) {
        ContentsBody(
            book = book,
            currentChapter = currentChapter,
            onSelect = onSelect
        )
    }
}

/**
 * 目录的**内容体**，与 sheet 外壳解耦。
 *
 * 理由与其余已迁弹层一致：`ModalBottomSheet` 在渲染台里画不进位图，抽出内容体才能
 * 出迁移前后的对照图。代价也一样——**不再自带纸色底**，单独使用时调用方必须补齐
 * （渲染台见 `ReaderShellRenderTest`）。
 *
 * 横向留白不再自己写：由 [LinguaSheet] 的 content 槽提供 `spacing.l`。
 * 滚动容器 `LazyColumn` **仍然由本组件自己拥有**——换外壳时最容易丢的就是它
 * （PacksSheet 迁移踩过：`verticalScroll` 挂在外壳上，换壳后长内容拖不动）。
 */
@Composable
internal fun ContentsBody(
    book: Book,
    currentChapter: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalDesignTokens.current
    // ⚠️ 高度上限必须保留：迁移前是 `LazyColumn(Modifier.fillMaxHeight(.72f))`。
    // 拿掉上限后列表会「撑到弹层满高」，章节多的书目录会顶到状态栏下面 —— 那是
    // 可见的布局变化，不属于「只换外壳」。这里原样保留 72%，只把字面量命名化。
    // READER_CONTENTS_SHEET 原本挂在 ModalBottomSheet 上（LinguaSheet 不收 modifier，
    // 与 ReviewUi 的 REVIEW_SHEET 同一处理）；内容体是它的子树，语义查找照样命中。
    LazyColumn(
        modifier
            .fillMaxWidth()
            .fillMaxHeight(ContentsMaxHeightFraction)
            .testTag(UiTags.READER_CONTENTS_SHEET)
    ) {
        itemsIndexed(book.chapters) { index, chapter ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onSelect(index) }
                    .defaultMinSize(minHeight = t.controlHeight)
                    .padding(vertical = t.spacing.s)
            ) {
                // 序号作为标题文本一部分渲染（书源标题常不带编号，
                // 纯序号列太淡易被当成没有序号）。
                Text(
                    "${index + 1}. ${chapter.title}",
                    color = if (index == currentChapter) Accent else Ink,
                    style = t.typography.body,
                    fontWeight = if (index == currentChapter) FontWeight.Bold else FontWeight.Normal
                )
            }
            SheetDivider()
        }
    }
}

/**
 * 阅读设置弹层：外壳走 [LinguaSheet]，内容体是 [ReaderSettingsBody]。
 *
 * 字号 / 行距 / 主题 / 字体的**行为零改动**：两个 Slider 的 valueRange、steps
 * 与 `onChange` 的调用时机（拖动即写 prefs，无「确定」）全部原样保留。
 */
@Composable
private fun SettingsSheet(
    preferences: ReaderPreferences,
    reviewPace: ReviewPace,
    onOpenReviewSettings: () -> Unit,
    onOpenListeningSettings: () -> Unit,
    onChange: (ReaderPreferences) -> Unit,
    onDismiss: () -> Unit
) {
    LinguaSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.reader_settings_title),
        contentArrangement = Arrangement.Top
    ) {
        ReaderSettingsBody(
            preferences = preferences,
            reviewPace = reviewPace,
            onOpenReviewSettings = onOpenReviewSettings,
            onOpenListeningSettings = onOpenListeningSettings,
            onChange = onChange
        )
    }
}

/**
 * 阅读设置的**内容体**，与 sheet 外壳解耦（理由同 [ContentsBody]）。
 *
 * ⚠️ 两处**刻意不换成 token**，都是「跟着正文配色」的语义：
 * 1. 主题色块本身就是七个阅读主题的预览色（`theme.background`/`theme.foreground`），
 *    它渲染的是**正文**长什么样，不是外壳皮肤长什么样 —— 换成 token 会让七个色块
 *    全变成一个颜色。这与 `ListeningBar` 刻意不换是同一个理由。
 * 2. 选中描边用 `Accent`（外壳调色板的强调色），与该色块上的对勾同源；
 *    token 的 accent 在自定义皮肤下可能与之不同源。
 *
 * 横向留白由 [LinguaSheet] 给（`spacing.l`），这里不再自带。
 * 滚动容器 `verticalScroll` 仍由本组件自己拥有。
 */
@Composable
internal fun ReaderSettingsBody(
    preferences: ReaderPreferences,
    reviewPace: ReviewPace,
    onOpenReviewSettings: () -> Unit,
    onOpenListeningSettings: () -> Unit,
    onChange: (ReaderPreferences) -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalDesignTokens.current
    var fontSize by remember(preferences.fontPercent) {
        mutableFloatStateOf(preferences.fontPercent.toFloat())
    }
    var lineHeight by remember(preferences.lineHeight) { mutableFloatStateOf(preferences.lineHeight) }

    // READER_SETTINGS_SHEET 原本挂在 ModalBottomSheet 上（LinguaSheet 不收 modifier）；
    // 内容体是它的子树，语义查找照样命中。
    // 滚动容器在这里：这一屏在手机上会超出弹层可视高度，丢了它真机就拖不动。
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag(UiTags.READER_SETTINGS_SHEET)
    ) {
        Text(
            text = stringResource(R.string.reader_font_size, fontSize.roundToInt()),
            style = t.typography.caption,
            color = t.colors.inkSoft
        )
        Spacer(Modifier.height(t.spacing.xs))
        Slider(
            value = fontSize,
            onValueChange = {
                fontSize = it
                onChange(preferences.copy(fontPercent = it.roundToInt()))
            },
            valueRange = 80f..150f,
            steps = 6,
            colors = SliderDefaults.colors(
                thumbColor = t.colors.accent,
                activeTrackColor = t.colors.accent,
                inactiveTrackColor = t.colors.paperDeep
            )
        )
        Text(
            text = stringResource(R.string.reader_line_height, "%.1f".format(lineHeight)),
            style = t.typography.caption,
            color = t.colors.inkSoft
        )
        Spacer(Modifier.height(t.spacing.xs))
        Slider(
            value = lineHeight,
            onValueChange = {
                lineHeight = it
                onChange(preferences.copy(lineHeight = it))
            },
            valueRange = 1.2f..2.2f,
            steps = 4,
            colors = SliderDefaults.colors(
                thumbColor = t.colors.accent,
                activeTrackColor = t.colors.accent,
                inactiveTrackColor = t.colors.paperDeep
            )
        )

        SectionHeader(text = stringResource(R.string.reader_theme_label))
        // FlowRow：7 个主题一行放不下（7×48 + 6×18 = 444dp，而面板左右各留
        // spacing.l 后只有约 312dp），普通 Row 会把溢出部分压成堆叠的文字。换行后
        // 全部选项可见——主题是「选一次就很少动」的设置，可发现性优先于省高度。
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(t.spacing.m),
            verticalArrangement = Arrangement.spacedBy(t.spacing.s),
            itemVerticalAlignment = Alignment.Top
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
                            .size(t.spacing.xxl)
                            .clip(CircleShape)
                            .background(Color(android.graphics.Color.parseColor(theme.background)))
                            .border(
                                // 选中描边加粗一档（原 3dp）——取 token 间距梯的最小档，
                                // 不新造 dp 常量：换肤时间距梯跟着皮肤走。
                                width = if (selected) t.spacing.xs else t.hairline,
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
                                modifier = Modifier.size(t.iconSize)
                            )
                        }
                    }
                    Spacer(Modifier.height(t.spacing.xs))
                    Text(
                        stringResource(theme.labelRes.resolve()),
                        style = t.typography.label,
                        color = if (selected) Accent else InkSoft,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        SectionHeader(text = stringResource(R.string.reader_font_label))
        // 五款字体横向可滚：窄屏放不下，滚动容器是内容体自己带的（不依赖外壳）。
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(t.spacing.xs)
        ) {
            ReaderFont.entries.forEach { font ->
                val fontLabel = stringResource(font.labelRes.resolve())
                val selected = font == preferences.fontFamily
                // 选中标记「●」本来就在 reader_font_selected 这条文案里，
                // 所以这里**不能**再加图标（会变成「● ✓ 字体」双标记）。
                LinguaTextButton(
                    text = if (selected) {
                        stringResource(R.string.reader_font_selected, fontLabel)
                    } else {
                        fontLabel
                    },
                    onClick = { onChange(preferences.copy(fontFamily = font)) }
                )
            }
        }

        Spacer(Modifier.height(t.spacing.s))
        SheetDivider()
        // 整行（文字 + 右侧值/箭头）都是命中区：此前只有箭头按钮可点，
        // 真机两次点文字均无反应。
        SettingRow(
            title = stringResource(R.string.review_pace_section),
            onClick = onOpenReviewSettings,
            trailing = {
                Text(
                    text = "${stringResource(reviewPace.labelRes.resolve())} ›",
                    style = t.typography.caption,
                    color = t.colors.accent
                )
            }
        )
        SettingRow(
            title = stringResource(R.string.tts_settings_title),
            onClick = onOpenListeningSettings,
            trailing = {
                Text(text = "›", style = t.typography.caption, color = t.colors.accent)
            }
        )
    }
}


@Composable
private fun PageJumpDialog(
    currentPage: Int,
    pageCount: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalDesignTokens.current
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
                    color = t.colors.inkSoft,
                    style = t.typography.caption
                )
                Spacer(Modifier.height(t.spacing.s))
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

/**
 * 顶栏/底栏的遮罩不透明度（迁移前的字面量，这里只给它们名字）。
 *
 * 刻意**不走间距/圆角梯**：alpha 是「这条栏要透出多少正文」的颜色参数，
 * 不属于 spacing/radii 的语义位；token 里目前也没有 alpha 梯。
 */
private const val ChromeScrimAlpha = .96f

/** 底栏底色不透明度（比顶栏略低，让正文末行透出一丝纹理）。 */
private const val ChromeBarAlpha = .94f

/** 顶栏进度条轨道的不透明度。 */
private const val ChromeTrackAlpha = .12f

/** 底栏页码文字的不透明度（次级信息，压暗一档）。 */
private const val ChromeIndicatorAlpha = .6f

/**
 * 目录列表相对弹层可用高度的占比（迁移前就是 `.72f`）。
 *
 * 必须保留：不加上限时 LazyColumn 会把弹层撑到满高，长目录会顶进状态栏。
 * 这是**布局**约束不是样式值，因此待在调用点、不进 token。
 */
private const val ContentsMaxHeightFraction = .72f

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
                        // 5-6：音标属次级文字，原 .58 在浅色最弱表面 3.91:1
                        color = InkSoft,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
            // matchedPhrase 现在是跨模块（:shared）属性，Kotlin 不对其做智能转换，
            // 先捕获到局部量（语义与原来的 entry.matchedPhrase 完全一致）。
            val matchedPhrase = entry?.matchedPhrase
            if (matchedPhrase != null) {
                Text(
                    if (isPhraseView) {
                        stringResource(R.string.reader_phrase_view, matchedPhrase)
                    } else {
                        stringResource(R.string.reader_phrase_first, matchedPhrase)
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
                        stringResource(entry.inferredPartOfSpeech.labelRes.resolve())
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    // 5-6：原 .56 在浅色最弱表面（cardSurface）仅 3.70:1，不足 4.5:1
                    color = InkSoft
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
                        color = InkSoft
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
                    color = InkSoft
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
            // 中文句里高亮那个词来自哪条义项（`WordAligner` 记下的 `sourceSense`，与
            // 面板 `sense.text` 是同一个串）：标出该行、并用与高亮词完全相同的
            // 强调色 + 粗体，用户才能把「高亮的词」与「具体义项」互相指认（Q1-t09 ④）。
            val alignedSense = translation?.wordAlignment?.sourceSense
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                entry != null -> {
                    entry.senses.take(8).forEachIndexed { index, sense ->
                        val preferred = sense.contextPreferred && index == 0
                        // 是来源义项但不是「本句优先」时才占用标号位：两枚标记都不丢。
                        val aligned = alignedSense != null && sense.text == alignedSense
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                when {
                                    aligned && !preferred -> stringResource(R.string.reader_sense_aligned)
                                    preferred -> stringResource(R.string.reader_sense_preferred)
                                    else -> "•"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (aligned || preferred) Accent
                                // 5-6：项目符号也是文字，原 .38 在浅/深最弱表面仅 2.27/3.01:1
                                else InkFaint,
                                modifier = Modifier.width(if (aligned || preferred) 64.dp else 20.dp)
                            )
                            Text(
                                sense.text,
                                style = MaterialTheme.typography.bodyLarge,
                                // 与中文句里被着色的词同一套样式（Accent + Bold）。
                                color = if (aligned) Accent else Color.Unspecified,
                                fontWeight = if (aligned) FontWeight.Bold else null,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (entry.senses.isEmpty()) {
                        Text(
                            stringResource(R.string.reader_no_senses),
                            color = InkSoft
                        )
                    }
                    if (entry.definitions.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            entry.definitions.take(2).joinToString("\n"),
                            color = InkSoft,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                else -> Text(
                    stringResource(R.string.reader_word_not_found),
                    color = InkSoft
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
                // 5-6：原 .45 在浅/深最弱表面仅 2.71/3.69:1；实色后 5.56/4.63:1
                color = InkFaint
            )
        }
    }
}
