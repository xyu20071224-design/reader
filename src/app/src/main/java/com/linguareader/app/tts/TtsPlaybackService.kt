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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Collections

/**
 * Foreground media service — a thin Android shell around [TtsPlaybackEngine].
 *
 * Keeps only the Android-specific concerns: the foreground notification, the
 * media session, intent routing, the IO-backed chapter extractor and the
 * versioned progress persistence. The sentence queue and all playback state
 * live in the pure [TtsPlaybackEngine], which is unit-tested in isolation.
 */
class TtsPlaybackService : Service() {
    private val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ ->
        // 兜底：章节抽取（extractor.chapter）里 Jsoup.parse(File) 会在章节文件
        // 缺失/损坏时抛 IOException。别让前台服务进程因此崩掉——停掉这次播放即可，
        // 下次点开书时用户自会看到章节坏了的提示。
        stopSelf()
    }
)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Multi-voice M2: background speaker tagging, never on the playback path. */
    private val tagScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val extractor = TtsTextExtractor()
    private val repository by lazy { LibraryRepository(applicationContext) }
    private val speakerTags by lazy { MultiVoiceSupport.speakerTagRepository(applicationContext) }
    /** Multi-voice M3: per-book character → voice mapping. */
    private val voiceMaps by lazy { MultiVoiceSupport.voiceMapRepository(applicationContext) }

    /** Mapping of the book being listened to; null = single-voice playback. */
    @Volatile
    private var activeVoiceMap: BookVoiceMap? = null

    /**
     * Settings snapshot for voice resolution. Reading SharedPreferences (and
     * decrypting the stored keys) for every sentence would be wasteful, so the
     * snapshot is cached and dropped on ACTION_RECONFIGURE, which is exactly
     * what the settings sheet sends after a change.
     */
    @Volatile
    private var cachedVoiceSettings: CloudTtsSettings? = null
    /**
     * Chapters whose speaker tags were already resolved this session
     * ("bookId:chapter"). The engine reloads the chapter for every single
     * sentence, so without this the cache would be re-read - and a failed
     * request re-issued - once per sentence.
     */
    private val resolvedTagChapters: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    private var mediaSession: MediaSession? = null
    private var isForeground = false

    @Volatile
    private var progressWriteVersion = 0

    /** Serializes the version-check + disk write into one critical section. */
    private val progressWriteMutex = Mutex()

    /** BUG-009: bumped on every handlePlay/stopPlayback; stale async chapter
     *  queries compare against it before starting playback. */
    @Volatile
    private var playGeneration = 0

    private val engine by lazy {
        TtsPlaybackEngine(
            synthesizerFactory = { listener ->
                // The pre-generation path inside the cloud synthesizer must
                // resolve voices exactly like the playback path, or cached audio
                // would sit under a voice nobody asks for.
                TtsSynthesizerFactory.create(applicationContext, listener, ::resolveVoice)
            },
            chapterLoader = { book, index ->
                withContext(Dispatchers.IO) {
                    val loaded = extractor.chapter(book, index)
                    // Multi-voice M2, once per chapter: cached LLM tags are
                    // applied immediately, an untagged chapter starts on the
                    // rule-layer tags while a background request refines them.
                    // Listening never waits for the network. Later sentences see
                    // the tags through the extractor cache.
                    if (!resolvedTagChapters.add(book.id + ":" + index)) {
                        loaded
                    } else {
                        val cached =
                            speakerTags.cachedSpeakers(book.id, index, loaded.sentenceCount)
                        if (cached != null) {
                            extractor.applySpeakers(book.id, index, cached)
                            loaded.withSpeakers(cached)
                        } else {
                            requestSpeakerTags(book, index, loaded)
                            loaded
                        }
                    }
                }
            },
            isSystemEngine = { it is SystemTtsSynthesizer },
            // BUG-001: the engine's last resort — a real system engine, never
            // the settings factory (which would recreate the broken cloud one).
            fallbackSynthesizerFactory = { listener ->
                SystemTtsSynthesizer(applicationContext, listener)
            },
            onChapterRequest = { chapter -> _chapterRequests.tryEmit(chapter) },
            onBookSwitched = {
                extractor.clear()
                resolvedTagChapters.clear()
                // The mapping is per book; never let the old one leak into the
                // new book while its own mapping is still loading.
                activeVoiceMap = null
            },
            onProgressSave = { book, chapter, sentence ->
                saveProgressNow(book, chapter, sentence)
            },
            onState = { s ->
                _state.value = s
                updateMediaSession()
                updateNotification()
            },
            voiceForSpeaker = ::resolveVoice,
            dispatcher = Dispatchers.Main.immediate,
            readerLoadedChapterFor = { id ->
                readerChapterByBook?.takeIf { it.first == id }?.second
            }
        )
    }

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
        // PLAY and STANDBY are delivered via ContextCompat.startForegroundService.
        // The framework requires startForeground() soon after, or it kills the
        // service, so satisfy the foreground contract up front on every
        // startForeground entry point instead of relying on the happy path.
        when (intent?.action) {
            ACTION_PLAY, ACTION_STANDBY -> ensureForeground()
        }
        when (intent?.action) {
            ACTION_PLAY -> handlePlay(intent)
            ACTION_STANDBY -> handleStandby(intent)
            ACTION_TOGGLE -> if (engine.isPlaying) pause() else resume()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_NEXT -> nextSentence()
            ACTION_PREVIOUS -> previousSentence()
            ACTION_STOP -> stopPlayback()
            ACTION_RATE -> intent.getFloatExtra(EXTRA_RATE, engine.currentSpeechRate).let(::setRate)
            ACTION_RECONFIGURE -> {
                // Settings changed: drop the snapshot and re-assign voices for
                // the new engine (the voice library may be a different one).
                cachedVoiceSettings = null
                engine.currentBook?.let { refreshVoiceMap(it) }
                engine.reconfigure()
            }
            ACTION_CACHE_BOOK -> engine.cacheWholeBook()
        }
        return START_NOT_STICKY
    }

    private fun handleStandby(intent: Intent) {
        val json = intent.getStringExtra(EXTRA_BOOK_JSON)
        val newBook = json?.let { runCatching { Book.fromJson(JSONObject(it)) }.getOrNull() }
        if (newBook == null) {
            // Fail cleanly instead of leaving a half-started standby session.
            _state.value = TtsPlaybackState()
            stopSelf()
            return
        }
        val requestedChapter = intent.getIntExtra(EXTRA_CHAPTER, 0)
        refreshVoiceMap(newBook)
        engine.startStandby(newBook, requestedChapter)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopPlayback()
    }

    override fun onDestroy() {
        engine.shutdown()
        // Reset the process-wide static state too, so the reader never shows a
        // listening bar for a service that no longer exists.
        _state.value = TtsPlaybackState()
        companionInstance = null
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        scope.cancel()
        tagScope.cancel()
        super.onDestroy()
    }

    // ── Multi-voice speaker tagging (M2) ─────────────────────────────────

    /**
     * Requests LLM speaker tags for one chapter in the background.
     *
     * The result is written into the extractor cache and pushed into the
     * running engine, so the current chapter switches to per-character voices
     * mid-playback and later loads start already tagged. Every failure path
     * (disabled AI, no key, no roster, network error) simply leaves the
     * rule-layer tags in place.
     */
    private fun requestSpeakerTags(book: Book, chapterIndex: Int, chapter: TtsChapter) {
        if (!multiVoiceRequested()) return
        tagScope.launch {
            val speakers = runCatching {
                speakerTags.tagChapter(
                    bookId = book.id,
                    chapterIndex = chapterIndex,
                    chapterTitle = chapter.title,
                    blocks = chapter.blocks,
                    sentenceCount = chapter.sentenceCount
                )
            }.getOrNull() ?: return@launch
            extractor.applySpeakers(book.id, chapterIndex, speakers)
            engine.applySpeakerTags(book.id, chapterIndex, speakers)
            // Fresh tags mean fresh co-occurrence data for the assigner (M3);
            // existing assignments are kept, only new characters are added.
            refreshVoiceMap(book)
        }
    }

    /**
     * The M4 switch decides whether anything multi-voice runs at all; D2 limits
     * it to the cloud engines. With it off there are no tagging requests and no
     * voice assignment - playback is exactly the pre-M1 single-voice one plus
     * the manual narrator/dialogue overrides.
     */
    private fun multiVoiceRequested(): Boolean =
        MultiVoiceSupport.multiVoiceActive(
            voiceSettings(),
            MultiVoiceSupport.systemUsableVoiceCount(applicationContext)
        )

    private fun voiceSettings(): CloudTtsSettings =
        cachedVoiceSettings ?: CloudTtsSettings.load(applicationContext).also {
            cachedVoiceSettings = it
        }

    // ── Multi-voice voice assignment (M3) ────────────────────────────────

    /**
     * Voice for one sentence, used by both the playback queue and the cloud
     * pre-generation path.
     *
     * Order: the assigned per-character / per-language voice (M3, edited by the
     * M4 panel), then the manually configured narrator / dialogue voice (M1),
     * then null = "engine default" so the backend keeps its language routing.
     */
    private fun resolveVoice(speaker: String, text: String): String? {
        // The assigned mapping only exists while multi-voice is on, and the M4
        // panel edits exactly that mapping, so it wins over the M1 fields; those
        // remain the single-voice fallback (and the narration voice for a
        // language the mapping has no entry for).
        activeVoiceMap
            ?.voiceFor(speaker, TtsLanguage.of(text))
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val settings = voiceSettings()
        if (speaker.equals(SpeakerRuleTagger.NARRATOR, ignoreCase = true)) {
            return settings.narratorVoice.takeIf { it.isNotBlank() }
        }
        return settings.dialogueVoice.takeIf { it.isNotBlank() }
    }

    /**
     * Builds (or extends) the character → voice mapping for a book in the
     * background: refresh the engine voice library, then assign every character
     * the AI profile knows about. Locked entries and existing assignments are
     * preserved by [VoiceMapRepository]; any failure just leaves playback on the
     * M1 narrator/dialogue voices.
     */
    private fun refreshVoiceMap(book: Book) {
        if (!multiVoiceRequested()) {
            activeVoiceMap = null
            return
        }
        val settings = voiceSettings()
        tagScope.launch {
            val map = runCatching {
                val library = MultiVoiceSupport.library(applicationContext, settings)
                voiceMaps.ensureFor(
                    bookId = book.id,
                    library = library,
                    narratorLanguages = MultiVoiceSupport.NARRATOR_LANGUAGES,
                    reserved = MultiVoiceSupport.reservedVoices(settings)
                )
            }.getOrNull()
            if (map != null && map.bookId == book.id) activeVoiceMap = map
        }
    }

    // ── Playback control (thin wrappers over the engine) ─────────────────

    private fun handlePlay(intent: Intent) {
        val json = intent.getStringExtra(EXTRA_BOOK_JSON) ?: return
        val newBook = runCatching { Book.fromJson(JSONObject(json)) }.getOrNull() ?: return
        val requestedChapter = intent.getIntExtra(EXTRA_CHAPTER, 0)
        val blockText = intent.getStringExtra(EXTRA_BLOCK_TEXT).orEmpty()
        val blockOffset = intent.getIntExtra(EXTRA_BLOCK_OFFSET, 0)
        refreshVoiceMap(newBook)

        // BUG-009: ACTION_STOP can land while the chapter query below is still
        // running; without a generation check the query would resurrect the
        // playback it was meant to stop.
        val generation = ++playGeneration
        when {
            blockText.isNotEmpty() -> scope.launch {
                val chapter = withContext(Dispatchers.IO) { extractor.chapter(newBook, requestedChapter) }
                if (generation != playGeneration) return@launch
                val index = chapter.sentenceIndexAt(blockText, blockOffset) ?: 0
                engine.startPlayback(newBook, requestedChapter, index)
            }

            // 没带起点（如点到空块）时从章首起播，纯防御兜底。
            else -> engine.startPlayback(newBook, requestedChapter, 0)
        }
    }

    private fun resume() {
        ensureForeground()
        engine.resume()
    }

    private fun pause() = engine.pause()

    private fun nextSentence() = engine.next()

    private fun previousSentence() = engine.previous()

    private fun setRate(rate: Float) = engine.setRate(rate)

    private fun stopPlayback() {
        playGeneration++
        engine.stop()
        mediaSession?.isActive = false
        updateMediaSession()
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    // ── Progress persistence ─────────────────────────────────────────────

    /**
     * Persists the latest listening position with last-write-wins ordering.
     *
     * Each dispatch is stamped with a monotonic [progressWriteVersion] and only
     * the newest stamp is allowed to reach the disk. The stamp comparison and
     * the write are serialized under [progressWriteMutex], closing the
     * check/write gap so an older snapshot can never overwrite a newer one.
     */
    private fun saveProgressNow(book: Book, chapter: Int, sentence: Int) {
        val version = ++progressWriteVersion
        saveScope.launch {
            progressWriteMutex.withLock {
                if (version != progressWriteVersion) return@withLock
                runCatching { repository.saveListeningProgress(book, chapter, sentence) }
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
        // BUG-003: without ever activating the session, bluetooth headsets and
        // the system media controls never see this player.
        mediaSession?.isActive = true
        isForeground = true
    }

    private fun updateNotification() {
        if (!isForeground) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val currentBook = engine.currentBook
        val title = currentBook?.title ?: "听书"
        val detail = engine.currentChapter?.title
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
                if (engine.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (engine.isPlaying) "暂停" else "播放",
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
        val currentBook = engine.currentBook
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentBook?.title ?: "听书")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, engine.currentChapter?.title ?: "")
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
                    if (engine.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    engine.currentSpeechRate
                )
                .build()
        )
    }

    companion object {
        @Volatile
        private var companionInstance: TtsPlaybackService? = null

        @Volatile
        private var readerChapterByBook: Pair<String, Int>? = null

        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 0x544553

        private const val ACTION_PLAY = "com.linguareader.app.tts.PLAY"
        private const val ACTION_STANDBY = "com.linguareader.app.tts.STANDBY"
        private const val ACTION_TOGGLE = "com.linguareader.app.tts.TOGGLE"
        private const val ACTION_PAUSE = "com.linguareader.app.tts.PAUSE"
        private const val ACTION_RESUME = "com.linguareader.app.tts.RESUME"
        private const val ACTION_NEXT = "com.linguareader.app.tts.NEXT"
        private const val ACTION_PREVIOUS = "com.linguareader.app.tts.PREVIOUS"
        private const val ACTION_STOP = "com.linguareader.app.tts.STOP"
        private const val ACTION_RATE = "com.linguareader.app.tts.RATE"
        private const val ACTION_RECONFIGURE = "com.linguareader.app.tts.RECONFIGURE"
        private const val ACTION_CACHE_BOOK = "com.linguareader.app.tts.CACHE_BOOK"

        private const val EXTRA_BOOK_JSON = "book_json"
        private const val EXTRA_CHAPTER = "chapter"
        private const val EXTRA_BLOCK_TEXT = "block_text"
        private const val EXTRA_BLOCK_OFFSET = "block_offset"
        private const val EXTRA_RATE = "rate"

        private val _state = MutableStateFlow(TtsPlaybackState())
        val state: StateFlow<TtsPlaybackState> = _state.asStateFlow()

        private val _chapterRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        val chapterRequests: SharedFlow<Int> = _chapterRequests.asSharedFlow()

        /** Opens listening without playing; the user picks the start point. */
        fun startStandby(
            context: Context,
            book: Book,
            chapterIndex: Int
        ) {
            val intent = Intent(context, TtsPlaybackService::class.java)
                .setAction(ACTION_STANDBY)
                .putExtra(EXTRA_BOOK_JSON, book.toJson().toString())
                .putExtra(EXTRA_CHAPTER, chapterIndex)
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

        /** Starts whole-book cache (全书缓存). No-op unless a listening session is active. */
        fun cacheWholeBook(context: Context) {
            if (_state.value.bookId == null) return
            context.startService(
                Intent(context, TtsPlaybackService::class.java).setAction(ACTION_CACHE_BOOK)
            )
        }

        /** Called by the reader whenever a chapter WebView reports ready. */
        fun onReaderChapterLoaded(bookId: String, chapterIndex: Int) {
            readerChapterByBook = bookId to chapterIndex
            companionInstance?.engine?.onReaderChapterLoaded(bookId, chapterIndex)
        }

        /** Called when the user manually switches chapters during playback. */
        fun onReaderChapterSelected(bookId: String, chapterIndex: Int) {
            companionInstance?.engine?.onReaderChapterSelected(bookId, chapterIndex)
        }

        /** Called after a manual page turn so the queue follows the reader. */
        fun onReaderPositionChanged(bookId: String, chapterIndex: Int, blockText: String) {
            companionInstance?.engine?.onReaderPositionChanged(bookId, chapterIndex, blockText)
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

