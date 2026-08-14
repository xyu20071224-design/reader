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
 * - [synthesizerFactory] creates the engine (system / Piper / cloud); the
 *   engine only sees the [TtsSynthesizer] contract.
 * - [chapterLoader] produces a [TtsChapter] for a book + chapter index (the
 *   service wraps the IO-backed extractor, a test returns a fixed chapter).
 * - [isSystemEngine] / [engineLabelForSettings] / [engineLabelForSynthesizer]
 *   isolate the two remaining engine-identity decisions from the queue logic.
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
    private val engineLabelForSettings: () -> String,
    private val engineLabelForSynthesizer: (TtsSynthesizer?) -> String,
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
    private var engineLabel = "系统语音"
    private var state = TtsPlaybackState()

    /** Listener handed to [synthesizerFactory]; routes engine callbacks here. */
    val synthesizerListener: TtsSynthesizerListener = object : TtsSynthesizerListener {
        override fun onReady() { scope.launch { handleSynthesizerReady() } }
        override fun onInitFailed(status: Int) { scope.launch { pause() } }
        override fun onStart(utteranceId: String) { scope.launch { handleUtteranceStart(utteranceId) } }
        override fun onDone(utteranceId: String) { scope.launch { handleUtteranceDone(utteranceId) } }
        override fun onError(utteranceId: String) { scope.launch { handleUtteranceError(utteranceId) } }
    }

    // Read-only views for the shell (media session / notification metadata).
    val currentBook: Book? get() = book
    val currentChapter: TtsChapter? get() = chapter
    val isPlaying: Boolean get() = playing
    val currentSpeechRate: Float get() = speechRate

    // ── Playback control ──────────────────────────────────────────────────

    fun startPlayback(newBook: Book, requestedChapter: Int, requestedSentence: Int) {
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
        playing = true
        updateState { it.copy(isPlaying = true) }
        ensureSynthesizer { loadAndSpeakCurrent() }
        scheduleProgressSave()
    }

    fun pause() {
        if (book == null) return
        playing = false
        synthesizer?.stop()
        saveProgressNow()
        updateState { it.copy(isPlaying = false) }
    }

    fun next() {
        if (book == null) return
        playing = true
        synthesizer?.stop()
        sentenceIndex++
        loadAndSpeakCurrent()
    }

    fun previous() {
        val currentBook = book ?: return
        playing = true
        synthesizer?.stop()
        if (sentenceIndex > 0) {
            sentenceIndex--
            loadAndSpeakCurrent()
        } else if (chapterIndex > 0) {
            chapterIndex--
            sentenceIndex = 0
            lastLoadedChapter = null
            // Capture the chapter this request targets; a slower (older) load
            // must not overwrite `chapter` after the queue has moved on again.
            val targetChapter = chapterIndex
            scope.launch {
                val previous = chapterLoader(currentBook, targetChapter)
                if (chapterIndex != targetChapter || book !== currentBook) return@launch
                chapter = previous
                sentenceIndex = (previous.sentenceCount - 1).coerceAtLeast(0)
                speakCurrent()
            }
        } else {
            loadAndSpeakCurrent()
        }
    }

    fun setRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2f)
        if (clamped == speechRate) return
        speechRate = clamped
        updateState { it.copy(speechRate = clamped) }
        if (playing && book != null && chapter != null && lastLoadedChapter == chapterIndex) {
            synthesizer?.stop()
            speakCurrent()
        }
    }

    fun stop() {
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
        val wasPlaying = playing
        synthesizer?.stop()
        synthesizer?.shutdown()
        synthesizer = null
        pendingReady.clear()
        preparedChapterKey = null
        engineLabel = engineLabelForSettings()
        updateState {
            it.copy(
                engineLabel = engineLabel,
                isPreparing = false,
                preparedCount = 0,
                preparedTotal = 0
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
        scope.launch {
            val loadedChapter = chapterLoader(currentBook, changedChapter)
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
        if (created.isReady) onReady() else pendingReady += onReady
    }

    private fun loadAndSpeakCurrent() {
        val currentBook = book ?: return
        if (!playing) return
        if (chapterIndex !in currentBook.chapters.indices) {
            finishPlayback()
            return
        }
        scope.launch {
            val loadedChapter = chapterLoader(currentBook, chapterIndex)
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
            scope.launch {
                val loaded = withTimeoutOrNull(chapterReadyTimeoutMs) { deferred.await() }
                if (chapterReadyDeferred !== deferred) return@launch
                if (chapter !== currentChapter) return@launch
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
        if (isSystemEngine(current)) return
        synthesizer?.stop()
        synthesizer?.shutdown()
        synthesizer = null
        pendingReady.clear()
        engineLabel = fallbackEngineLabel
        updateState {
            it.copy(
                engineLabel = fallbackEngineLabel,
                isPreparing = false,
                preparedCount = 0,
                preparedTotal = 0
            )
        }
        ensureSynthesizer { loadAndSpeakCurrent() }
    }

    private fun handleSynthesizerReady() {
        engineLabel = engineLabelForSynthesizer(synthesizer)
        updateState { it.copy(engineLabel = engineLabel) }
        pendingReady.toList().forEach { it() }
        pendingReady.clear()
    }

    private fun handleUtteranceStart(utteranceId: String) {
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

