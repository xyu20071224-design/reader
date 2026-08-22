package com.linguareader.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Offline TTS backed by sherpa-onnx. The Chinese VITS voice is held as one
 * fixed instance; English Piper voices live in an LRU pool ([LruInstancePool],
 * D3) so multi-voice playback can switch per sentence without reloading —
 * synthesis returns float PCM which is wrapped as a WAV and played with
 * [MediaPlayer] to map onto [TtsSynthesizerListener].
 */
class SherpaTtsSynthesizer(
    context: Context,
    private val listener: TtsSynthesizerListener,
    private val piperEnVoiceId: String = ""
) : TtsSynthesizer {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 池清理走独立 scope：shutdown 已取消 [scope]，再用它 launch 不会执行。 */
    private val poolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 英文实例驻留上限（含默认音色）；每个 medium 模型约 60-110MB。 */
    private val maxEnglishInstances = 4

    @Volatile
    private var ready = false

    private var zhTts: OfflineTts? = null
    private val enPool = LruInstancePool<String, OfflineTts>(
        capacity = maxEnglishInstances,
        create = { id ->
            // resolve 对未知 id 回退内置 Ryan，所以坏映射不会让整句无声。
            PiperAssets.createEnglishTts(appContext, PiperVoiceStore.resolve(appContext, id))
        },
        destroy = { tts -> runCatching { tts.release() } }
    )

    private var player: MediaPlayer? = null
    private val generationLock = Any()
    private var generation = 0

    init {
        scope.launch {
            try {
                // sherpa-onnx's Piper phonemizer needs espeak-ng-data as real
                // files (not asset entries), so copy it out to filesDir once.
                PiperAssets.ensureEspeakData(appContext)
                zhTts = OfflineTts(appContext.assets, PiperAssets.chineseConfig())
                // 默认英文音色常驻池内（pin 持有一份引用），既保证单音色路径
                // 零加载延迟，也不会被后续角色音色挤出去。
                enPool.pin(defaultEnglishId)
                ready = true
                listener.onReady()
            } catch (failure: Throwable) {
                listener.onInitFailed(-1)
            }
        }
    }

    override val isReady: Boolean get() = ready

    private val defaultEnglishId: String =
        PiperVoiceStore.resolve(appContext, piperEnVoiceId).id

    override fun speak(text: String, rate: Float, utteranceId: String, voice: String?) {
        if (!ready || text.isBlank()) return
        val gen = nextGeneration()
        scope.launch {
            if (isChinese(text)) {
                // 中文只有一个内置模型，角色映射里的中文条目在这里自然汇合。
                synthesize(zhTts, text, rate, utteranceId, gen)
            } else {
                val requested = voice?.trim()?.takeUnless { it.isEmpty() } ?: defaultEnglishId
                val tts = enPool.acquire(requested) ?: return@launch synthesize(
                    enPool.acquire(defaultEnglishId), text, rate, utteranceId, gen
                )
                try {
                    synthesize(tts, text, rate, utteranceId, gen)
                } finally {
                    enPool.release(requested)
                }
            }
        }
    }

    private fun nextGeneration(): Int = synchronized(generationLock) { ++generation }

    private suspend fun synthesize(
        tts: OfflineTts?,
        text: String,
        rate: Float,
        utteranceId: String,
        gen: Int
    ) {
        val audio = runCatching {
            tts?.generate(text, sid = 0, speed = rate.coerceIn(0.5f, 2f))
        }.getOrNull()
        if (gen != currentGeneration()) return
        if (audio == null || audio.samples.isEmpty()) {
            listener.onError(utteranceId)
            return
        }
        val wav = PiperAssets.writeWav(audio.samples, audio.sampleRate, appContext.cacheDir)
        if (gen != currentGeneration()) {
            wav.delete()
            return
        }
        play(wav, utteranceId, gen)
    }

    private fun currentGeneration(): Int = synchronized(generationLock) { generation }

    override fun stop() {
        nextGeneration()
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
    }

    override fun shutdown() {
        stop()
        scope.cancel()
        runCatching { zhTts?.release() }
        zhTts = null
        poolScope.launch { enPool.close() }
    }

    private fun isChinese(text: String): Boolean =
        text.any { char ->
            val code = char.code
            code in 0x4E00..0x9FFF ||
                code in 0x3400..0x4DBF ||
                code in 0xF900..0xFAFF ||
                code in 0x20000..0x2FA1F
        }


    private fun play(file: File, utteranceId: String, gen: Int) {
        runCatching {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener {
                if (gen == currentGeneration()) {
                    listener.onStart(utteranceId)
                    it.start()
                } else {
                    it.release()
                    file.delete()
                }
            }
            mp.setOnCompletionListener {
                if (gen == currentGeneration()) listener.onDone(utteranceId)
                it.release()
                file.delete()
            }
            mp.setOnErrorListener { _, _, _ ->
                if (gen == currentGeneration()) listener.onError(utteranceId)
                mp.release()
                file.delete()
                true
            }
            mp.prepareAsync()
        }
    }
}
