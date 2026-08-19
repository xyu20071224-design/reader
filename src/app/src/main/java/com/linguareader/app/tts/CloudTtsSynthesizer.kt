package com.linguareader.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.linguareader.app.data.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Optional hook for synthesizers that can pre-generate a whole chapter.
 * The system TTS engine does not implement this; the cloud engines do.
 */
interface ChapterTtsPreparer {
    fun prepareChapter(
        book: Book,
        chapter: TtsChapter,
        onProgress: (prepared: Int, total: Int) -> Unit,
        onComplete: (success: Boolean) -> Unit
    )
}

/**
 * Optional hook for synthesizers that can pre-generate the whole book (F-151
 * "全书缓存"). The system TTS engine does not implement this; the cloud
 * engines do. Progress is reported across the entire book, not per chapter.
 */
interface BookTtsPreparer {
    fun prepareBook(
        book: Book,
        chapterCount: Int,
        chapterProvider: suspend (Int) -> TtsChapter,
        onProgress: (done: Int, total: Int) -> Unit,
        onComplete: (success: Boolean) -> Unit
    )
}

/**
 * Cloud synthesizer (F-151).
 *
 * Pre-generates a whole chapter on first play, writes MP3s into the app cache
 * and plays them with [MediaPlayer]. Playback speed is applied locally, so
 * changing speed never re-synthesizes or re-bills. On any generation failure
 * the service falls back to the system TTS engine for the rest of the chapter.
 */
class CloudTtsSynthesizer(
    context: Context,
    private val backend: CloudTtsBackend,
    private val listener: TtsSynthesizerListener
) : TtsSynthesizer, ChapterTtsPreparer, BookTtsPreparer {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cacheRoot = File(appContext.filesDir, "tts_cache")

    override val capabilities: TtsCapabilities = TtsCapabilities(
        wordBoundaries = false,  // M3: Azure word boundary sidecar, Volcano SSE timestamps
        chapterPreparer = true,
        gapControl = false,
        liveRateChange = true    // playback speed applied locally, no re-synthesis/re-billing
    )

    @Volatile
    private var shutdown = false

    @Volatile
    private var stopped = false

    @Volatile
    private var chapterFailed = false

    @Volatile
    private var prepareJob: Job? = null
    private var bookPrepareJob: Job? = null
    private var preparedChapterKey: String? = null
    private var currentPlayer: MediaPlayer? = null

    /** Cache files currently being synthesized, so two preparers never double-bill one sentence. */
    private val inflightFiles = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    override val engineLabel: String get() = backend.label

    override val chapterPreparer: ChapterTtsPreparer get() = this
    override val bookPreparer: BookTtsPreparer get() = this

    override val isReady: Boolean get() = backend.isConfigured()

    override fun prepareChapter(
        book: Book,
        chapter: TtsChapter,
        onProgress: (Int, Int) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val key = "${book.id}:${chapter.chapterIndex}"
        if (key == preparedChapterKey && prepareJob?.isActive == true) return
        preparedChapterKey = key
        chapterFailed = false
        prepareJob?.cancel()
        val total = chapter.sentences.size
        if (total == 0) {
            mainHandler.post { onComplete(true) }
            return
        }
        prepareJob = scope.launch {
            val semaphore = Semaphore(3)
            val failed = AtomicBoolean(false)
            val completed = AtomicInteger(0)
            val jobs = chapter.sentences.indices.map { index ->
                async {
                    semaphore.withPermit {
                        if (failed.get() || shutdown) {
                            false
                        } else {
                            val ok = generateOne(book, chapter, index)
                            if (!ok) failed.set(true)
                            val done = completed.incrementAndGet()
                            mainHandler.post { onProgress(done, total) }
                            ok
                        }
                    }
                }
            }
            val allOk = jobs.all { it.await() }
            if (allOk && !shutdown) {
                mainHandler.post { onComplete(true) }
            } else if (!shutdown) {
                chapterFailed = true
                mainHandler.post { onComplete(false) }
            }
        }
    }

    override fun speak(text: String, rate: Float, utteranceId: String) {
        val parsed = parseUtteranceId(utteranceId)
        if (parsed == null) {
            mainHandler.post { listener.onError(utteranceId) }
            return
        }
        val (bookId, chapterIndex, sentenceIndex) = parsed
        val voice = backend.voiceFor(text)
        val file = cacheFile(bookId, chapterIndex, sentenceIndex, voice)
        stopped = false
        scope.launch {
            val ready = waitForFileOrSynthesize(file, text, voice)
            if (!ready || shutdown || stopped) return@launch
            mainHandler.post { if (!stopped) play(file, rate, utteranceId) }
        }
    }

    override fun stop() {
        stopped = true
        mainHandler.post { releaseCurrentPlayer() }
    }

    override fun shutdown() {
        shutdown = true
        prepareJob?.cancel()
        bookPrepareJob?.cancel()
        scope.cancel()
        mainHandler.post { releaseCurrentPlayer() }
    }

    private suspend fun generateOne(book: Book, chapter: TtsChapter, index: Int): Boolean {
        val text = chapter.sentences[index]
        val voice = backend.voiceFor(text)
        val file = cacheFile(book.id, chapter.chapterIndex, index, voice)
        if (file.exists() && file.length() > 0) return true
        val key = file.absolutePath
        if (inflightFiles.putIfAbsent(key, true) != null) {
            // Another coroutine (chapter prep vs whole-book cache) is already
            // synthesizing this file — wait for it instead of double-billing.
            while (inflightFiles.containsKey(key) && !shutdown) delay(100)
            return file.exists() && file.length() > 0
        }
        try {
            return backend.synthesize(text, voice, file).isSuccess
        } finally {
            inflightFiles.remove(key)
        }
    }

    override fun prepareBook(
        book: Book,
        chapterCount: Int,
        chapterProvider: suspend (Int) -> TtsChapter,
        onProgress: (done: Int, total: Int) -> Unit,
        onComplete: (success: Boolean) -> Unit
    ) {
        bookPrepareJob?.cancel()
        val job = scope.launch {
            val chapters = mutableListOf<TtsChapter>()
            for (index in 0 until chapterCount) {
                if (shutdown) return@launch
                val loaded = try {
                    chapterProvider(index)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mainHandler.post { onComplete(false) }
                    return@launch
                }
                chapters.add(loaded)
            }
            val total = chapters.sumOf { it.sentences.size }
            if (total == 0) {
                mainHandler.post { onComplete(true) }
                return@launch
            }
            val semaphore = Semaphore(3)
            val failed = AtomicBoolean(false)
            val completed = AtomicInteger(0)
            val jobs = chapters
                .flatMap { chapter -> chapter.sentences.indices.map { sentenceIndex -> chapter to sentenceIndex } }
                .map { (chapter, sentenceIndex) ->
                    async {
                        semaphore.withPermit {
                            if (failed.get() || shutdown) {
                                false
                            } else {
                                val ok = generateOne(book, chapter, sentenceIndex)
                                if (!ok) failed.set(true)
                                val done = completed.incrementAndGet()
                                mainHandler.post { onProgress(done, total) }
                                ok
                            }
                        }
                    }
                }
            val allOk = jobs.all { it.await() }
            if (!shutdown) {
                mainHandler.post { onComplete(allOk) }
            }
        }
        bookPrepareJob = job
    }

    private suspend fun waitForFileOrSynthesize(
        file: File,
        text: String,
        voice: String
    ): Boolean {
        val deadline = System.currentTimeMillis() + 25_000
        while (!file.exists() && System.currentTimeMillis() < deadline) {
            if (shutdown || chapterFailed || stopped) break
            delay(100)
        }
        if (file.exists() && file.length() > 0) return true
        if (chapterFailed || shutdown || stopped) return false
        // Chapter preparation may simply be slower than the first-sentence
        // deadline (long sentences, slow self-hosted servers). Waiting for the
        // in-flight preparation avoids synthesizing the same sentence twice.
        // Whole-book caching counts too: otherwise the first play of a cached
        // sentence would re-synthesize and double-bill (OBS-04).
        val waitDeadline = System.currentTimeMillis() + 30_000
        while (!file.exists() &&
            (prepareJob?.isActive == true || bookPrepareJob?.isActive == true) &&
            !shutdown && !chapterFailed && !stopped &&
            System.currentTimeMillis() < waitDeadline
        ) {
            delay(100)
        }
        if (file.exists() && file.length() > 0) return true
        if (chapterFailed || shutdown || stopped) return false
        return backend.synthesize(text, voice, file).isSuccess
    }

    private fun play(file: File, rate: Float, utteranceId: String) {
        if (stopped || shutdown) return
        releaseCurrentPlayer()
        val player = MediaPlayer()
        currentPlayer = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )
        runCatching { player.setDataSource(file.absolutePath) }
            .onFailure {
                listener.onError(utteranceId)
                releasePlayer(player)
                return
            }
        player.setOnPreparedListener { prepared ->
            if (shutdown || currentPlayer !== prepared) {
                releasePlayer(prepared)
                return@setOnPreparedListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    prepared.playbackParams =
                        PlaybackParams().setSpeed(rate.coerceIn(0.5f, 2f))
                }
            }
            listener.onStart(utteranceId)
            prepared.start()
        }
        player.setOnCompletionListener {
            listener.onDone(utteranceId)
            releasePlayer(player)
        }
        player.setOnErrorListener { _, _, _ ->
            listener.onError(utteranceId)
            releasePlayer(player)
            true
        }
        player.prepareAsync()
    }

    private fun releaseCurrentPlayer() {
        currentPlayer?.let { releasePlayer(it) }
    }

    private fun releasePlayer(player: MediaPlayer) {
        if (currentPlayer === player) currentPlayer = null
        runCatching {
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            player.setOnPreparedListener(null)
            player.stop()
            player.release()
        }
    }

    private fun cacheFile(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        voice: String
    ): File {
        val safeVoice = voice.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(cacheRoot, "$bookId/$chapterIndex/$safeVoice/$sentenceIndex.mp3")
    }

    private fun parseUtteranceId(id: String): Triple<String, Int, Int>? {
        // 引擎生成的 utteranceId 是 4 段 "bookId:chapter:sentence:attempt"
        // （见 TtsPlaybackEngine.utteranceIdFor）；末段 attempt 仅用于去重，
        // 解析时忽略它，只取前 3 段。
        val parts = id.split(":")
        if (parts.size < 3) return null
        val chapter = parts[1].toIntOrNull() ?: return null
        val sentence = parts[2].toIntOrNull() ?: return null
        return Triple(parts[0], chapter, sentence)
    }
}
