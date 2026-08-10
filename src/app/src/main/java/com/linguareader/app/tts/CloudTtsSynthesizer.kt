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
) : TtsSynthesizer, ChapterTtsPreparer {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cacheRoot = File(appContext.filesDir, "tts_cache")

    @Volatile
    private var shutdown = false

    @Volatile
    private var chapterFailed = false

    private var prepareJob: Job? = null
    private var preparedChapterKey: String? = null
    private var currentPlayer: MediaPlayer? = null

    val engineLabel: String get() = backend.label

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
        scope.launch {
            val ready = waitForFileOrSynthesize(file, text, voice)
            if (!ready || shutdown) {
                if (!ready) mainHandler.post { listener.onError(utteranceId) }
                return@launch
            }
            mainHandler.post { play(file, rate, utteranceId) }
        }
    }

    override fun stop() {
        mainHandler.post { releaseCurrentPlayer() }
    }

    override fun shutdown() {
        shutdown = true
        prepareJob?.cancel()
        scope.cancel()
        mainHandler.post { releaseCurrentPlayer() }
    }

    private suspend fun generateOne(book: Book, chapter: TtsChapter, index: Int): Boolean {
        val text = chapter.sentences[index]
        val voice = backend.voiceFor(text)
        val file = cacheFile(book.id, chapter.chapterIndex, index, voice)
        if (file.exists() && file.length() > 0) return true
        return backend.synthesize(text, backend.voiceFor(text), file).isSuccess
    }

    private suspend fun waitForFileOrSynthesize(
        file: File,
        text: String,
        voice: String
    ): Boolean {
        val deadline = System.currentTimeMillis() + 25_000
        while (!file.exists() && System.currentTimeMillis() < deadline) {
            if (shutdown || chapterFailed) break
            delay(100)
        }
        if (file.exists() && file.length() > 0) return true
        if (chapterFailed || shutdown) return false
        return backend.synthesize(text, voice, file).isSuccess
    }

    private fun play(file: File, rate: Float, utteranceId: String) {
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
        val parts = id.split(":")
        if (parts.size != 3) return null
        val chapter = parts[1].toIntOrNull() ?: return null
        val sentence = parts[2].toIntOrNull() ?: return null
        return Triple(parts[0], chapter, sentence)
    }
}
