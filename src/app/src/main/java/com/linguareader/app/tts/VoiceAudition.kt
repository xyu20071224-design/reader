package com.linguareader.app.tts

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
    val isPlaying: Boolean get() = player != null

    fun stop() {
        val current = player
        player = null
        if (current != null) {
            runCatching {
                if (current.isPlaying) current.stop()
            }
            runCatching { current.release() }
        }
        runCatching { currentWav?.delete() }
        currentWav = null
        notifyFinished()
    }

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
