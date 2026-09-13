package com.linguareader.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.linguareader.app.data.Book
import com.linguareader.app.packs.PackRepository
import com.linguareader.shared.tts.TtsCacheKey
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

/**
 * Optional hook for synthesizers that can pre-generate the whole book (F-151
 * "全书缓存"). The system TTS engine does not implement this; the cloud
 * engines do. Progress is reported across the entire book, not per chapter.
 */

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
    private val listener: TtsSynthesizerListener,
    /** Maps a speaker tag ("narrator" / name / "dialogue") plus the sentence
     *  text to the voice id to synthesize with (multi-voice M1/M3). Must be the
     *  same resolver the playback engine uses, or pre-generated audio would be
     *  cached under a voice that playback never asks for. */
    private val voiceForSpeaker: (String, String) -> String? = { _, _ -> null }
) : TtsSynthesizer, ChapterTtsPreparer, BookTtsPreparer {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 缓存路径的唯一知情者（见 TtsAudioCache 的类注释：这条路径以前被写了两遍）。 */
    private val cache = TtsAudioCache(appContext)

    /**
     * 预生成音频包（M3）。与 UI 侧各自持有一个实例：两边都读同一份 registry.json，
     * `PackRepository` 用文件时间戳判缓存失效，所以装了新包这边立刻能看见。
     */
    private val packs = PackRepository(appContext)

    /**
     * 引擎身份，进缓存键。快照即可：换引擎/换服务器会走 ACTION_RECONFIGURE，
     * 那条路会重建合成器（见 TtsPlaybackService 的 engine by lazy 与 :196-202）。
     * 复用 VoiceLibraryLoader.engineKey，别再造第二套「引擎身份」的定义。
     */
    private val engineTag: String by lazy {
        runCatching { VoiceLibraryLoader.engineKey(CloudTtsSettings.load(appContext)) }
            .getOrDefault("unknown")
    }

    @Volatile
    private var shutdown = false

    @Volatile
    private var chapterFailed = false

    /** BUG-005: set by stop() so an in-flight synthesis (still waiting for its
     *  file) neither posts audio nor flips the UI back to "playing" after a
     *  pause landed inside that wait window. */
    @Volatile
    private var stopped = false

    private var prepareJob: Job? = null
    private var bookPrepareJob: Job? = null
    private var preparedChapterKey: String? = null
    private var currentPlayer: MediaPlayer? = null

    /** Cache files currently being synthesized, so two preparers never double-bill one sentence. */
    private val inflightFiles = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    override val isReady: Boolean get() = backend.isConfigured()

    override val supportsWholeBookCache: Boolean get() = backend.supportsWholeBookCache

    init {
        // Probe backend capabilities (e.g. slow-engine detection) once; the
        // result flips supportsWholeBookCache and re-evaluates the cache UI.
        scope.launch {
            backend.refreshCapabilities()
            if (!shutdown) {
                mainHandler.post { listener.onCapabilitiesChanged() }
            }
        }
    }

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
        val total = chapter.utterances.size
        if (total == 0) {
            mainHandler.post { onComplete(true) }
            return
        }
        prepareJob = scope.launch {
            val semaphore = Semaphore(3)
            val failed = AtomicBoolean(false)
            val completed = AtomicInteger(0)
            val jobs = chapter.utterances.map { utterance ->
                async {
                    semaphore.withPermit {
                        if (failed.get() || shutdown) {
                            false
                        } else {
                            val ok = generateOne(book, chapter, utterance)
                            if (!ok) failed.set(true)
                            val done = completed.incrementAndGet()
                            mainHandler.post { onProgress(done, total) }
                            ok
                        }
                    }
                }
            }
            val allOk = jobs.all { it.await() }
            trimCache(book.id, chapter.chapterIndex)
            if (allOk && !shutdown) {
                mainHandler.post { onComplete(true) }
            } else if (!shutdown) {
                chapterFailed = true
                mainHandler.post { onComplete(false) }
            }
        }
    }

    override fun speak(text: String, rate: Float, utteranceId: String, voice: String?) {
        val parsed = parseUtteranceId(utteranceId)
        if (parsed == null) {
            mainHandler.post { listener.onError(utteranceId) }
            return
        }
        val effectiveVoice = voice?.takeIf { it.isNotBlank() } ?: backend.voiceFor(text)
        // 解析链（方案 §3）：预生成包（只读、永不淘汰）→ 缓存 → 现场合成。
        // 包命中时 file 已存在，waitForFileOrSynthesize 直接返回，不重新计费。
        val file = packFile(
            parsed.bookId, parsed.chapterIndex, parsed.sentenceIndex, parsed.segmentIndex, effectiveVoice
        ) ?: cacheFile(
            parsed.bookId, parsed.chapterIndex, parsed.sentenceIndex, parsed.segmentIndex, effectiveVoice
        )
        stopped = false
        scope.launch {
            val ready = waitForFileOrSynthesize(file, text, effectiveVoice)
            if (!ready || shutdown || stopped) {
                if (!ready && !stopped) mainHandler.post { listener.onError(utteranceId) }
                return@launch
            }
            if (!stopped) {
                // 第四轮审查 6-13：命中缓存并真的拿去播放时，记下「最近访问」，
                // 让 TtsAudioCache 的淘汰按真 LRU 走（音频包命中的文件不在缓存根下，会被忽略）。
                cache.markAccessed(file)
                mainHandler.post { play(file, rate, utteranceId) }
            }
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

    // internal 而非 private：M3 的「包命中不合成」由单测直接驱动这条分支（避免 25s 等待）。
    internal suspend fun generateOne(book: Book, chapter: TtsChapter, utterance: TtsUtterance): Boolean {
        val voice = voiceForSpeaker(utterance.speaker, utterance.text)?.takeIf { it.isNotBlank() }
            ?: backend.voiceFor(utterance.text)
        val file = cacheFile(book.id, chapter.chapterIndex, utterance.sentenceIndex, utterance.segmentIndex, voice)
        // 包已覆盖这句：不预生成、不写缓存（预生成会把整包再抄一遍，纯浪费）。
        if (packFile(book.id, chapter.chapterIndex, utterance.sentenceIndex, utterance.segmentIndex, voice) != null) {
            return true
        }
        if (file.exists() && file.length() > 0) return true
        return synthesizeOnce(file, utterance.text, voice, SYNTHESIS_RETRY_DELAY_MS)
    }

    /**
     * 同键合成只做一次（第四轮审查 6-4）。
     *
     * 预生成与现场播放（`speak`）原先各走一条路：只有预生成登记 `inflightFiles`，
     * 现场合成在等待超时后会与预生成**对同一文件重复合成**（云 TTS 双计费），
     * 并且两边并发写同一路径。现在两条路都从这里走，输者只等结果。
     *
     * 第四轮审查 6-6：单句失败**重试 1 次**（退避 [retryDelayMs] 默认 [SYNTHESIS_RETRY_DELAY_MS]
     * = 1s ≤ 2s），仍失败才降级。重试上限固定为 1 次是为了控制云计费 —— 网络抖动/服务端 5xx
     * 一次重试基本能救回，再多就是拿钱赌偶发故障。失败路径不写缓存（原子写会清掉 `.tmp`），
     * 所以重试不会读到半成品。
     *
     * [retryDelayMs] 做成参数是为了让单测不必真等 1 秒（产品调用点始终传默认常量）。
     */
    internal suspend fun synthesizeOnce(
        file: File,
        text: String,
        voice: String,
        retryDelayMs: Long = SYNTHESIS_RETRY_DELAY_MS
    ): Boolean {
        val key = file.absolutePath
        if (inflightFiles.putIfAbsent(key, true) != null) return awaitInflight(file, key)
        try {
            if (backend.synthesize(text, voice, file).isSuccess) return true
            // 第一次失败：退避后重试一次（同键仍在 inflight 中，不会被别的协程插进来重复合成）。
            delay(retryDelayMs)
            return backend.synthesize(text, voice, file).isSuccess
        } finally {
            inflightFiles.remove(key)
        }
    }

    /** 等同键在途合成结束；超时或停机则返回当前是否已可播放。 */
    private suspend fun awaitInflight(file: File, key: String): Boolean {
        val deadline = System.currentTimeMillis() + 60_000
        while (
            !shutdown && !chapterFailed && !stopped &&
            inflightFiles.containsKey(key) &&
            System.currentTimeMillis() < deadline
        ) {
            delay(100)
        }
        return file.exists() && file.length() > 0
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
            val total = chapters.sumOf { it.utterances.size }
            if (total == 0) {
                mainHandler.post { onComplete(true) }
                return@launch
            }
            val semaphore = Semaphore(3)
            val failed = AtomicBoolean(false)
            val completed = AtomicInteger(0)
            val jobs = chapters
                .flatMap { chapter -> chapter.utterances.map { utterance -> chapter to utterance } }
                .map { (chapter, utterance) ->
                    async {
                        semaphore.withPermit {
                            if (failed.get() || shutdown) {
                                false
                            } else {
                                val ok = generateOne(book, chapter, utterance)
                                if (!ok) failed.set(true)
                                val done = completed.incrementAndGet()
                                mainHandler.post { onProgress(done, total) }
                                ok
                            }
                        }
                    }
                }
            val allOk = jobs.all { it.await() }
            // 整书缓存是最容易把配额顶穿的入口：填完立刻整理。
            // M6：这里只保该书的「最近写入的那一章」，不再保护整本 —— 否则单本超上限就永不淘汰。
            trimCache(book.id, protectChapterIndex = null, protectNewestChapterOnly = true)
            if (!shutdown) {
                mainHandler.post { onComplete(allOk) }
            }
        }
        bookPrepareJob = job
    }

    /**
     * 按用户设定的上限整理缓存（方案 D2.3）。
     *
     * 只在**预生成刚结束**时调用 —— 那既是占用刚变大的时刻，也是不在播放中间的
     * 时刻。正在听的书（章）永不淘汰：删掉它会当场触发重新合成，云 TTS 是花钱的。
     * 上限为 0（不限）时 trimTo 直接不动。
     *
     * [protectNewestChapterOnly] 对应整书缓存路径（M6）：只保该书最近写入的一章，
     * 避免"保护整本 ⇒ 单本超上限即永不淘汰"。
     */
    private fun trimCache(
        bookId: String,
        protectChapterIndex: Int?,
        protectNewestChapterOnly: Boolean = false
    ) {
        if (shutdown) return
        val limitMb = runCatching { CloudTtsSettings.load(appContext).cacheLimitMb }.getOrDefault(0)
        if (limitMb <= 0) return
        runCatching {
            cache.trimTo(
                limitBytes = limitMb.toLong() * 1024L * 1024L,
                protectBookId = bookId,
                protectChapterIndex = protectChapterIndex,
                protectNewestChapterOnly = protectNewestChapterOnly
            )
        }
    }

    /**
     * 预生成音频包里的句子文件；null = 包没有这句。
     *
     * 键与缓存键同源（[TtsCacheKey.relativePath]），所以「包里的文件」与「现场合成的
     * 文件」永远指向同一句话；引擎/音色/管线版本任一不匹配，路径就不同，自然不命中
     * —— 不匹配的表现是**降级为现场合成**，不会放错音。
     */
    internal fun packFile(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        segmentIndex: Int,
        voice: String
    ): File? = packs.audioPackFile(
        bookId,
        TtsCacheKey.relativePath(chapterIndex, sentenceIndex, segmentIndex, engineTag, voice)
    )

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
        // in-flight preparation avoids synthesizing the same sentence twice —
        // but never indefinitely: a cross-chapter "previous" waits here for a
        // chapter whose file nobody is generating (BUG-015), and the whole-book
        // cache (bookPrepareJob) may also be producing this very file (OBS-04).
        val waitDeadline = System.currentTimeMillis() + 30_000
        while (
            !file.exists() &&
            System.currentTimeMillis() < waitDeadline &&
            (prepareJob?.isActive == true || bookPrepareJob?.isActive == true) &&
            !shutdown && !chapterFailed && !stopped
        ) {
            delay(100)
        }
        if (file.exists() && file.length() > 0) return true
        if (chapterFailed || shutdown || stopped) return false
        return synthesizeOnce(file, text, voice)
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
        segmentIndex: Int,
        voice: String
    ): File = cache.fileFor(bookId, chapterIndex, sentenceIndex, segmentIndex, voice, engineTag)

    /** utteranceId 的可定位坐标（缓存路径需要；attempt 只用于去重不参与）。 */
    private data class UtteranceRef(
        val bookId: String,
        val chapterIndex: Int,
        val sentenceIndex: Int,
        val segmentIndex: Int
    )

    private fun parseUtteranceId(id: String): UtteranceRef? {
        // 引擎生成的 utteranceId 是 5 段 "bookId:chapter:sentence:segment:attempt"
        // （见 TtsPlaybackEngine.utteranceIdFor）；末段 attempt 仅用于去重。
        val parts = id.split(":")
        if (parts.size < 5) return null
        val chapter = parts[1].toIntOrNull() ?: return null
        val sentence = parts[2].toIntOrNull() ?: return null
        val segment = parts[3].toIntOrNull() ?: return null
        return UtteranceRef(parts[0], chapter, sentence, segment)
    }
}

/**
 * 单句合成失败后的重试退避（第四轮审查 6-6）。
 *
 * 固定 1 次重试、退避 1s（≤ 口径要求的 2s）：网络抖动与服务端 5xx 基本一次能救回，
 * 再加次数就是拿云计费赌偶发故障。放在文件级是为了让单测直接引用同一个值。
 */
private const val SYNTHESIS_RETRY_DELAY_MS = 1_000L
