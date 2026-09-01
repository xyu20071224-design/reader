package com.linguareader.app.tts

import com.linguareader.app.data.Book
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pure-Kotlin playback state machine extracted from [TtsPlaybackService].
 *
 * Owns the sentence queue, utterance -> event dispatch and the UI-visible
 * [TtsPlaybackState]. Deliberately free of Android types so the whole
 * play/pause/skip/chapter-switch/fallback path can be driven by a fake
 * synthesizer in a plain JVM unit test.
 *
 * Everything Android-specific is injected as a plain function or type:
 *
 * - [synthesizerFactory] creates the engine (system / cloud); the
 *   engine only sees the [TtsSynthesizer] contract.
 * - [chapterLoader] produces a [TtsChapter] for a book + chapter index (the
 *   service wraps the IO-backed extractor, a test returns a fixed chapter).
 * - [isSystemEngine] isolates the remaining engine-identity decision from the
 *   queue logic.
 * - [onChapterRequest] replaces the reader chapter handshake shared flow.
 * - [onBookSwitched] lets the service clear its chapter cache on a book swap.
 * - [onProgressSave] is called with the final (book, chapter, sentence) the
 *   moment a position should be persisted (debounce + versioning stay outside).
 * - [onState] receives every [TtsPlaybackState] emission.
 *
 * The engine runs all of its coroutines on [dispatcher]; tests inject a
 * [kotlinx.coroutines.test.TestDispatcher] to control virtual time.
 */
class TtsPlaybackEngine(
    private val synthesizerFactory: (TtsSynthesizerListener) -> TtsSynthesizer,
    private val chapterLoader: suspend (Book, Int) -> TtsChapter,
    private val isSystemEngine: (TtsSynthesizer) -> Boolean,
    private val onChapterRequest: (Int) -> Unit,
    private val onBookSwitched: () -> Unit,
    private val onProgressSave: (Book, Int, Int) -> Unit,
    private val onState: (TtsPlaybackState) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val readerLoadedChapterFor: (String) -> Int? = { null },
    private val chapterReadyTimeoutMs: Long = 2_000L,
    private val progressSaveDelayMs: Long = 5_000L,
    /** Creates the system engine used when the configured engine fails
     *  mid-playback (BUG-001). Null = no fallback engine available (tests). */
    private val fallbackSynthesizerFactory: ((TtsSynthesizerListener) -> TtsSynthesizer)? = null,
    /** Maps a speaker tag ("narrator" / name / "dialogue") plus the sentence
     *  text (M3 uses it to route the narration language) to the voice to
     *  synthesize with; null keeps the engine default. */
    private val voiceForSpeaker: (String, String) -> String? = { _, _ -> null }
) {
    /**
     * 注意 [CoroutineExceptionHandler]：`SupervisorJob` 只负责「兄弟协程互不牵连」，
     * **不会**吞掉未捕获异常——没有 handler 时它会一路冒到线程默认处理器，
     * 而本引擎默认跑在 `Dispatchers.Main.immediate` 上，等于直接崩掉进程。
     * 而 [chapterLoader] 最终会走到 `Jsoup.parse(File(...))`：章节文件缺失或损坏
     * （导入残缺、外部存储被清理）就抛 IOException。那本该降级成暂停，不该是崩溃。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ -> pause() }
    )

    private var synthesizer: TtsSynthesizer? = null
    private val pendingReady = mutableListOf<() -> Unit>()
    private var book: Book? = null
    private var chapter: TtsChapter? = null
    private var chapterIndex = 0
    private var sentenceIndex = 0
    private var speechRate = 1f
    private var playing = false
    private var lastLoadedChapter: Int? = null
    private var requestedChapter: Int? = null
    private var chapterReadyDeferred: CompletableDeferred<Int>? = null
    private var consecutiveErrors = 0
    private var progressSaveJob: Job? = null
    private var preparedChapterKey: String? = null
    private var speakAttempt = 0
    private var state = TtsPlaybackState()
    /** True while playback runs on the BUG-001 fallback engine; guards
     *  re-entering [fallbackToSystemTts]. */
    private var fallbackActive = false
    /** Bumped by every control entry; async work captures the value at start
     *  and aborts when it went stale (BUG-006/009: rapid skips / stop races
     *  must not resurrect an outdated load). */
    private var navigationVersion = 0
    /** Refined (M2 LLM) speaker tags per chapter index of the current book. */
    private val refinedSpeakers = mutableMapOf<Int, List<String>>()
    private var refinedSpeakersBookId: String? = null

    /** Listener handed to [synthesizerFactory]; routes engine callbacks here. */
    val synthesizerListener: TtsSynthesizerListener = object : TtsSynthesizerListener {
        override fun onReady() { scope.launch { handleSynthesizerReady() } }
        override fun onInitFailed(status: Int) { scope.launch { handleInitFailed() } }
        override fun onStart(utteranceId: String) { scope.launch { handleUtteranceStart(utteranceId) } }
        override fun onDone(utteranceId: String) { scope.launch { handleUtteranceDone(utteranceId) } }
        override fun onError(utteranceId: String) { scope.launch { handleUtteranceError(utteranceId) } }
        override fun onCapabilitiesChanged() { scope.launch { refreshCacheCapability() } }
    }

    // Read-only views for the shell (media session / notification metadata).
    val currentBook: Book? get() = book
    val currentChapter: TtsChapter? get() = chapter
    val isPlaying: Boolean get() = playing
    val currentSpeechRate: Float get() = speechRate

    // ── Playback control ──────────────────────────────────────────────────

    fun startPlayback(newBook: Book, requestedChapter: Int, requestedSentence: Int) {
        val switchedBook = book?.id != newBook.id
        navigationVersion++
        fallbackActive = false
        synthesizer?.stop()
        if (switchedBook) {
            onBookSwitched()
            forgetSpeakerTags()
        }
        book = newBook
        chapter = null
        chapterIndex = requestedChapter.coerceIn(0, newBook.chapters.lastIndex.coerceAtLeast(0))
        sentenceIndex = requestedSentence.coerceAtLeast(0)
        preparedChapterKey = null
        lastLoadedChapter = readerLoadedChapterFor(newBook.id)
        playing = true
        consecutiveErrors = 0
        setState(
            TtsPlaybackState(
                bookId = newBook.id,
                chapterIndex = chapterIndex,
                sentenceIndex = sentenceIndex,
                sentenceCount = 0,
                currentSentence = "",
                isPlaying = true,
                speechRate = speechRate
            )
        )
        ensureSynthesizer { loadAndSpeakCurrent() }
    }

    fun startStandby(newBook: Book, requestedChapter: Int) {
        val switchedBook = book?.id != newBook.id
        navigationVersion++
        fallbackActive = false
        synthesizer?.stop()
        if (switchedBook) {
            onBookSwitched()
            forgetSpeakerTags()
        }
        book = newBook
        chapter = null
        consecutiveErrors = 0
        chapterIndex = requestedChapter.coerceIn(0, newBook.chapters.lastIndex.coerceAtLeast(0))
        sentenceIndex = if (newBook.ttsChapterIndex == chapterIndex) {
            newBook.ttsSentenceIndex.coerceAtLeast(0)
        } else {
            0
        }
        preparedChapterKey = null
        lastLoadedChapter = readerLoadedChapterFor(newBook.id)
        playing = false
        setState(
            TtsPlaybackState(
                bookId = newBook.id,
                chapterIndex = chapterIndex,
                sentenceIndex = sentenceIndex,
                sentenceCount = 0,
                currentSentence = "",
                isPlaying = false,
                speechRate = speechRate
            )
        )
    }

    fun resume() {
        if (book == null) return
        navigationVersion++
        playing = true
        updateState { it.copy(isPlaying = true) }
        ensureSynthesizer { loadAndSpeakCurrent() }
        scheduleProgressSave()
    }

    fun pause() {
        if (book == null) return
        navigationVersion++
        playing = false
        synthesizer?.stop()
        saveProgressNow()
        updateState { it.copy(isPlaying = false) }
    }

    fun next() {
        if (book == null) return
        navigationVersion++
        playing = true
        synthesizer?.stop()
        sentenceIndex++
        loadAndSpeakCurrent()
    }

    fun previous() {
        val currentBook = book ?: return
        navigationVersion++
        playing = true
        synthesizer?.stop()
        if (sentenceIndex > 0) {
            sentenceIndex--
            loadAndSpeakCurrent()
        } else if (chapterIndex > 0) {
            // BUG-007/015: walk backwards to the previous non-blank sentence.
            // Landing on a blank tail sentence used to bounce playback back to
            // the chapter the user just left, and calling speakCurrent() here
            // skipped the old chapter's prepare entirely (cloud engines then
            // waited minutes for a file nobody was generating). Reusing
            // loadAndSpeakCurrent() triggers the normal prepare for the old
            // chapter; the version check aborts if the queue moved on again.
            val version = navigationVersion
            scope.launch {
                var target = chapterIndex - 1
                while (true) {
                    val previous = chapterLoader(currentBook, target)
                    if (version != navigationVersion || book !== currentBook) return@launch
                    val lastSpoken = previous.sentences.indexOfLast { it.isNotBlank() }
                    if (lastSpoken >= 0) {
                        chapterIndex = target
                        sentenceIndex = lastSpoken
                        lastLoadedChapter = null
                        loadAndSpeakCurrent()
                        return@launch
                    }
                    if (target <= 0) {
                        // Everything before this point is blank — restart at
                        // the very beginning instead of looping forever.
                        chapterIndex = 0
                        sentenceIndex = 0
                        lastLoadedChapter = null
                        loadAndSpeakCurrent()
                        return@launch
                    }
                    target--
                }
            }
        } else {
            loadAndSpeakCurrent()
        }
    }

    fun setRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2f)
        if (clamped == speechRate) return
        navigationVersion++
        speechRate = clamped
        updateState { it.copy(speechRate = clamped) }
        if (playing && book != null && chapter != null && lastLoadedChapter == chapterIndex) {
            synthesizer?.stop()
            speakCurrent()
        }
    }

    /** Starts whole-book pre-generation (全书缓存); no-op for non-cloud engines. */
    fun cacheWholeBook() {
        if (book == null || state.isCachingBook) return
        ensureSynthesizer { cacheWholeBookNow() }
    }

    private fun cacheWholeBookNow() {
        val currentBook = book ?: return
        val preparer = synthesizer as? BookTtsPreparer ?: return
        updateState { it.copy(isCachingBook = true, cachedSentences = 0, cachedTotal = 0) }
        preparer.prepareBook(
            currentBook,
            currentBook.chapters.size,
            chapterProvider = { index -> chapterLoader(currentBook, index) },
            onProgress = { done, total ->
                scope.launch {
                    updateState {
                        it.copy(isCachingBook = true, cachedSentences = done, cachedTotal = total)
                    }
                }
            },
            onComplete = { success ->
                scope.launch {
                    updateState {
                        it.copy(
                            isCachingBook = false,
                            cachedSentences = if (success) it.cachedTotal else it.cachedSentences,
                            cachedTotal = it.cachedTotal
                        )
                    }
                }
            }
        )
    }

    fun stop() {
        navigationVersion++
        fallbackActive = false
        saveProgressNow()
        playing = false
        synthesizer?.stop()
        setState(TtsPlaybackState(speechRate = speechRate))
        book = null
        chapter = null
        chapterIndex = 0
        sentenceIndex = 0
        preparedChapterKey = null
        lastLoadedChapter = null
        requestedChapter = null
        chapterReadyDeferred?.complete(-1)
        chapterReadyDeferred = null
        pendingReady.clear()
    }

    fun reconfigure() {
        navigationVersion++
        fallbackActive = false
        val wasPlaying = playing
        synthesizer?.stop()
        synthesizer?.shutdown()
        synthesizer = null
        pendingReady.clear()
        preparedChapterKey = null
        updateState {
            it.copy(
                isCachingBook = false,
                cachedSentences = 0,
                cachedTotal = 0,
                canCacheBook = false
            )
        }
        if (wasPlaying && book != null) {
            playing = true
            ensureSynthesizer { loadAndSpeakCurrent() }
        }
    }

    fun shutdown() {
        saveProgressNow()
        synthesizer?.shutdown()
        synthesizer = null
        chapterReadyDeferred?.complete(-1)
        chapterReadyDeferred = null
        progressSaveJob?.cancel()
        progressSaveJob = null
        scope.cancel()
    }

    // ── Multi-voice ───────────────────────────────────────────────────────

    /**
     * Upgrades the loaded chapter with refined speaker tags (M2: the LLM
     * tagger answers a few seconds after playback started).
     *
     * Only the tag list of the currently loaded chapter is replaced - the
     * queue, the sentence index, the utterance ids and the highlight all stay
     * untouched, so the next sentence simply picks up its new voice. A stale
     * answer (other book/chapter, wrong length) is dropped.
     */
    fun applySpeakerTags(bookId: String, taggedChapter: Int, speakers: List<String>) {
        scope.launch {
            if (book?.id != bookId || speakers.isEmpty()) return@launch
            // Remembered per chapter, so a chapter reload (every sentence goes
            // through the loader) keeps the refined tags instead of falling back
            // to the rule layer.
            rememberSpeakerTags(bookId, taggedChapter, speakers)
            if (chapterIndex != taggedChapter) return@launch
            val loaded = chapter ?: return@launch
            if (speakers.size != loaded.sentenceCount) return@launch
            // While a sentence waits for the reader chapter handshake the
            // pending speak is tied to this chapter *instance*; swapping it
            // would drop that sentence, so let the next load pick the tags up.
            if (waitingForChapter()) return@launch
            chapter = loaded.withSpeakers(speakers)
        }
    }

    private fun rememberSpeakerTags(
        bookId: String,
        taggedChapter: Int,
        speakers: List<String>
    ) {
        if (refinedSpeakersBookId != bookId) {
            refinedSpeakers.clear()
            refinedSpeakersBookId = bookId
        }
        refinedSpeakers[taggedChapter] = speakers
    }

    /** Re-applies remembered speaker tags to a freshly loaded chapter. */
    private fun withRefinedSpeakers(loaded: TtsChapter): TtsChapter {
        if (refinedSpeakersBookId != book?.id) return loaded
        val speakers = refinedSpeakers[chapterIndex] ?: return loaded
        return loaded.withSpeakers(speakers)
    }

    private fun forgetSpeakerTags() {
        refinedSpeakers.clear()
        refinedSpeakersBookId = null
    }

    // ── Reader handshake ──────────────────────────────────────────────────

    fun onReaderChapterLoaded(bookId: String, loadedChapter: Int) {
        if (book?.id != bookId) return
        if (loadedChapter == chapterIndex) {
            lastLoadedChapter = loadedChapter
        }
        if (chapterReadyDeferred != null && loadedChapter == chapterIndex) {
            chapterReadyDeferred?.complete(loadedChapter)
        }
    }

    fun onReaderChapterSelected(bookId: String, selectedChapter: Int) {
        if (book?.id != bookId) return
        navigationVersion++
        // BUG-014: a pending chapter handshake for the old chapter must not
        // linger — waitingForChapter() would drop speaker-tag upgrades and
        // page-follow until the next speak replaces the deferred.
        chapterReadyDeferred?.complete(-1)
        chapterReadyDeferred = null
        chapterIndex = selectedChapter.coerceIn(0, book?.chapters?.lastIndex?.coerceAtLeast(0) ?: 0)
        sentenceIndex = 0
        preparedChapterKey = null
        lastLoadedChapter = null
        chapter = null
        updateState {
            it.copy(
                chapterIndex = chapterIndex,
                sentenceIndex = 0,
                sentenceCount = 0,
                currentSentence = ""
            )
        }
        scheduleProgressSave()
        if (playing) loadAndSpeakCurrent()
    }

    fun onReaderPositionChanged(bookId: String, changedChapter: Int, blockText: String) {
        val currentBook = book ?: return
        if (currentBook.id != bookId || !playing || blockText.isBlank()) return
        if (changedChapter != chapterIndex || waitingForChapter()) return
        val version = navigationVersion
        scope.launch {
            // 跟随失败无关紧要：放弃这一次即可，不要惊动正在播的队列
            // （交给 scope 的 handler 会一路 pause，那是过度反应）。
            val loadedChapter = runCatching { chapterLoader(currentBook, changedChapter) }
                .getOrNull() ?: return@launch
            if (version != navigationVersion) return@launch
            if (loadedChapter.sentenceBelongsToBlock(sentenceIndex, blockText)) return@launch
            val target = loadedChapter.firstSentenceIndexInBlock(blockText) ?: return@launch
            if (target == sentenceIndex) return@launch
            sentenceIndex = target
            if (playing) {
                synthesizer?.stop()
                speakCurrent()
            }
        }
    }

    // ── Queue / synthesis ─────────────────────────────────────────────────

    private fun finishPlayback() {
        // Keep progress on the last sentence actually spoken instead of the
        // out-of-range index used to detect the end of the queue.
        val lastSpoken = (chapter?.sentenceCount ?: 0) - 1
        if (lastSpoken >= 0) sentenceIndex = lastSpoken
        stop()
    }

    private fun ensureSynthesizer(onReady: () -> Unit) {
        val existing = synthesizer
        if (existing != null) {
            if (existing.isReady) onReady() else pendingReady += onReady
            return
        }
        val created = synthesizerFactory(synthesizerListener)
        synthesizer = created
        updateState { it.copy(canCacheBook = canCacheWholeBook(created)) }
        if (created.isReady) onReady() else pendingReady += onReady
    }

    private fun canCacheWholeBook(s: TtsSynthesizer?): Boolean =
        (s as? BookTtsPreparer)?.supportsWholeBookCache == true

    /** Re-evaluates [TtsPlaybackState.canCacheBook] after a capability probe. */
    private fun refreshCacheCapability() {
        updateState { it.copy(canCacheBook = canCacheWholeBook(synthesizer)) }
    }

    private fun loadAndSpeakCurrent() {
        val currentBook = book ?: return
        if (!playing) return
        val version = navigationVersion
        if (chapterIndex !in currentBook.chapters.indices) {
            finishPlayback()
            return
        }
        scope.launch {
            val loadedChapter = withRefinedSpeakers(chapterLoader(currentBook, chapterIndex))
            if (version != navigationVersion) return@launch
            chapter = loadedChapter
            if (sentenceIndex >= loadedChapter.sentenceCount) {
                if (chapterIndex >= currentBook.chapters.lastIndex) {
                    finishPlayback()
                    return@launch
                }
                chapterIndex++
                sentenceIndex = 0
                loadAndSpeakCurrent()
                return@launch
            }
            val preparer = synthesizer as? ChapterTtsPreparer
            val chapterKey = currentBook.id + ":" + chapterIndex
            if (preparer != null && preparedChapterKey != chapterKey) {
                preparedChapterKey = chapterKey
                preparer.prepareChapter(
                    currentBook,
                    loadedChapter,
                    onProgress = { _, _ -> },
                    onComplete = { success ->
                        scope.launch {
                            if (!success) fallbackToSystemTts()
                        }
                    }
                )
            }
            updateState {
                it.copy(
                    sentenceCount = loadedChapter.sentenceCount,
                    chapterIndex = chapterIndex,
                    sentenceIndex = sentenceIndex
                )
            }
            speakCurrent()
        }
    }

    private fun speakCurrent() {
        val currentChapter = chapter ?: return
        if (!playing) return
        if (sentenceIndex !in currentChapter.sentences.indices) {
            loadAndSpeakCurrent()
            return
        }
        val text = currentChapter.sentences[sentenceIndex]
        if (text.isBlank()) {
            sentenceIndex++
            loadAndSpeakCurrent()
            return
        }
        if (lastLoadedChapter != chapterIndex) {
            requestedChapter = chapterIndex
            onChapterRequest(chapterIndex)
            val deferred = CompletableDeferred<Int>()
            chapterReadyDeferred = deferred
            val version = navigationVersion
            scope.launch {
                val loaded = withTimeoutOrNull(chapterReadyTimeoutMs) { deferred.await() }
                if (chapterReadyDeferred !== deferred) return@launch
                if (version != navigationVersion) {
                    chapterReadyDeferred = null
                    return@launch
                }
                if (chapter !== currentChapter) {
                    // BUG-014: the queue moved to another chapter while this
                    // handshake waited — complete and drop the stale deferred
                    // instead of leaving waitingForChapter() stuck true.
                    chapterReadyDeferred?.complete(-1)
                    chapterReadyDeferred = null
                    return@launch
                }
                chapterReadyDeferred = null
                when {
                    loaded == chapterIndex && playing -> speakNow(text)
                    loaded != chapterIndex && loaded != null && playing -> {
                        lastLoadedChapter = loaded
                        loadAndSpeakCurrent()
                    }
                    else -> {
                        lastLoadedChapter = chapterIndex
                        if (playing) speakNow(text)
                    }
                }
            }
        } else {
            speakNow(text)
        }
    }

    private fun speakNow(text: String) {
        val currentBook = book ?: return
        val attempt = ++speakAttempt
        val utteranceId = utteranceIdFor(chapterIndex, sentenceIndex, attempt)
        val location = chapter?.sentenceLocation(sentenceIndex)
        updateState {
            it.copy(
                bookId = currentBook.id,
                chapterIndex = chapterIndex,
                sentenceIndex = sentenceIndex,
                sentenceCount = chapter?.sentenceCount ?: it.sentenceCount,
                currentSentence = text,
                highlightBlockIndex = location?.first ?: -1,
                highlightOffset = location?.second ?: 0,
                highlightLength = location?.third ?: 0,
                isPlaying = true
            )
        }
        scheduleProgressSave()
        val voice = chapter?.speakerAt(sentenceIndex)?.let { voiceForSpeaker(it, text) }
        synthesizer?.speak(text, speechRate, utteranceId, voice)
    }

    private fun fallbackToSystemTts() {
        val current = synthesizer ?: return
        if (fallbackActive || isSystemEngine(current)) return
        // BUG-001: the fallback must create a *system* engine. Rebuilding via
        // the settings factory just recreated the same broken cloud engine in
        // an endless (and billable) loop.
        val factory = fallbackSynthesizerFactory ?: return
        synthesizer?.stop()
        synthesizer?.shutdown()
        synthesizer = null
        pendingReady.clear()
        preparedChapterKey = null
        fallbackActive = true
        val created = factory(synthesizerListener)
        synthesizer = created
        updateState {
            it.copy(
                canCacheBook = canCacheWholeBook(created)
            )
        }
        if (created.isReady) loadAndSpeakCurrent() else pendingReady += { loadAndSpeakCurrent() }
    }

    /**
     * BUG-002: an engine that failed to initialize used to just pause() while
     * the dead instance kept occupying `synthesizer`, so every later resume()
     * queued its callback behind a ready that never came — playback was stuck
     * until the service restarted. Non-system engines fall back (BUG-001);
     * a system/fallback engine that itself fails is dropped so the next
     * resume() rebuilds a fresh one.
     */
    private fun handleInitFailed() {
        val current = synthesizer ?: return
        if (fallbackSynthesizerFactory != null && !fallbackActive && !isSystemEngine(current)) {
            fallbackToSystemTts()
            return
        }
        synthesizer?.stop()
        synthesizer?.shutdown()
        synthesizer = null
        pendingReady.clear()
        if (playing) pause()
    }

    private fun handleSynthesizerReady() {
        updateState {
            it.copy(canCacheBook = canCacheWholeBook(synthesizer))
        }
        pendingReady.toList().forEach { it() }
        pendingReady.clear()
    }

    private fun handleUtteranceStart(utteranceId: String) {
        if (!playing) return
        if (utteranceId == utteranceIdFor(chapterIndex, sentenceIndex, speakAttempt)) {
            // Only a successful start clears the error streak.
            consecutiveErrors = 0
            updateState { it.copy(isPlaying = true) }
        }
    }

    private fun handleUtteranceDone(utteranceId: String) {
        if (utteranceId != utteranceIdFor(chapterIndex, sentenceIndex, speakAttempt) || !playing) return
        sentenceIndex++
        loadAndSpeakCurrent()
    }

    private fun handleUtteranceError(utteranceId: String) {
        if (utteranceId != utteranceIdFor(chapterIndex, sentenceIndex, speakAttempt) || !playing) return
        consecutiveErrors++
        if (consecutiveErrors >= 25) {
            pause()
            return
        }
        sentenceIndex++
        loadAndSpeakCurrent()
    }

    private fun waitingForChapter(): Boolean = chapterReadyDeferred != null

    // ── Progress ──────────────────────────────────────────────────────────

    private fun scheduleProgressSave() {
        progressSaveJob?.cancel()
        progressSaveJob = scope.launch {
            delay(progressSaveDelayMs)
            saveProgressNow()
        }
    }

    private fun saveProgressNow() {
        val currentBook = book ?: return
        val chapter = chapterIndex
        val sentence = sentenceIndex
        progressSaveJob?.cancel()
        progressSaveJob = null
        onProgressSave(currentBook, chapter, sentence)
    }

    // ── State / helpers ───────────────────────────────────────────────────

    private fun setState(newState: TtsPlaybackState) {
        state = newState
        onState(state)
    }

    private fun updateState(transform: (TtsPlaybackState) -> TtsPlaybackState) {
        state = transform(state)
        onState(state)
    }

    private fun utteranceIdFor(chapter: Int, sentence: Int, attempt: Int): String =
        (book?.id.orEmpty()) + ":" + chapter + ":" + sentence + ":" + attempt
}

