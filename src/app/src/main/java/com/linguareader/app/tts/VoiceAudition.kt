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
}
