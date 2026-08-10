package com.linguareader.app.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.linguareader.app.MainActivity
import com.linguareader.app.data.Book
import com.linguareader.app.data.LibraryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Foreground media service that owns the book playback queue.
 *
 * - Speaks one sentence at a time with the system TTS (mixed EN/ZH).
 * - Requests chapter switches through [chapterRequests]; the reader screen
 *   confirms via [onReaderChapterLoaded] before the next sentence starts.
 * - Exposes play/pause/next/previous/stop and speech rate to both the reader
 *   UI and the lock-screen / notification controls.
 * - Persists per-book listening progress into book metadata.
 */
class TtsPlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val extractor = TtsTextExtractor()
    private val repository by lazy { LibraryRepository(applicationContext) }

    private var synthesizer: TtsSynthesizer? = null
    private val pendingReady = mutableListOf<() -> Unit>()
    private var mediaSession: MediaSession? = null
    private var book: Book? = null
    private var chapter: TtsChapter? = null
    private var chapterIndex = 0
    private var sentenceIndex = 0
    private var speechRate = 1f
    private var playing = false
    private var isForeground = false
    private var lastLoadedChapter: Int? = null
    private var requestedChapter: Int? = null
    private var chapterReadyDeferred: CompletableDeferred<Int>? = null
    private var consecutiveErrors = 0
    private var progressSaveJob: Job? = null
    private var preparedChapterKey: String? = null

    override fun onCreate() {
        super.onCreate()
        companionInstance = this
        createNotificationChannel()
        mediaSession = MediaSession(this, "LinguaReaderTts").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = nextSentence()
                override fun onSkipToPrevious() = previousSentence()
                override fun onStop() = stopPlayback()
            })
            isActive = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> handlePlay(intent)
            ACTION_TOGGLE -> if (playing) pause() else resume()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_NEXT -> nextSentence()
            ACTION_PREVIOUS -> previousSentence()
            ACTION_STOP -> stopPlayback()
            ACTION_RATE -> intent.getFloatExtra(EXTRA_RATE, speechRate).let(::setRate)
            ACTION_RECONFIGURE -> reconfigureSynthesizer()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopPlayback()
    }

    override fun onDestroy() {
        if (book != null) saveProgressNow()
        synthesizer?.shutdown()
        synthesizer = null
        companionInstance = null
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        chapterReadyDeferred?.complete(-1)
        chapterReadyDeferred = null
        progressSaveJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ── Playback control ──────────────────────────────────────────────────

    private fun handlePlay(intent: Intent) {
        val json = intent.getStringExtra(EXTRA_BOOK_JSON) ?: return
        val newBook = runCatching { Book.fromJson(JSONObject(json)) }.getOrNull() ?: return
        val requestedChapter = intent.getIntExtra(EXTRA_CHAPTER, 0)
        val requestedSentence = intent.getIntExtra(EXTRA_SENTENCE, 0)
        val sentenceText = intent.getStringExtra(EXTRA_SENTENCE_TEXT)?.trim().orEmpty()
        val blockText = intent.getStringExtra(EXTRA_BLOCK_TEXT).orEmpty()
        val blockOffset = intent.getIntExtra(EXTRA_BLOCK_OFFSET, 0)

        when {
            sentenceText.isNotEmpty() -> scope.launch {
                val chapter = withContext(Dispatchers.IO) { extractor.chapter(newBook, requestedChapter) }
                val index = chapter.sentences.indexOfFirst { it == sentenceText }
                    .takeIf { it >= 0 } ?: 0
                startPlayback(newBook, requestedChapter, index)
            }

            blockText.isNotEmpty() -> scope.launch {
                val chapter = withContext(Dispatchers.IO) { extractor.chapter(newBook, requestedChapter) }
                val index = chapter.sentenceIndexAt(blockText, blockOffset) ?: 0
                startPlayback(newBook, requestedChapter, index)
            }

            else -> startPlayback(newBook, requestedChapter, requestedSentence)
        }
    }

    private fun startPlayback(newBook: Book, requestedChapter: Int, requestedSentence: Int) {
        book = newBook
        chapterIndex = requestedChapter.coerceIn(0, newBook.chapters.lastIndex.coerceAtLeast(0))
        sentenceIndex = requestedSentence.coerceAtLeast(0)
        preparedChapterKey = null
        val readerChapter = readerChapterByBook
        lastLoadedChapter = if (readerChapter?.first == newBook.id) readerChapter.second else null
        playing = true
        consecutiveErrors = 0
        ensureForeground()
        _state.value = TtsPlaybackState(
            bookId = newBook.id,
            bookTitle = newBook.title,
            chapterIndex = chapterIndex,
            sentenceIndex = sentenceIndex,
            sentenceCount = 0,
            currentSentence = "",
            isPlaying = true,
            speechRate = speechRate,
            engineLabel = engineLabelFor(CloudTtsSettings.load(applicationContext))
        )
        mediaSession?.isActive = true
        updateMediaSession()
        updateNotification()
        ensureSynthesizer { loadAndSpeakCurrent() }
    }

    private fun resume() {
        val currentBook = book ?: return
        playing = true
        _state.update { it.copy(isPlaying = true) }
        ensureForeground()
        updateMediaSession()
        updateNotification()
        ensureSynthesizer { loadAndSpeakCurrent() }
        scheduleProgressSave()
    }

    private fun pause() {
        if (book == null) return
        playing = false
        synthesizer?.stop()
        saveProgressNow()
        _state.update { it.copy(isPlaying = false) }
        updateMediaSession()
        updateNotification()
    }

    private fun nextSentence() {
        if (book == null) return
        playing = true
        sentenceIndex++
        loadAndSpeakCurrent()
    }

    private fun previousSentence() {
        val currentBook = book ?: return
        playing = true
        if (sentenceIndex > 0) {
            sentenceIndex--
            loadAndSpeakCurrent()
        } else if (chapterIndex > 0) {
            chapterIndex--
            sentenceIndex = 0
            lastLoadedChapter = null
            scope.launch {
                val previous = withContext(Dispatchers.IO) { extractor.chapter(currentBook, chapterIndex) }
                chapter = previous
                sentenceIndex = (previous.sentenceCount - 1).coerceAtLeast(0)
                speakCurrent()
            }
        } else {
            loadAndSpeakCurrent()
        }
    }

    private fun setRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2f)
        if (clamped == speechRate) return
        speechRate = clamped
        _state.update { it.copy(speechRate = clamped) }
        updateMediaSession()
        if (playing && book != null && chapter != null && lastLoadedChapter == chapterIndex) {
            synthesizer?.stop()
            speakCurrent()
        }
    }

    private fun stopPlayback() {
        saveProgressNow()
        playing = false
        synthesizer?.stop()
        _state.value = TtsPlaybackState(speechRate = speechRate)
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
        mediaSession?.isActive = false
        updateMediaSession()
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun finishPlayback() {
        // Keep progress on the last sentence actually spoken instead of the
        // out-of-range index used to detect the end of the queue.
        val lastSpoken = (chapter?.sentenceCount ?: 0) - 1
        if (lastSpoken >= 0) sentenceIndex = lastSpoken
        stopPlayback()
    }

    /** Rebuilds the synthesizer after cloud TTS settings change. */
    private fun reconfigureSynthesizer() {
        val wasPlaying = playing
        synthesizer?.stop()
        synthesizer?.shutdown()
        synthesizer = null
        pendingReady.clear()
        preparedChapterKey = null
        val settings = CloudTtsSettings.load(applicationContext)
        _state.update {
            it.copy(
                engineLabel = engineLabelFor(settings),
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

    // ── Queue / synthesis ─────────────────────────────────────────────────

    private fun ensureSynthesizer(onReady: () -> Unit) {
        val existing = synthesizer
        if (existing != null) {
            if (existing.isReady) onReady() else pendingReady += onReady
            return
        }
        val created = TtsSynthesizerFactory.create(
            applicationContext,
            object : TtsSynthesizerListener {
                override fun onReady() {
                    scope.launch {
                        _state.update {
                            it.copy(
                                engineLabel = (synthesizer as? CloudTtsSynthesizer)?.engineLabel
                                    ?: "系统语音"
                            )
                        }
                        pendingReady.toList().forEach { it() }
                        pendingReady.clear()
                    }
                }

                override fun onInitFailed(status: Int) {
                    scope.launch { pause() }
                }

                override fun onStart(utteranceId: String) {
                    scope.launch { handleUtteranceStart(utteranceId) }
                }

                override fun onDone(utteranceId: String) {
                    scope.launch { handleUtteranceDone(utteranceId) }
                }

                override fun onError(utteranceId: String) {
                    scope.launch { handleUtteranceError(utteranceId) }
                }
            }
        )
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
            val loadedChapter = withContext(Dispatchers.IO) {
                extractor.chapter(currentBook, chapterIndex)
            }
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
            val chapterKey = "${currentBook.id}:${chapterIndex}"
            if (preparer != null && preparedChapterKey != chapterKey) {
                preparedChapterKey = chapterKey
                _state.update {
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
                        _state.update {
                            it.copy(
                                isPreparing = true,
                                preparedCount = done,
                                preparedTotal = total
                            )
                        }
                    },
                    onComplete = { success ->
                        _state.update {
                            it.copy(
                                isPreparing = false,
                                preparedCount = loadedChapter.sentenceCount,
                                preparedTotal = loadedChapter.sentenceCount
                            )
                        }
                        if (!success) fallbackToSystemTts()
                    }
                )
            } else if (preparer == null) {
                _state.update { it.copy(isPreparing = false) }
            }
            _state.update {
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
        if (sentenceIndex !in currentChapter.sentences.indices) return
        val text = currentChapter.sentences[sentenceIndex]
        if (text.isBlank()) {
            sentenceIndex++
            loadAndSpeakCurrent()
            return
        }
        if (lastLoadedChapter != chapterIndex) {
            requestedChapter = chapterIndex
            _chapterRequests.tryEmit(chapterIndex)
            val deferred = CompletableDeferred<Int>()
            chapterReadyDeferred = deferred
            scope.launch {
                // The reader confirms quickly when it is on screen; when the
                // app is backgrounded the book must keep playing anyway, so a
                // short grace period lets playback continue without a WebView.
                val loaded = withTimeoutOrNull(2_000) { deferred.await() }
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
        consecutiveErrors = 0
        val utteranceId = utteranceIdFor(chapterIndex, sentenceIndex)
        _state.update {
            it.copy(
                bookId = currentBook.id,
                bookTitle = currentBook.title,
                chapterIndex = chapterIndex,
                sentenceIndex = sentenceIndex,
                sentenceCount = chapter?.sentenceCount ?: it.sentenceCount,
                currentSentence = text,
                isPlaying = true
            )
        }
        updateMediaSession()
        updateNotification()
        scheduleProgressSave()
        synthesizer?.speak(text, speechRate, utteranceId)
    }

    private fun fallbackToSystemTts() {
        if (synthesizer is SystemTtsSynthesizer) return
        synthesizer?.stop()
        synthesizer?.shutdown()
        synthesizer = null
        pendingReady.clear()
        _state.update {
            it.copy(
                engineLabel = "系统语音（云 TTS 失败）",
                isPreparing = false,
                preparedCount = 0,
                preparedTotal = 0
            )
        }
        ensureSynthesizer { loadAndSpeakCurrent() }
    }

    private fun handleUtteranceStart(utteranceId: String) {
        if (utteranceId == utteranceIdFor(chapterIndex, sentenceIndex)) {
            _state.update { it.copy(isPlaying = true) }
        }
    }

    private fun handleUtteranceDone(utteranceId: String) {
        if (utteranceId != utteranceIdFor(chapterIndex, sentenceIndex) || !playing) return
        sentenceIndex++
        loadAndSpeakCurrent()
    }

    private fun handleUtteranceError(utteranceId: String) {
        if (utteranceId != utteranceIdFor(chapterIndex, sentenceIndex) || !playing) return
        consecutiveErrors++
        if (consecutiveErrors >= 25) {
            pause()
            return
        }
        sentenceIndex++
        loadAndSpeakCurrent()
    }

    // ── Reader handshake ──────────────────────────────────────────────────

    /** Called by the reader after a chapter WebView is ready. */
    fun onReaderChapterLoaded(bookId: String, loadedChapter: Int) {
        if (book?.id != bookId) return
        lastLoadedChapter = loadedChapter
        chapterReadyDeferred?.complete(loadedChapter)
        chapterReadyDeferred = null
    }

    /** Called when the user manually picks a chapter while playback is active. */
    fun onReaderChapterSelected(bookId: String, selectedChapter: Int) {
        if (book?.id != bookId) return
        chapterIndex = selectedChapter.coerceIn(0, book?.chapters?.lastIndex?.coerceAtLeast(0) ?: 0)
        sentenceIndex = 0
        preparedChapterKey = null
        lastLoadedChapter = null
        chapter = null
        _state.update {
            it.copy(
                chapterIndex = chapterIndex,
                sentenceIndex = 0,
                sentenceCount = 0,
                currentSentence = ""
            )
        }
        updateMediaSession()
        updateNotification()
        scheduleProgressSave()
        if (playing) loadAndSpeakCurrent()
    }

    /**
     * Called when the reader page changes (manual page turn). If the page now
     * starts in another paragraph, the queue jumps to that paragraph's first
     * sentence so reading and listening stay together.
     */
    fun onReaderPositionChanged(bookId: String, changedChapter: Int, blockText: String) {
        val currentBook = book ?: return
        if (currentBook.id != bookId || !playing || blockText.isBlank()) return
        if (changedChapter != chapterIndex || waitingForChapter()) return
        scope.launch {
            val loadedChapter = withContext(Dispatchers.IO) {
                extractor.chapter(currentBook, changedChapter)
            }
            if (loadedChapter.sentenceBelongsToBlock(sentenceIndex, blockText)) return@launch
            val target = loadedChapter.firstSentenceIndexInBlock(blockText) ?: return@launch
            if (target == sentenceIndex) return@launch
            sentenceIndex = target
            if (playing) speakCurrent()
        }
    }

    private fun waitingForChapter(): Boolean = chapterReadyDeferred != null

    // ── Progress ──────────────────────────────────────────────────────────

    private fun scheduleProgressSave() {
        progressSaveJob?.cancel()
        progressSaveJob = scope.launch {
            delay(5_000)
            saveProgressNow()
        }
    }

    private fun saveProgressNow() {
        val currentBook = book ?: return
        val chapter = chapterIndex
        val sentence = sentenceIndex
        progressSaveJob?.cancel()
        progressSaveJob = null
        saveScope.launch {
            runCatching {
                repository.saveListeningProgress(currentBook, chapter, sentence)
            }
        }
    }

    // ── Media session / notification ──────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "听书播放", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun ensureForeground() {
        val notification = notification()
        if (isForeground) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            return
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )
        isForeground = true
    }

    private fun updateNotification() {
        if (!isForeground) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val currentBook = book
        val title = currentBook?.title ?: "听书"
        val detail = chapter?.title
            ?.takeIf { it.isNotBlank() }
            ?: _state.value.currentSentence.ifBlank { "语境阅读" }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(
                android.R.drawable.ic_media_previous,
                "上一句",
                actionIntent(ACTION_PREVIOUS)
            )
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "播放",
                actionIntent(ACTION_TOGGLE)
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "下一句",
                actionIntent(ACTION_NEXT)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                actionIntent(ACTION_STOP)
            )
            .build()
    }

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, TtsPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun updateMediaSession() {
        val session = mediaSession ?: return
        val currentBook = book
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentBook?.title ?: "听书")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, chapter?.title ?: "")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "语境阅读")
                .build()
        )
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    speechRate
                )
                .build()
        )
    }

    private fun utteranceIdFor(chapter: Int, sentence: Int): String =
        "${book?.id.orEmpty()}:$chapter:$sentence"

    private fun engineLabelFor(settings: CloudTtsSettings): String = when (settings.mode) {
        TtsEngineMode.AZURE -> "Azure 云 TTS"
        TtsEngineMode.OPENAI_COMPAT -> "自建服务器（OpenAI 兼容）"
        TtsEngineMode.VOLC -> "火山引擎（豆包语音）"
        TtsEngineMode.SYSTEM -> "系统语音"
    }

    companion object {
        @Volatile
        private var companionInstance: TtsPlaybackService? = null

        @Volatile
        private var readerChapterByBook: Pair<String, Int>? = null

        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 0x544553

        private const val ACTION_PLAY = "com.linguareader.app.tts.PLAY"
        private const val ACTION_TOGGLE = "com.linguareader.app.tts.TOGGLE"
        private const val ACTION_PAUSE = "com.linguareader.app.tts.PAUSE"
        private const val ACTION_RESUME = "com.linguareader.app.tts.RESUME"
        private const val ACTION_NEXT = "com.linguareader.app.tts.NEXT"
        private const val ACTION_PREVIOUS = "com.linguareader.app.tts.PREVIOUS"
        private const val ACTION_STOP = "com.linguareader.app.tts.STOP"
        private const val ACTION_RATE = "com.linguareader.app.tts.RATE"
        private const val ACTION_RECONFIGURE = "com.linguareader.app.tts.RECONFIGURE"

        private const val EXTRA_BOOK_JSON = "book_json"
        private const val EXTRA_CHAPTER = "chapter"
        private const val EXTRA_SENTENCE = "sentence"
        private const val EXTRA_SENTENCE_TEXT = "sentence_text"
        private const val EXTRA_BLOCK_TEXT = "block_text"
        private const val EXTRA_BLOCK_OFFSET = "block_offset"
        private const val EXTRA_RATE = "rate"

        private val _state = MutableStateFlow(TtsPlaybackState())
        val state: StateFlow<TtsPlaybackState> = _state.asStateFlow()

        private val _chapterRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        val chapterRequests: SharedFlow<Int> = _chapterRequests.asSharedFlow()

        fun startFromChapter(
            context: Context,
            book: Book,
            chapterIndex: Int,
            sentenceIndex: Int
        ) {
            val intent = Intent(context, TtsPlaybackService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_BOOK_JSON, book.toJson().toString())
                .putExtra(EXTRA_CHAPTER, chapterIndex)
                .putExtra(EXTRA_SENTENCE, sentenceIndex)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startFromSentence(
            context: Context,
            book: Book,
            chapterIndex: Int,
            sentenceText: String
        ) {
            val intent = Intent(context, TtsPlaybackService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_BOOK_JSON, book.toJson().toString())
                .putExtra(EXTRA_CHAPTER, chapterIndex)
                .putExtra(EXTRA_SENTENCE_TEXT, sentenceText)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startFromBlockOffset(
            context: Context,
            book: Book,
            chapterIndex: Int,
            blockText: String,
            blockOffset: Int
        ) {
            val intent = Intent(context, TtsPlaybackService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_BOOK_JSON, book.toJson().toString())
                .putExtra(EXTRA_CHAPTER, chapterIndex)
                .putExtra(EXTRA_BLOCK_TEXT, blockText)
                .putExtra(EXTRA_BLOCK_OFFSET, blockOffset)
            ContextCompat.startForegroundService(context, intent)
        }

        fun toggle(context: Context) {
            if (_state.value.bookId == null) return
            context.startService(Intent(context, TtsPlaybackService::class.java).setAction(ACTION_TOGGLE))
        }

        fun pause(context: Context) {
            if (_state.value.bookId == null) return
            context.startService(Intent(context, TtsPlaybackService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            if (_state.value.bookId == null) return
            context.startService(Intent(context, TtsPlaybackService::class.java).setAction(ACTION_RESUME))
        }

        fun next(context: Context) {
            if (_state.value.bookId == null) return
            context.startService(Intent(context, TtsPlaybackService::class.java).setAction(ACTION_NEXT))
        }

        fun previous(context: Context) {
            if (_state.value.bookId == null) return
            context.startService(Intent(context, TtsPlaybackService::class.java).setAction(ACTION_PREVIOUS))
        }

        fun stop(context: Context) {
            if (_state.value.bookId == null) return
            context.startService(Intent(context, TtsPlaybackService::class.java).setAction(ACTION_STOP))
        }

        fun setRate(context: Context, rate: Float) {
            if (_state.value.bookId == null) return
            context.startService(
                Intent(context, TtsPlaybackService::class.java)
                    .setAction(ACTION_RATE)
                    .putExtra(EXTRA_RATE, rate)
            )
        }

        /** Called by the reader whenever a chapter WebView reports ready. */
        fun onReaderChapterLoaded(bookId: String, chapterIndex: Int) {
            readerChapterByBook = bookId to chapterIndex
            companionInstance?.onReaderChapterLoaded(bookId, chapterIndex)
        }

        /** Called when the user manually switches chapters during playback. */
        fun onReaderChapterSelected(bookId: String, chapterIndex: Int) {
            companionInstance?.onReaderChapterSelected(bookId, chapterIndex)
        }

        /** Called after a manual page turn so the queue follows the reader. */
        fun onReaderPositionChanged(bookId: String, chapterIndex: Int, blockText: String) {
            companionInstance?.onReaderPositionChanged(bookId, chapterIndex, blockText)
        }

        /** Called by the settings sheet after cloud TTS configuration changes. */
        fun onCloudSettingsChanged(context: Context) {
            if (_state.value.bookId != null) {
                context.startService(
                    Intent(context, TtsPlaybackService::class.java).setAction(ACTION_RECONFIGURE)
                )
            }
        }
    }
}
