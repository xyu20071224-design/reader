package com.linguareader.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline TTS backed by sherpa-onnx. Two models are held in memory — a Chinese
 * VITS voice and an English Piper voice — so mixed-language playback switches
 * instantly without reloading. Synthesis returns float PCM which is wrapped as
 * a WAV and played with [MediaPlayer] to map onto [TtsSynthesizerListener].
 */
class SherpaTtsSynthesizer(
    context: Context,
    private val listener: TtsSynthesizerListener,
    private val piperEnVoiceId: String = ""
) : TtsSynthesizer {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var ready = false

    private var zhTts: OfflineTts? = null
    private var enTts: OfflineTts? = null

    private var player: MediaPlayer? = null
    private var generation = 0

    init {
        scope.launch {
            try {
                // sherpa-onnx's Piper phonemizer needs espeak-ng-data as real
                // files (not asset entries), so copy it out to filesDir once.
                PiperAssets.ensureEspeakData(appContext)
                zhTts = OfflineTts(appContext.assets, zhConfig())
                // 英文：选中的音色可能是用户导入的（文件路径），加载失败时回退内置
                // Ryan，避免一个坏音色让整个 Piper 引擎（含中文）都不可用。
                val selected = PiperVoiceStore.resolve(appContext, piperEnVoiceId)
                enTts = PiperAssets.createEnglishTts(appContext, selected)
                    ?: PiperAssets.createEnglishTts(appContext, PiperVoiceCatalog.builtin)
                ready = true
                listener.onReady()
            } catch (failure: Throwable) {
                listener.onInitFailed(-1)
            }
        }
    }

    override val isReady: Boolean get() = ready

    override fun speak(text: String, rate: Float, utteranceId: String, voice: String?) {
        // Piper ignores per-utterance voice (only 2 bundled voices, auto
        // switched by language) — multi-voice is disabled for it (D2).
        if (!ready || text.isBlank()) return
        val gen = ++generation
        scope.launch {
            val tts = if (isChinese(text)) zhTts else enTts
            val audio = runCatching {
                tts?.generate(text, sid = 0, speed = rate.coerceIn(0.5f, 2f))
            }.getOrNull()
            if (gen != generation) return@launch
            if (audio == null || audio.samples.isEmpty()) {
                listener.onError(utteranceId)
                return@launch
            }
            val wav = writeWav(audio.samples, audio.sampleRate)
            if (gen != generation) {
                wav.delete()
                return@launch
            }
            play(wav, utteranceId, gen)
        }
    }

    override fun stop() {
        generation++
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
        runCatching { enTts?.release() }
        zhTts = null
        enTts = null
    }

    private fun zhConfig(): OfflineTtsConfig = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(
                model = "sherpa/vits-zh-hf-fanchen-wnj/vits-zh-hf-fanchen-wnj.onnx",
                tokens = "sherpa/vits-zh-hf-fanchen-wnj/tokens.txt",
                lexicon = "sherpa/vits-zh-hf-fanchen-wnj/lexicon.txt",
                dictDir = "sherpa/vits-zh-hf-fanchen-wnj/dict",
            ),
            numThreads = 2,
        ),
        ruleFsts = listOf(
            "sherpa/vits-zh-hf-fanchen-wnj/date.fst",
            "sherpa/vits-zh-hf-fanchen-wnj/number.fst",
            "sherpa/vits-zh-hf-fanchen-wnj/phone.fst",
            "sherpa/vits-zh-hf-fanchen-wnj/new_heteronym.fst",
        ).joinToString(","),
    )


    private fun isChinese(text: String): Boolean =
        text.any { char ->
            val code = char.code
            code in 0x4E00..0x9FFF ||
                code in 0x3400..0x4DBF ||
                code in 0xF900..0xFAFF ||
                code in 0x20000..0x2FA1F
        }


    private fun writeWav(samples: FloatArray, sampleRate: Int): File {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            pcm[i * 2] = (s and 0xff).toByte()
            pcm[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)

        val file = File.createTempFile("sherpa-", ".wav", appContext.cacheDir)
        file.outputStream().use { out ->
            out.write(header.array())
            out.write(pcm)
        }
        return file
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
                if (gen == generation) {
                    listener.onStart(utteranceId)
                    it.start()
                } else {
                    it.release()
                    file.delete()
                }
            }
            mp.setOnCompletionListener {
                if (gen == generation) listener.onDone(utteranceId)
                it.release()
                file.delete()
            }
            mp.setOnErrorListener { _, _, _ ->
                if (gen == generation) listener.onError(utteranceId)
                mp.release()
                file.delete()
                true
            }
            mp.prepareAsync()
        }
    }
}
