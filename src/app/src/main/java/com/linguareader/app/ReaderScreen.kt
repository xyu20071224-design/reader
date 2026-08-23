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
import androidx.compose.runtime.mutableIntStateOf
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
import com.linguareader.app.data.DictionaryLookupResult
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
import com.linguareader.app.reader.ReaderController
import com.linguareader.app.reader.ReaderScripts
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
    var chapterIndex by rememberSaveable(book.id) {
        mutableIntStateOf(book.chapterIndex.coerceIn(0, book.chapters.lastIndex))
    }
    var initialPage by rememberSaveable(book.id) { mutableIntStateOf(book.pageIndex) }
    var currentPage by remember { mutableIntStateOf(initialPage) }
    var pageCount by remember { mutableIntStateOf(1) }
    var scrollMode by rememberSaveable(book.id) { mutableStateOf(false) }
    var scrollRatio by rememberSaveable(book.id) { mutableFloatStateOf(0f) }
    var scrollPageCount by rememberSaveable(book.id) { mutableIntStateOf(1) }
    var pendingPage by remember { mutableIntStateOf(initialPage) }
    var pendingCount by remember { mutableIntStateOf(1) }
    var needsSave by remember { mutableStateOf(false) }
    var toolbarVisible by remember { mutableStateOf(true) }
    var choosingStart by rememberSaveable { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showListeningSettings by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var lookup by rememberSaveable(stateSaver = WordLookupSaver) { mutableStateOf<WordLookup?>(null) }
    var dictionaryResult by remember { mutableStateOf<DictionaryLookupResult?>(null) }
    var dictionaryLoading by remember { mutableStateOf(false) }
    var aiResult by remember { mutableStateOf<AiLookupResult?>(null) }
    var aiLoading by remember { mutableStateOf(false) }
    var aiFailed by remember { mutableStateOf(false) }
    var sentenceTranslation by remember { mutableStateOf<SentenceTranslationResult?>(null) }
    var translation by remember { mutableStateOf<TranslationLookupResult?>(null) }
    var translationLoading by remember { mutableStateOf(false) }
    var sentenceTranslationError by remember { mutableStateOf<String?>(null) }
    var sentenceTranslationLoading by remember { mutableStateOf(false) }
    // 同一句重复点词不再重复出网（也不重复计费）；换书即失效，超过 64 句整体丢弃。
    val sentenceTranslationCache = remember(book.id) {
        mutableMapOf<String, SentenceTranslationResult>()
    }
    var showingRelatedPhrase by remember { mutableStateOf(false) }
    // 复习卡组旋转屏后按 id 从 ViewModel 的 savedWords 恢复（SavedWord 本身不可 Bundle 化）。
    var reviewDeckIds by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val reviewDeck = remember(savedWords, reviewDeckIds) {
        reviewDeckIds?.let { ids -> savedWords.filter { it.id in ids } }?.takeIf { it.isNotEmpty() }
    }
    var showReviewSettings by remember { mutableStateOf(false) }
    var showReviewPrompt by remember { mutableStateOf(false) }
    var pendingClose by remember { mutableStateOf(false) }
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

    fun progressOf(chapter: Int, page: Int, count: Int): Float {
        val chapterProgress = if (count <= 1) 0f else page.toFloat() / (count - 1)
        return (chapter + chapterProgress) / book.chapters.size.toFloat()
    }

    fun flushProgressAsync() {
        if (!needsSave) return
        needsSave = false
        val chapter = chapterIndex
        val page = pendingPage
        val count = pendingCount
        scope.launch {
            viewModel.saveProgress(book, chapter, page, progressOf(chapter, page, count))
        }
    }

    fun performClose() {
        val chapter = chapterIndex
        val page = pendingPage
        val count = pendingCount
        val mustSave = needsSave
        needsSave = false
        scope.launch {
            if (mustSave) {
                viewModel.saveProgress(book, chapter, page, progressOf(chapter, page, count))
            }
            onClose()
        }
    }

    fun closeWithFlush() {
        if (reminders.pausePrompt && dueWords.isNotEmpty() && !showReviewPrompt) {
            showReviewPrompt = true
            pendingClose = true
            return
        }
        pendingClose = false
        showReviewPrompt = false
        performClose()
    }

    fun selectChapter(
        index: Int,
        fromEnd: Boolean = false,
        fromTts: Boolean = false,
        keepScrollMode: Boolean = false
    ) {
        if (index !in book.chapters.indices) return
        flushProgressAsync()
        val stayScrolled = keepScrollMode && scrollMode
        chapterIndex = index
        initialPage = if (fromEnd) Int.MAX_VALUE else 0
        currentPage = 0
        pageCount = 1
        scrollMode = stayScrolled
        scrollRatio = if (stayScrolled && fromEnd) 1f else 0f
        scrollPageCount = 1
        pendingPage = 0
        pendingCount = 1
        needsSave = false
        if (ttsForThisBook && !fromTts) {
            TtsPlaybackController.onReaderChapterSelected(book.id, index)
        }
    }

    fun changeChapter(direction: Int, keepScrollMode: Boolean = false) {
        val next = chapterIndex + direction
        if (next !in book.chapters.indices) return
        selectChapter(next, fromEnd = direction < 0, keepScrollMode = keepScrollMode)
    }

    fun reportTtsPositionDelayed() {
        ttsPositionReportJob?.cancel()
        ttsPositionReportJob = scope.launch {
            delay(350)
            controller.firstVisibleBlock { block ->
                if (block != null) {
                    TtsPlaybackController.onReaderPositionChanged(book.id, chapterIndex, block)
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
                choosingStart = true
                controller.setChoosingStart(true)
            }
            return
        }
        // Opening listening never auto-plays: enter standby and let the user
        // tap a word/sentence to choose the start point.
        TtsPlaybackController.startStandby(context, book, chapterIndex)
        choosingStart = true
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
            if (requested != chapterIndex) {
                selectChapter(requested, fromTts = true)
            }
        }
    }

    LaunchedEffect(ttsForThisBook) {
        if (!ttsForThisBook) {
            choosingStart = false
            controller.setChoosingStart(false)
            controller.clearHighlight()
        }
    }

    LaunchedEffect(
        ttsState.highlightBlockIndex,
        ttsState.highlightOffset,
        ttsState.chapterIndex,
        chapterIndex
    ) {
        if (ttsForThisBook && ttsState.chapterIndex == chapterIndex) {
            highlightCurrentTts(controller, ttsState)
        }
    }

    BackHandler {
        when {
            lookup != null -> lookup = null
            showSettings -> showSettings = false
            showContents -> showContents = false
            showPageJump -> showPageJump = false
            else -> closeWithFlush()
        }
    }

    LaunchedEffect(lookup) {
        val request = lookup ?: return@LaunchedEffect
        dictionaryLoading = true
        aiResult = null
        aiLoading = false
        aiFailed = false
        sentenceTranslation = sentenceTranslationCache[request.sentence.trim()]
        sentenceTranslationError = null
        sentenceTranslationLoading = false
        translation = null
        translationLoading = book.hasTranslation
        dictionaryResult = viewModel.lookup(request)
        showingRelatedPhrase = false
        dictionaryLoading = false
        // 译本对照是纯本地查询，先于联网 AI 出结果。
        if (book.hasTranslation) {
            translation = viewModel.translationLookup(book, chapterIndex, request)
            translationLoading = false
        }
        if (aiSettings.enabled) {
            aiLoading = true
            aiResult = runCatching {
                viewModel.aiLookup(book, request, dictionaryResult?.entry)
            }.onFailure { aiFailed = true }.getOrNull()
            aiLoading = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(android.graphics.Color.parseColor(preferences.theme.background)))
    ) {
        key(chapterIndex) {
            EpubPage(
                chapterFile = File(book.extractedDir, book.chapters[chapterIndex].relativePath),
                initialPage = initialPage,
                initialScrollMode = scrollMode,
                initialScrollRatio = scrollRatio,
                initialScrollPageCount = scrollPageCount.coerceAtLeast(1),
                preferences = preferences,
                savedWords = if (reminders.contextHighlight) savedWords.map { it.headword } else emptyList(),
                chromeTopPx = chromeTopPx.roundToInt(),
                chromeBottomPx = chromeBottomPx.roundToInt(),
                controller = controller,
                modifier = Modifier.fillMaxSize(),
                onReady = { page, count ->
                    initialPage = page
                    currentPage = page
                    pageCount = count
                    pendingPage = page
                    pendingCount = count
                    needsSave = true
                    TtsPlaybackController.onReaderChapterLoaded(book.id, chapterIndex)
                    controller.setChoosingStart(choosingStart)
                    if (ttsForThisBook && ttsState.chapterIndex == chapterIndex) {
                        highlightCurrentTts(controller, ttsState)
                    }
                },
                onPageChanged = { page, count ->
                    initialPage = page
                    currentPage = page
                    pageCount = count
                    pendingPage = page
                    pendingCount = count
                    needsSave = true
                    // A manual page turn scrolls the highlight away with the
                    // text; clear it here so no stale block lingers on the new
                    // page before the next sentence re-applies it.
                    if (ttsForThisBook) controller.clearHighlight()
                    if (ttsForThisBook && ttsState.isPlaying) {
                        reportTtsPositionDelayed()
                    }
                },
                onChapterRequested = { direction ->
                    if (reminders.pausePrompt && dueWords.isNotEmpty() && !showReviewPrompt) {
                        showReviewPrompt = true
                    }
                    changeChapter(direction, keepScrollMode = scrollMode)
                },
                onWord = {
                    lookup = it
                    dictionaryResult = null
                    showingRelatedPhrase = false
                    toolbarVisible = false
                },
                onSentenceTapped = { block, offset ->
                    if (choosingStart) {
                        // Only the first tap after entering choose mode is
                        // consumed as the start point.
                        choosingStart = false
                        controller.setChoosingStart(false)
                        TtsPlaybackController.startFromBlockOffset(
                            context,
                            book,
                            chapterIndex,
                            block,
                            offset
                        )
                    }
                },
                onScrollModeChanged = { active -> scrollMode = active },
                onScrollProgress = { ratio, page, count ->
                    scrollRatio = ratio
                    scrollPageCount = count
                    currentPage = page
                    pageCount = count
                    pendingPage = page
                    pendingCount = count
                    needsSave = true
                },
                onToolbarRequested = { toolbarVisible = !toolbarVisible }
            )
        }

        AnimatedVisibility(
            visible = toolbarVisible,
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
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = ::closeWithFlush) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text(
                        book.chapters[chapterIndex].title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium
                    )
                    if (reminders.toolbarBadge && dueWords.isNotEmpty()) {
                        TextButton(onClick = { reviewDeckIds = dueWords.take(reviewPace.sessionMaxWords).map { it.id } }) {
                            Text("复习 ${dueWords.size}", color = Accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    TextButton(onClick = { showContents = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("目录")
                    }
                    TextButton(onClick = ::startOrToggleListening) {
                        Icon(
                            Icons.Filled.Headphones,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (ttsForThisBook) Accent else InkSoft
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (ttsForThisBook) "听书中" else "听书", color = Ink)
                    }
                    TextButton(onClick = { showSettings = true }) {
                        Text("Aa", fontWeight = FontWeight.Bold)
                    }
                }
                LinearProgressIndicator(
                    progress = { progressOf(chapterIndex, currentPage, pageCount) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Accent,
                    trackColor = Accent.copy(alpha = .12f)
                )
            }
        }

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
                modifier = Modifier.semantics { contentDescription = "上一页" }
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = Color(android.graphics.Color.parseColor(preferences.theme.foreground))
                )
            }
            if (scrollMode) {
                Text(
                    "${chapterIndex + 1}/${book.chapters.size} · 章节进度 ${(scrollRatio * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(android.graphics.Color.parseColor(preferences.theme.foreground)).copy(alpha = .6f),
                    modifier = Modifier
                        .semantics { contentDescription = "页码指示" }
                        .clickable { showPageJump = true }
                )
                TextButton(onClick = controller::exitScrollMode) {
                    Text(
                        "分页",
                        color = Color(android.graphics.Color.parseColor(preferences.theme.foreground))
                    )
                }
            } else {
                Text(
                    "${chapterIndex + 1}/${book.chapters.size} · ${currentPage + 1}/$pageCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(android.graphics.Color.parseColor(preferences.theme.foreground)).copy(alpha = .6f),
                    modifier = Modifier
                        .semantics { contentDescription = "页码指示" }
                        .clickable { showPageJump = true }
                )
            }
            IconButton(
                onClick = controller::nextPage,
                modifier = Modifier.semantics { contentDescription = "下一页" }
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
                modifier = Modifier.padding(bottom = 62.dp),
                onToggle = { TtsPlaybackController.toggle(context) },
                onPrevious = { TtsPlaybackController.previous(context) },
                onNext = { TtsPlaybackController.next(context) },
                onStop = { TtsPlaybackController.stop(context) },
                onRateChange = { TtsPlaybackController.setRate(context, it) },
                onCacheBook = { TtsPlaybackController.cacheWholeBook(context) },
                choosingStart = choosingStart,
                onChooseStart = {
                    choosingStart = !choosingStart
                    controller.setChoosingStart(choosingStart)
                }
            )
        }

        AnimatedVisibility(
            visible = showReviewPrompt && dueWords.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(Modifier.padding(bottom = 62.dp)) {
                ReviewPromptBanner(
                    count = dueWords.size,
                    dwellMillis = reviewPace.dwellMillis,
                    onStart = {
                        showReviewPrompt = false
                        pendingClose = false
                        reviewDeckIds = dueWords.take(reviewPace.sessionMaxWords).map { it.id }
                    },
                    onDismiss = {
                        showReviewPrompt = false
                        if (pendingClose) {
                            pendingClose = false
                            performClose()
                        }
                    }
                )
            }
        }
    }

    if (showContents) {
        ContentsSheet(
            book = book,
            currentChapter = chapterIndex,
            onSelect = {
                selectChapter(it)
                showContents = false
            },
            onDismiss = { showContents = false }
        )
    }

    if (showSettings) {
        SettingsSheet(
            preferences = preferences,
            reviewPace = reviewPace,
            onOpenReviewSettings = {
                showSettings = false
                showReviewSettings = true
            },
            onOpenListeningSettings = {
                showSettings = false
                showListeningSettings = true
            },
            onChange = ::persistPreferences,
            onDismiss = { showSettings = false }
        )
    }

    if (showListeningSettings) {
        // Multi-voice M4: the reader knows the book, so its character list and
        // voice assignments can be managed right here.
        ListeningSettingsSheet(onDismiss = { showListeningSettings = false }, book = book)
    }

    if (showPageJump) {
        PageJumpDialog(
            currentPage = currentPage,
            pageCount = pageCount,
            onJump = controller::jumpToPage,
            onDismiss = { showPageJump = false }
        )
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
            onReview = viewModel::reviewWord,
            onSpeak = onSpeak,
            onDismiss = { reviewDeckIds = null }
        )
    }

    lookup?.let { currentLookup ->
        val displayedEntry = if (showingRelatedPhrase) dictionaryResult?.relatedPhrase
        else dictionaryResult?.entry
        val savedId = (displayedEntry?.matchedPhrase ?: displayedEntry?.headword)
            ?.lowercase(Locale.ROOT)
        LookupSheet(
            lookup = currentLookup,
            entry = displayedEntry,
            relatedPhrase = dictionaryResult?.relatedPhrase,
            loading = dictionaryLoading,
            aiContext = aiResult,
            aiLoading = aiLoading,
            aiFailed = aiFailed,
            sentenceTranslationReady = SentenceTranslatorFactory.isConfigured(aiSettings),
            hasTranslation = book.hasTranslation,
            translation = translation,
            translationLoading = translationLoading,
            sentenceTranslation = sentenceTranslation,
            sentenceTranslationError = sentenceTranslationError,
            sentenceTranslationLoading = sentenceTranslationLoading,
            isSaved = savedId != null && savedWords.any { word -> word.id == savedId },
            isPhraseView = showingRelatedPhrase,
            showReviewEntry = reminders.contextHighlight,
            onReviewSaved = {
                val word = savedId?.let { id -> savedWords.firstOrNull { w -> w.id == id } }
                if (word != null) {
                    lookup = null
                    reviewDeckIds = listOf(word.id)
                }
            },
            onShowPhrase = { showingRelatedPhrase = true },
            onShowWord = { showingRelatedPhrase = false },
            onSpeak = {
                onSpeak(displayedEntry?.matchedPhrase ?: displayedEntry?.headword ?: currentLookup.word)
            },
            onToggleSave = {
                val entry = displayedEntry ?: return@LookupSheet
                if (savedId != null && savedWords.any { word -> word.id == savedId }) {
                    viewModel.removeSavedWord(savedId)
                } else {
                    viewModel.saveWord(
                        book,
                        book.chapters[chapterIndex].title,
                        currentLookup,
                        entry,
                        aiResult
                    )
                }
            },
            onTranslateSentence = {
                val key = currentLookup.sentence.trim()
                val cached = sentenceTranslationCache[key]
                when {
                    sentenceTranslationLoading -> Unit
                    cached != null -> {
                        sentenceTranslation = cached
                        sentenceTranslationError = null
                    }
                    else -> scope.launch {
                        sentenceTranslationLoading = true
                        sentenceTranslationError = null
                        // 成功与失败都必须留下可见结果：静默失败是历史缺陷。
                        runCatching { viewModel.translateSentence(book, currentLookup.sentence) }
                            .onSuccess { result ->
                                sentenceTranslation = result
                                if (sentenceTranslationCache.size >= 64) {
                                    sentenceTranslationCache.clear()
                                }
                                sentenceTranslationCache[key] = result
                            }
                            .onFailure {
                                sentenceTranslation = null
                                sentenceTranslationError =
                                    context.getString(R.string.translate_sentence_failed)
                            }
                        sentenceTranslationLoading = false
                    }
                }
            },
            onDismiss = {
                lookup = null
                showingRelatedPhrase = false
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Text(
            "目录",
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
                    Text(
                        "${index + 1}",
                        color = if (index == currentChapter) Accent else Ink.copy(alpha = .45f),
                        modifier = Modifier.width(36.dp)
                    )
                    Text(
                        chapter.title,
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

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text("阅读设置", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            Text("字号 ${fontSize.roundToInt()}%")
            Slider(
                value = fontSize,
                onValueChange = {
                    fontSize = it
                    onChange(preferences.copy(fontPercent = it.roundToInt()))
                },
                valueRange = 80f..150f,
                steps = 6
            )
            Text("行距 ${"%.1f".format(lineHeight)}")
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
            Text("主题", color = InkSoft)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.Top
            ) {
                ReaderTheme.entries.forEach { theme ->
                    val selected = theme == preferences.theme
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(theme.background)))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) Accent else Ink.copy(alpha = .18f),
                                    shape = CircleShape
                                )
                                .clickable { onChange(preferences.copy(theme = theme)) },
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
            Text("字体", color = InkSoft)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReaderFont.entries.forEach { font ->
                    val fontLabel = stringResource(font.labelRes)
                    TextButton(onClick = { onChange(preferences.copy(fontFamily = font)) }) {
                        Text(if (font == preferences.fontFamily) "● $fontLabel" else fontLabel)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Ink.copy(alpha = .1f))
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "复习节奏",
                    modifier = Modifier.weight(1f),
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onOpenReviewSettings) {
                    Text("${stringResource(reviewPace.labelRes)} ›", color = Accent)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "听书设置",
                    modifier = Modifier.weight(1f),
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onOpenListeningSettings) {
                    Text("›", color = Accent)
                }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到页码") },
        text = {
            Column {
                Text(
                    "当前章节共 $pageCount 页，可输入 1–$pageCount。",
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
                    label = { Text("页码") },
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
                        error = "请输入 1–$pageCount 之间的页码"
                    } else {
                        onJump(target)
                        onDismiss()
                    }
                }
            ) { Text("跳转") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
    aiContext: AiLookupResult?,
    aiLoading: Boolean,
    aiFailed: Boolean,
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
    onReviewSaved: () -> Unit,
    onShowPhrase: () -> Unit,
    onShowWord: () -> Unit,
    onSpeak: () -> Unit,
    onToggleSave: () -> Unit,
    onTranslateSentence: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
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
                    if (isPhraseView) "短语释义：${entry.matchedPhrase}"
                    else "短语优先：${entry.matchedPhrase}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Accent
                )
            } else if (entry != null && !entry.headword.equals(lookup.word, ignoreCase = true)) {
                Text(
                    "原形：${entry.headword}",
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
                        "相关短语：${relatedPhrase.matchedPhrase ?: relatedPhrase.headword}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Ink.copy(alpha = .72f),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onShowPhrase) { Text("查看") }
                }
            }
            if (isPhraseView) {
                TextButton(onClick = onShowWord) { Text("返回单词释义") }
            }
            if (entry != null && entry.inferredPartOfSpeech != PartOfSpeech.UNKNOWN) {
                Text(
                    "本句推断：${stringResource(entry.inferredPartOfSpeech.labelRes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.copy(alpha = .56f)
                )
            }
            if (entry != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onSpeak) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("朗读")
                    }
                    if (isSaved && showReviewEntry) {
                        TextButton(onClick = onReviewSaved) {
                            Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("复习")
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
                        Text(if (isSaved) "移出生词本" else "加入生词本")
                    }
                }
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
                        "正在结合本书语境…",
                        style = MaterialTheme.typography.labelMedium,
                        color = InkSoft
                    )
                }
            }
            if (!aiLoading && aiFailed && aiContext == null) {
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
                    "本书语境释义（${aiContext.source}）",
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
                        "短语：$it",
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
                                if (sense.contextPreferred && index == 0) "本句优先" else "•",
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
                        Text("该词条暂无中文释义。", color = Ink.copy(alpha = .62f))
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
                else -> Text("本地词典中暂未收录该词。", color = Ink.copy(alpha = .62f))
            }
            Spacer(Modifier.height(18.dp))
            Text("当前语境", style = MaterialTheme.typography.labelLarge, color = Accent)
            Spacer(Modifier.height(5.dp))
            Text(
                lookup.sentence.ifBlank { lookup.paragraph },
                fontFamily = FontFamily.Serif,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "释义来源：ECDICT（MIT License）。短语匹配、词形还原与义项排序均在设备本地完成。",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.copy(alpha = .45f)
            )
        }
    }
}
