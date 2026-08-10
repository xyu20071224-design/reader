package com.linguareader.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Text-to-speech backend used by the book player.
 *
 * The player only talks to this interface. Today the implementation is the
 * Android system TTS engine; a cloud TTS (OpenAI TTS etc.) can later be
 * plugged in through [TtsSynthesizerFactory] without touching the queue,
 * progress or UI layers.
 */
interface TtsSynthesizer {
    val isReady: Boolean

    /** Queue [text] for synthesis; [utteranceId] is echoed back by callbacks. */
    fun speak(text: String, rate: Float, utteranceId: String)

    fun stop()

    fun shutdown()
}

interface TtsSynthesizerListener {
    fun onReady()
    fun onInitFailed(status: Int)
    fun onStart(utteranceId: String)
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String)
}

/** Android system TTS implementation, with per-utterance language detection. */
class SystemTtsSynthesizer(
    context: Context,
    private val listener: TtsSynthesizerListener
) : TtsSynthesizer {
    private var ready = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.let(listener::onStart)
                    }

                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let(listener::onDone)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let(listener::onError)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        utteranceId?.let(listener::onError)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        // A manual stop (pause/speed change) must not advance the
                        // sentence queue; the player re-speaks the current one.
                    }
                })
                listener.onReady()
            } else {
                listener.onInitFailed(status)
            }
        }
    }

    override val isReady: Boolean get() = ready

    override fun speak(text: String, rate: Float, utteranceId: String) {
        if (text.isBlank()) return
        tts.language = localeFor(text)
        tts.setSpeechRate(rate.coerceIn(0.5f, 2f))
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    override fun stop() {
        runCatching { tts.stop() }
    }

    override fun shutdown() {
        runCatching { tts.shutdown() }
    }

    private fun localeFor(text: String): Locale =
        if (text.any(::isHan)) Locale.CHINA else Locale.US

    private fun isHan(char: Char): Boolean {
        val code = char.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0xF900..0xFAFF ||
            code in 0x20000..0x2FA1F
    }
}

/**
 * Backend factory. Currently always returns the system engine; swapping in a
 * cloud engine later only requires a configuration check here.
 */
object TtsSynthesizerFactory {
    fun create(context: Context, listener: TtsSynthesizerListener): TtsSynthesizer =
        SystemTtsSynthesizer(context, listener)
}
