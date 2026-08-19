package com.linguareader.app.tts

import com.linguareader.app.data.Book
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
 * - [synthesizerFactory] creates the configured engine (system / Piper /
 *   cloud); the engine only sees the [TtsSynthesizer] contract.
 * - [fallbackSynthesizerFactory] creates the system engine used after a cloud
 *   chapter-preparation failure. It is a separate factory because the normal
 *   factory follows user settings and would otherwise create the same failing
 *   cloud engine again.
 * - [chapterLoader] produces a [TtsChapter] for a book + chapter index (the
 *   service wraps the IO-backed extractor, a test returns a fixed chapter).
 * - [engineLabelForSettings] resolves the label shown before a synthesizer has
 *   been created (from user settings); once created, the engine reads
 *   `synthesizer.engineLabel` directly — never the concrete type.
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
    private val fallbackSynthesizerFactory: (TtsSynthesizerListener) -> TtsSynthesizer,
    private val chapterLoader: suspend (Book, Int) -> TtsChapter,
    private val engineLabelForSettings: () -> String,
    private val onChapterRequest: (Int) -> Unit,
    private val onBookSwitched: () -> Unit,
    private val onProgressSave: (Book, Int, Int) -> Unit,
    private val onState: (TtsPlaybackState) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val readerLoadedChapterFor: (String) -> Int? = { null },
    private val chapterReadyTimeoutMs: Long = 2_000L,
    private val progressSaveDelayMs: Long = 5_000L,
    private val fallbackEngineLabel: String = "系统语音（云 TTS 失败）"
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

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
    private var navigationVersion = 0
    private var engineLabel = "系统语音"
    private var fallbackActive = false
    private var state = TtsPlaybackState()

    /** Listener handed to [synthesizerFactory]; folds engine callbacks into the
     *  single [handleTtsEvent] entry so new event kinds (e.g. word boundaries)
     *  never require touching the queue logic again. */
    val synthesizerListener: TtsSynthesizerListener = object : TtsSynthesizerListener {
        override fun onReady() { scope.launch { handleSynthesizerReady() } }
        override fun onInitFailed(status: Int) { scope.launch { handleInitFailed() } }
        override fun onStart(utteranceId: String) {
            markFor(utteranceId)?.let { mark -> scope.launch { handleTtsEvent(TtsEvent.Boundary(mark)) } }
        }
        override fun onDone(utteranceId: String) {
            markFor(utteranceId)?.let { mark -> scope.launch { handleTtsEvent(TtsEvent.End(mark)) } }
        }
        override fun onError(utteranceId: String) {
            markFor(utteranceId)?.let { mark -> scope.launch { handleTtsEvent(TtsEvent.Error(mark, null)) } }
        }
        override fun onWordBoundary(utteranceId: String, startChar: Int, endChar: Int) {
            markFor(utteranceId)?.let { mark ->
                scope.launch { handleTtsEvent(TtsEvent.WordBoundary(mark, startChar, endChar)) }
            }
        }
    }

    // Read-only views for the shell (media session / notification metadata).
    val currentBook: Book? get() = book
    val currentChapter: TtsChapter? get() = chapter
    val isPlaying: Boolean get() = playing
    val currentSpeechRate: Float get() = speechRate

    // ── Playback control ──────────────────────────────────────────────────

    fun startPlayback(newBook: Book, requestedChapter: Int, requestedSentence: Int) {
        navigationVersion++
        val switchedBook = book?.id != newBook.id
        synthesizer?.stop()
        if (switchedBook) onBookSwitched()
        book = newBook
        chapter = null
        chapterIndex = requestedChapter.coerceIn(0, newBook.chapters.lastIndex.coerceAtLeast(0))
        sentenceIndex = requestedSentence.coerceAtLeast(0)
        preparedChapterKey = null
        lastLoadedChapter = readerLoadedChapterFor(newBook.id)
        playing = true
        consecutiveErrors = 0
        fallbackActive = false
        engineLabel = engineLabelForSettings()
        setState(
            TtsPlaybackState(
                bookId = newBook.id,
                bookTitle = newBook.title,
                chapterIndex = chapterIndex,
                sentenceIndex = sentenceIndex,
                sentenceCount = 0,
                currentSentence = "",
                isPlaying = true,
                speechRate = speechRate,
                engineLabel = engineLabel
            )
        )
        ensureSynthesizer { loadAndSpeakCurrent() }
    }

    fun startStandby(newBook: Book, requestedChapter: Int) {
        navigationVersion++
        val switchedBook = book?.id != newBook.id
        synthesizer?.stop()
        if (switchedBook) onBookSwitched()
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
        fallbackActive = false
        engineLabel = engineLabelForSettings()
        setState(
            TtsPlaybackState(
                bookId = newBook.id,
                bookTitle = newBook.title,
                chapterIndex = chapterIndex,
                sentenceIndex = sentenceIndex,
                sentenceCount = 0,
                currentSentence = "",
                isPlaying = false,
                speechRate = speechRate,
                engineLabel = engineLabel
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
        val version = navigationVersion
        playing = true
        synthesizer?.stop()
        if (sentenceIndex > 0) {
            sentenceIndex--
            loadAndSpeakCurrent()
        } else if (chapterIndex > 0) {
            // Moving to an earlier chapter: load backwards until a non-blank
            // sentence is found so a blank chapter tail never bounces back to
            // the current chapter (BUG-007). The load is version-checked so a
            // slower old request cannot overwrite a newer navigation (BUG-006).
            var target = chapterIndex - 1
            chapterIndex = target
            scope.launch {
                while (target >= 0) {
                    if (version != navigationVersion || book !== currentBook || chapterIndex != target) return@launch
                    val previous = chapterLoader(currentBook, target)
                    if (version != navigationVersion || book !== currentBook || chapterIndex != target) return@launch
                    val nonBlankIndex = previous.sentences.indexOfLast { it.isNotBlank() }
                    if (nonBlankIndex >= 0) {
                        chapter = previous
                        sentenceIndex = nonBlankIndex
                        loadAndSpeakCurrent()
                        return@launch
                    }
                    if (target == 0) {
                        // Every earlier chapter is blank; start from the first
                        // chapter instead of bouncing forward.
                        chapter = previous
                        sentenceIndex = 0
                        loadAndSpeakCurrent()
                        return@launch
                    }
                    target--
                    chapterIndex = target
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
        val preparer = synthesizer?.bookPreparer ?: return
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
        saveProgressNow()
        playing = false
        fallbackActive = false
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

    private fun handleInitFailed() {
        val current = synthesizer ?: return
        if (!current.isSystemEngine && !fallbackActive) {
            fallbackToSystemTts()
            return
        }
        // The system engine (or the fallback engine itself) failed to start:
        // drop it entirely so pendingReady can never pin playback forever.
        synthesizer?.stop()
        synthesizer?.shutdown()
        synthesizer = null
        pendingReady.clear()
        fallbackActive = false
        if (playing) pause()
    }

    fun reconfigure() {
        navigationVersion++
        val wasPlaying = playing
        synthesizer?.stop()
        synthesizer?.shutdown()
        synthesizer = null
        pendingReady.clear()
        preparedChapterKey = null
        fallbackActive = false
        engineLabel = engineLabelForSettings()
        updateState {
            it.copy(
                engineLabel = engineLabel,
                isPreparing = false,
                preparedCount = 0,
                preparedTotal = 0,
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
        chapterIndex = selectedChapter.coerceIn(0, book?.chapters?.lastIndex?.coerceAtLeast(0) ?: 0)
        sentenceIndex = 0
        preparedChapterKey = null
        lastLoadedChapter = null
        chapter = null
        chapterReadyDeferred?.complete(-1)
        chapterReadyDeferred = null
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
            val loadedChapter = chapterLoader(currentBook, changedChapter)
            if (version != navigationVersion) return@launch
            if (loadedChapter.sentenceBelongsToBlock(sentenceIndex, blockText)) return@launch
            val target = loadedChapter.firstSentenceIndexInBlock(blockText) ?: return@launch
            if (target == sentenceIndex) return@launch
            sentenceIndex = target
            if (playing && version == navigationVersion) {
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
        updateState { it.copy(canCacheBook = created.bookPreparer != null) }
        if (created.isReady) onReady() else pendingReady += onReady
    }

    private fun loadAndSpeakCurrent() {
        val currentBook = book ?: return
        if (!playing) return
        if (chapterIndex !in currentBook.chapters.indices) {
            finishPlayback()
            return
        }
        val version = navigationVersion
        scope.launch {
            if (version != navigationVersion || !playing) return@launch
            val loadedChapter = chapterLoader(currentBook, chapterIndex)
            if (version != navigationVersion || !playing) return@launch
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
            val preparer = synthesizer?.chapterPreparer
            val chapterKey = currentBook.id + ":" + chapterIndex
            if (preparer != null && preparedChapterKey != chapterKey) {
                preparedChapterKey = chapterKey
                updateState {
                    it.copy(
                        isPreparing = true,
                        preparedCount = 0,
                        preparedTotal = loadedChapter.sentenceCount
                    )
                }
                preparer.prepareChapter(
                    currentBook,
                    loadedChapter,
                    onProgress = { done, total ->
                        scope.launch {
                            updateState {
                                it.copy(
                                    isPreparing = true,
                                    preparedCount = done,
                                    preparedTotal = total
                                )
                            }
                        }
                    },
                    onComplete = { success ->
                        scope.launch {
                            updateState {
                                it.copy(
                                    isPreparing = false,
                                    preparedCount = loadedChapter.sentenceCount,
                                    preparedTotal = loadedChapter.sentenceCount
                                )
                            }
                            if (!success) fallbackToSystemTts()
                        }
                    }
                )
            } else if (preparer == null) {
                updateState { it.copy(isPreparing = false) }
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
                if (version != navigationVersion) return@launch
                if (chapter !== currentChapter) {
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
                bookTitle = currentBook.title,
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
        synthesizer?.speak(text, speechRate, utteranceId)
    }

    private fun fallbackToSystemTts() {
        val current = synthesizer ?: return
        if (fallbackActive || current.isSystemEngine) return
        synthesizer?.stop()
        synthesizer?.shutdown()
        pendingReady.clear()
        val fallback = fallbackSynthesizerFactory(synthesizerListener)
        synthesizer = fallback
        fallbackActive = true
        engineLabel = fallbackEngineLabel
        updateState {
            it.copy(
                engineLabel = fallbackEngineLabel,
                canCacheBook = false,
                isPreparing = false,
                preparedCount = 0,
                preparedTotal = 0
            )
        }
        if (fallback.isReady) {
            loadAndSpeakCurrent()
        } else {
            pendingReady += { loadAndSpeakCurrent() }
        }
    }

    private fun handleSynthesizerReady() {
        engineLabel = if (fallbackActive) {
            fallbackEngineLabel
        } else {
            synthesizer?.engineLabel ?: engineLabelForSettings()
        }
        updateState {
            it.copy(
                engineLabel = engineLabel,
                canCacheBook = synthesizer?.bookPreparer != null
            )
        }
        pendingReady.toList().forEach { it() }
        pendingReady.clear()
    }

    /**
     * Single event entry for the whole state machine. Every synthesizer
     * callback is folded into a [TtsEvent] (carrying the current sentence's
     * [TtsMark]) before reaching this point, so playback control flow never
     * depends on which concrete engine emitted the event.
     */
    internal fun handleTtsEvent(event: TtsEvent) {
        when (event) {
            is TtsEvent.Boundary -> {
                if (!playing) return
                // Only a successful start clears the error streak.
                consecutiveErrors = 0
                updateState { it.copy(isPlaying = true) }
            }

            is TtsEvent.WordBoundary -> {
                // M3 will map word boundaries to word-level highlighting; for
                // now the sentence-level highlight already covers the position,
                // so the event only proves the engine can emit timestamps.
                if (playing) updateState { it.copy(isPlaying = true) }
            }

            is TtsEvent.End -> {
                if (!playing) return
                sentenceIndex++
                loadAndSpeakCurrent()
            }

            is TtsEvent.Error -> {
                if (!playing) return
                consecutiveErrors++
                if (consecutiveErrors >= 25) {
                    pause()
                    return
                }
                sentenceIndex++
                loadAndSpeakCurrent()
            }
        }
    }

    /**
     * Maps a synthesizer [utteranceId] to the [TtsMark] of the sentence it
     * names, or null when it no longer names the current utterance (a stale /
     * out-of-order callback). The mark carries the exact DOM block/offset so
     * event handlers never re-search the text.
     */
    private fun markFor(utteranceId: String): TtsMark? {
        if (utteranceId != utteranceIdFor(chapterIndex, sentenceIndex, speakAttempt)) return null
        val currentChapter = chapter ?: return null
        if (sentenceIndex !in currentChapter.sentences.indices) return null
        val text = currentChapter.sentences[sentenceIndex]
        val location = currentChapter.sentenceLocation(sentenceIndex)
        return TtsMark(
            sentenceIndex = sentenceIndex,
            text = text,
            blockIndex = location?.first ?: -1,
            offset = location?.second ?: 0,
            length = location?.third ?: 0
        )
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

