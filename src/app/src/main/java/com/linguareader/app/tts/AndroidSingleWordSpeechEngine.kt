package com.linguareader.app.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.linguareader.app.BuildConfig
import java.util.Locale

/** Serializes the platform's initialization/worker callbacks with UI requests and disposal. */
internal class AndroidSingleWordSpeechEngine(context: Context) : SingleWordSpeaker.Engine {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var closed = false

    override fun initialize(listener: SingleWordSpeaker.Listener) {
        check(tts == null && !closed)
        val created = TextToSpeech(appContext) { status ->
            // Posting also ensures tts is assigned before even an immediate init callback is used.
            deliver { listener.onInitialized(status) }
        }
        tts = created
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.let { id -> deliver { listener.onStart(id) } }
            }
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { id -> deliver { listener.onDone(id) } }
            }
            @Deprecated("Legacy platform callback")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { id -> deliver { listener.onError(id) } }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { id -> deliver { listener.onError(id, errorCode) } }
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { id -> deliver { listener.onStop(id) } }
            }
        })
    }

    override fun configureEnglish(): Int = tts?.setLanguage(Locale.US) ?: TextToSpeech.ERROR

    override fun speak(text: String, utteranceId: String): Int =
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) ?: TextToSpeech.ERROR

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        val previous = tts
        tts = null
        previous?.shutdown()
    }

    private fun deliver(action: () -> Unit) {
        handler.post { if (!closed) action() }
    }
}

/** Debug diagnostics are bounded and never include the spoken word or phrase. */
internal fun createAndroidSingleWordSpeaker(
    context: Context,
    onFailure: (SingleWordSpeaker.Failure) -> Unit
): SingleWordSpeaker {
    var remainingEvents = 1_024
    return SingleWordSpeaker(
        engineFactory = { AndroidSingleWordSpeechEngine(context) },
        onFailure = onFailure,
        onEvent = { event ->
            if (BuildConfig.DEBUG && remainingEvents > 0) {
                remainingEvents--
                Log.d("SingleWordSpeech", event.toString())
            }
        }
    )
}
