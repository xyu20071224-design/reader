package com.linguareader.app.tts

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * One-shot voice audition for the multi-voice settings panel (§8.2「试听」).
 *
 * Synthesizes a short sample line with an explicit voice id on the configured
 * cloud engine and plays it; the previous audition is always stopped first, so
 * tapping several rows quickly cannot stack overlapping audio.
 */
object VoiceAudition {
    @Volatile
    private var player: MediaPlayer? = null

    /** Dedicated engine instance while a SYSTEM-mode audition is speaking. */
    @Volatile
    private var systemTts: TextToSpeech? = null

    /** Piper 试听的临时 WAV，停止/播完都要删。 */
    @Volatile
    private var currentWav: File? = null

    /** 播放结束/被打断时回调，让面板把「停止」还原成「试听」。 */
    @Volatile
    private var onFinished: (() -> Unit)? = null

    suspend fun play(
        context: Context,
        settings: CloudTtsSettings,
        voiceId: String,
        text: String,
        onFinished: () -> Unit = {}
    ): Result<Unit> {
        val appContext = context.applicationContext
        // M5b: the annotation dialog auditions per system voice, so the system
        // engine speaks directly instead of going through a cloud backend.
        if (settings.mode == TtsEngineMode.SYSTEM) {
            return playOnSystemEngine(appContext, voiceId, text, onFinished)
        }
        if (settings.mode == TtsEngineMode.PIPER) {
            return playPiper(appContext, voiceId, text, onFinished)
        }
        val backend = backendFor(appContext, settings)
            ?: return Result.failure(IllegalStateException("当前引擎不支持试听"))
        val voice = voiceId.trim().ifBlank { backend.voiceFor(text) }
        val file = File(appContext.cacheDir, "voice_audition.mp3")
        val synthesized = withContext(Dispatchers.IO) {
            file.delete()
            backend.synthesize(text, voice, file)
        }
        synthesized.exceptionOrNull()?.let { return Result.failure(it) }
        return withContext(Dispatchers.Main) {
            runCatching {
                stop()
                val created = MediaPlayer()
                created.setDataSource(file.absolutePath)
                created.setOnCompletionListener { finished ->
                    finished.release()
                    if (player === finished) {
                        player = null
                        notifyFinished()
                    }
                }
                created.setOnErrorListener { _, _, _ ->
                    stop()
                    true
                }
                created.prepare()
                created.start()
                player = created
                this@VoiceAudition.onFinished = onFinished
            }.onFailure { onFinished() }
        }
    }

    /** 是否正在播放试听（面板用它显示「停止」）。 */
    val isPlaying: Boolean get() = player != null || systemTts != null

    fun stop() {
        val current = player
        player = null
        if (current != null) {
            runCatching {
                if (current.isPlaying) current.stop()
            }
            runCatching { current.release() }
        }
        stopSystemEngine()
        runCatching { currentWav?.delete() }
        currentWav = null
        notifyFinished()
    }

    /**
     * System-engine audition (M5b): speaks the sample line with a dedicated
     * [TextToSpeech] instance instead of synthesizing to a file. A previous
     * audition is always stopped first so two rows cannot overlap. Per-voice
     * `setVoice` with the same fallback chain as playback
     * ([SystemTtsSynthesizer.speak]) doubles as the §13.7 mitigation probe:
     * the user can hear whether an OEM engine actually honours `setVoice`.
     */
    private suspend fun playOnSystemEngine(
        appContext: Context,
        voiceId: String,
        text: String,
        onFinished: () -> Unit
    ): Result<Unit> = withContext(Dispatchers.Main) {
        stop()
        val engine: TextToSpeech? =
            suspendCancellableCoroutine { continuation ->
                lateinit var created: TextToSpeech
                created = TextToSpeech(appContext) { status ->
                    continuation.resume(if (status == TextToSpeech.SUCCESS) created else null)
                }
            }
        if (engine == null) {
            return@withContext Result.failure(IllegalStateException("系统语音初始化失败"))
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finishSystemAudition(engine)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finishSystemAudition(engine)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?, errorCode: Int) =
                finishSystemAudition(engine)

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                // stop() already cleaned up and notified; nothing to do here.
            }
        })

        // Same fallback chain as playback: setVoice first, then setLanguage,
        // then whatever default the engine picks — a failed setVoice must not
        // end in silence.
        val wanted = voiceId.trim()
        val target = runCatching { engine.voices }.getOrNull().orEmpty()
            .firstOrNull { it.name == wanted && !it.isNetworkConnectionRequired }
        val applied = target != null &&
            runCatching { engine.setVoice(target) }.getOrNull() == TextToSpeech.SUCCESS
        if (!applied) {
            runCatching {
                engine.setLanguage(
                    if (TtsLanguage.of(text) == TtsLanguage.CHINESE) Locale.CHINA else Locale.US
                )
            }
        }
        engine.setSpeechRate(1f)
        systemTts = engine
        // Register through the field (like the cloud path) so stop() also
        // reaches the UI callback of the audition being replaced.
        this@VoiceAudition.onFinished = onFinished
        val queued = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, SYSTEM_AUDITION_UTTERANCE)
        }.getOrNull()
        if (queued == null || queued < 0) {
            stopSystemEngine()
            return@withContext Result.failure(IllegalStateException("试听启动失败"))
        }
        Result.success(Unit)
    }

    private fun finishSystemAudition(engine: TextToSpeech) {
        // Identity check: a newer audition may already own the slot.
        if (systemTts === engine) {
            systemTts = null
            runCatching { engine.shutdown() }
            notifyFinished()
        }
    }

    private fun stopSystemEngine() {
        val engine = systemTts
        systemTts = null
        if (engine != null) {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }

    private const val SYSTEM_AUDITION_UTTERANCE = "voice_audition_system"

    private fun notifyFinished() {
        val callback = onFinished
        onFinished = null
        callback?.invoke()
    }

    private fun backendFor(context: Context, settings: CloudTtsSettings): CloudTtsBackend? =
        when (settings.mode) {
            TtsEngineMode.AZURE -> AzureTtsBackend(settings, context)
            TtsEngineMode.VOLC -> VolcanoTtsBackend(settings)
            TtsEngineMode.OPENAI_COMPAT -> OpenAiCompatTtsBackend(settings)
            TtsEngineMode.MIMO -> MiMoTtsBackend(settings, context)
            else -> null
        }

    /**
     * Piper 本地试听（D3）：按文本语言路由到中文模型或指定英文音色，现场加载、
     * 合成、播放后即释放——试听是低频操作，不值得为它常驻一个实例。
     */
    private suspend fun playPiper(
        appContext: Context,
        voiceId: String,
        text: String,
        onFinished: () -> Unit
    ): Result<Unit> {
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                val chinese = text.any { char -> char.code in 0x4E00..0x9FFF }
                val tts = if (chinese) {
                    PiperAssets.createChineseTts(appContext)
                } else {
                    val resolved = PiperVoiceStore.resolve(appContext, voiceId.trim())
                    PiperAssets.createEnglishTts(appContext, resolved)
                        ?: PiperAssets.createEnglishTts(appContext, PiperVoiceCatalog.builtin)
                } ?: error("音色加载失败")
                try {
                    val audio = tts.generate(text, sid = 0, speed = 1f)
                    check(audio.samples.isNotEmpty()) { "合成结果为空" }
                    PiperAssets.writeWav(audio.samples, audio.sampleRate, appContext.cacheDir)
                } finally {
                    runCatching { tts.release() }
                }
            }
        }
        val wav = prepared.getOrElse { failure -> onFinished(); return Result.failure(failure) }
        currentWav = wav
        return withContext(Dispatchers.Main) {
            runCatching {
                stop()
                val created = MediaPlayer()
                created.setDataSource(wav.absolutePath)
                created.setOnCompletionListener { finished ->
                    finished.release()
                    wav.delete()
                    if (player === finished) {
                        player = null
                        notifyFinished()
                    }
                }
                created.setOnErrorListener { _, _, _ ->
                    stop()
                    true
                }
                created.prepare()
                created.start()
                player = created
                this@VoiceAudition.onFinished = onFinished
            }.onFailure {
                runCatching { wav.delete() }
                onFinished()
            }
        }
    }
}
