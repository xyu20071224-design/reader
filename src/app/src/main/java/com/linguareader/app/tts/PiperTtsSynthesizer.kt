package com.linguareader.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline Piper/ncnn synthesizer (bundled voices). Synthesis runs through the
 * bundled native lib and produces PCM that is wrapped as a WAV and played with
 * [MediaPlayer] so completion/error callbacks map cleanly onto the
 * [TtsSynthesizerListener] contract used by the playback service.
 *
 * The native engine holds a single model at a time; [speak] switches the model
 * when the text language changes (Chinese text -> zh model, otherwise -> en).
 */
class PiperTtsSynthesizer(
    context: Context,
    private val listener: TtsSynthesizerListener,
    private val initialLangId: Int = PiperNcnn.LANG_ZH
) : TtsSynthesizer {
    private val appContext = context.applicationContext
    private val piper = PiperNcnn()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var modelLoaded = false

    @Volatile
    private var currentLangId: Int? = null

    private var player: MediaPlayer? = null

    // Monotonic generation: a newer speak()/stop() invalidates any in-flight
    // synthesis or playback, so a stale callback never advances the queue.
    private var generation = 0

    init {
        scope.launch {
            modelLoaded = loadModel(initialLangId)
            if (modelLoaded) listener.onReady() else listener.onInitFailed(-1)
        }
    }

    override val isReady: Boolean get() = modelLoaded

    override fun speak(text: String, rate: Float, utteranceId: String) {
        if (!modelLoaded || text.isBlank()) return
        val gen = ++generation
        scope.launch {
            val langId = if (isChinese(text)) PiperNcnn.LANG_ZH else PiperNcnn.LANG_EN
            if (currentLangId != langId && !loadModel(langId)) {
                listener.onError(utteranceId)
                return@launch
            }
            // Piper's length_scale is inverse to playback rate: rate 2x -> 0.5.
            val lengthScale = 1.0 / rate.coerceIn(0.5f, 2f)
            val pcm = piper.synthesize(text, 0, lengthScale)
            if (gen != generation) return@launch
            if (pcm == null || pcm.isEmpty()) {
                listener.onError(utteranceId)
                return@launch
            }
            val wav = writeWav(pcm)
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
    }

    private fun loadModel(langId: Int): Boolean {
        val ok = piper.loadModel(appContext.assets, langId, 0)
        if (ok) currentLangId = langId
        return ok
    }

    private fun isChinese(text: String): Boolean =
        text.any { char ->
            val code = char.code
            code in 0x4E00..0x9FFF ||
                code in 0x3400..0x4DBF ||
                code in 0xF900..0xFAFF ||
                code in 0x20000..0x2FA1F
        }

    private fun writeWav(pcm: ByteArray): File {
        val sampleRate = 22050
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

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

        val file = File.createTempFile("piper-", ".wav", appContext.cacheDir)
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
