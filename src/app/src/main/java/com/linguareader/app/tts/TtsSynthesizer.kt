package com.linguareader.app.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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

/**
 * Android system TTS implementation, with per-utterance language detection
 * and optional per-language voices picked by the user.
 */
class SystemTtsSynthesizer(
    context: Context,
    private val listener: TtsSynthesizerListener,
    private val zhVoice: String = "",
    private val enVoice: String = ""
) : TtsSynthesizer {
    private var ready = false
    private lateinit var tts: TextToSpeech
    private val voicesByName = mutableMapOf<String, Voice>()
    // Guards populateVoices() so a slow/empty engine (getVoices() returns
    // nothing) does not re-query `tts.voices` on every single utterance — that
    // per-sentence call is what turns "no voice found" into "extremely slow".
    private var voicesLoaded = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                populateVoices()
                if (voicesByName.isEmpty()) {
                    // Some engines populate their voice list slightly after the
                    // init callback fires (getVoices() then returns null/empty).
                    // Retry once so a selected voice is not silently ignored on
                    // the first utterance. This mirrors SystemTtsVoices.load().
                    Handler(Looper.getMainLooper()).postDelayed({ populateVoices(force = true) }, 300)
                }
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
        val locale = localeFor(text)
        val configured = if (locale == Locale.CHINA) zhVoice else enVoice
        val voice = configured.takeIf { it.isNotBlank() }?.let { voicesByName[it] }
        val voiceUsable = voice != null && !voice.isNetworkConnectionRequired
        if (!voiceUsable || tts.setVoice(voice) != TextToSpeech.SUCCESS) {
            // setLanguage 返回负值表示引擎缺少该语言音色数据（会静默无声），
            // 回退到引擎默认音色，避免整段静音。
            if (tts.setLanguage(locale) < 0) {
                tts.defaultVoice?.let { runCatching { tts.setVoice(it) } }
            }
        }
        tts.setSpeechRate(rate.coerceIn(0.5f, 2f))
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private fun populateVoices(force: Boolean = false) {
        // Only attempt once: engines whose getVoices() stays empty would
        // otherwise re-query `tts.voices` (potentially a slow binder call) on
        // every utterance. The init path passes force=true for its one delayed
        // retry, because the voice list may only become available right after
        // the init callback has fired.
        if (voicesLoaded && !force) return
        voicesLoaded = true
        // `getVoices()` is a platform type that may be null; collapsing
        // null/binder failures to an empty set keeps `voicesByName` populated
        // so a selected voice can actually be applied via `setVoice` in `speak`.
        runCatching { tts.voices }.getOrNull().orEmpty()
            .filterNot { it.isNetworkConnectionRequired }
            .forEach { voicesByName[it.name] = it }
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
 * Backend factory. Returns the configured cloud engine (Azure, Volcano or
 * OpenAI-compatible self-hosted) when enabled (F-151), otherwise the Android
 * system TTS engine.
 */
object TtsSynthesizerFactory {
    fun create(context: Context, listener: TtsSynthesizerListener): TtsSynthesizer {
        val settings = CloudTtsSettings.load(context)
        return when {
            settings.mode == TtsEngineMode.PIPER && settings.isConfigured ->
                SherpaTtsSynthesizer(context, listener)

            // Networked engines honour the master power switch; when it is off
            // they fall back to the local engines below (offline-first).
            settings.networkAiEnabled && settings.mode == TtsEngineMode.AZURE && settings.isConfigured ->
                CloudTtsSynthesizer(context, AzureTtsBackend(settings, context), listener)

            settings.networkAiEnabled && settings.mode == TtsEngineMode.OPENAI_COMPAT && settings.isConfigured ->
                CloudTtsSynthesizer(context, OpenAiCompatTtsBackend(settings), listener)

            settings.networkAiEnabled && settings.mode == TtsEngineMode.VOLC && settings.isConfigured ->
                CloudTtsSynthesizer(context, VolcanoTtsBackend(settings), listener)

            else -> SystemTtsSynthesizer(
                context,
                listener,
                zhVoice = settings.systemZhVoice,
                enVoice = settings.systemEnVoice
            )
        }
    }
}
