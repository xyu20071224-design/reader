package com.linguareader.app.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.linguareader.shared.tts.PlannedSpeechRequest
import com.linguareader.shared.tts.PlannedSpeechRunner
import com.linguareader.shared.tts.PlannedTtsSynthesizer
import com.linguareader.shared.tts.ProsodyConfiguration
import com.linguareader.shared.tts.ProsodyPlanner
import java.util.Locale

/**
 * Android system TTS implementation, with per-utterance language detection
 * and optional per-language voices picked by the user.
 */
class SystemTtsSynthesizer(
    context: Context,
    private val listener: TtsSynthesizerListener,
    private val zhVoice: String = "",
    private val enVoice: String = ""
) : TtsSynthesizer, PlannedTtsSynthesizer {
    private var ready = false
    private lateinit var tts: TextToSpeech
    private val voicesByName = mutableMapOf<String, Voice>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var plannedVoice: String? = null
    private val phraseRunner: PlannedSpeechRunner by lazy {
        PlannedSpeechRunner(
            listener = listener,
            play = { unit, childId ->
                if (!speakSystem(unit.utterance.text, unit.rate, childId, plannedVoice)) phraseRunner.onError(childId)
            },
            stopAudio = { tts.stop() },
            schedule = { millis, action ->
                val runnable = Runnable(action)
                mainHandler.postDelayed(runnable, millis)
                PlannedSpeechRunner.Cancellation { mainHandler.removeCallbacks(runnable) }
            }
        )
    }
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
                        utteranceId?.let { id -> mainHandler.post {
                            if (PlannedSpeechRunner.isChildId(id)) phraseRunner.onStart(id) else listener.onStart(id)
                        } }
                    }

                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { id -> mainHandler.post {
                            if (PlannedSpeechRunner.isChildId(id)) phraseRunner.onDone(id) else listener.onDone(id)
                        } }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let(::reportError)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        utteranceId?.let(::reportError)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        // A manual stop (pause/speed change) must not advance the
                        // sentence queue; the player re-speaks the current one.
                        utteranceId?.takeIf(PlannedSpeechRunner::isChildId)?.let(::reportError)
                    }
                })
                listener.onReady()
            } else {
                listener.onInitFailed(status)
            }
        }
    }

    override val isReady: Boolean get() = ready

    override fun speak(text: String, rate: Float, utteranceId: String, voice: String?) {
        if (text.isBlank()) return
        phraseRunner.stop()
        if (!speakSystem(text, rate, utteranceId, voice)) listener.onError(utteranceId)
    }

    override fun configureProsody(configuration: ProsodyConfiguration) {
        phraseRunner.stop()
    }

    override fun speakPlanned(request: PlannedSpeechRequest, utteranceId: String, voice: String?) {
        plannedVoice = voice
        listener.onProsody(utteranceId, ProsodyPlanner.Mode.PHRASES, null)
        phraseRunner.start(utteranceId, request.units)
    }

    private fun reportError(id: String) {
        mainHandler.post {
            if (PlannedSpeechRunner.isChildId(id)) phraseRunner.onError(id) else listener.onError(id)
        }
    }

    private fun speakSystem(text: String, rate: Float, utteranceId: String, voice: String?): Boolean {
        val locale = localeFor(text)
        val selected = voice?.takeIf { it.isNotBlank() }
            ?: if (locale == Locale.CHINA) zhVoice else enVoice
        val voiceObj = selected.takeIf { it.isNotBlank() }?.let { voicesByName[it] }
        val voiceUsable = voiceObj != null && !voiceObj.isNetworkConnectionRequired
        if (!voiceUsable || tts.setVoice(voiceObj) != TextToSpeech.SUCCESS) {
            // setLanguage 返回负值表示引擎缺少该语言音色数据（会静默无声），
            // 回退到引擎默认音色，避免整段静音。
            if (tts.setLanguage(locale) < 0) {
                tts.defaultVoice?.let { runCatching { tts.setVoice(it) } }
            }
        }
        tts.setSpeechRate(rate.coerceIn(0.5f, 2f))
        return tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) == TextToSpeech.SUCCESS
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
        phraseRunner.stop()
        runCatching { tts.stop() }
    }

    override fun shutdown() {
        phraseRunner.stop()
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
 * Backend factory. Returns the configured cloud engine (OpenAI-compatible
 * self-hosted or MiMo) when enabled (F-151), otherwise the Android
 * system TTS engine.
 */
object TtsSynthesizerFactory {
    fun create(
        context: Context,
        listener: TtsSynthesizerListener,
        /** Voice resolver shared with the playback engine (multi-voice M1/M3).
         *  Null falls back to the settings-only M1 resolver. */
        voiceResolver: ((String, String) -> String?)? = null
    ): TtsSynthesizer {
        val settings = CloudTtsSettings.load(context)
        // Multi-voice M1 fallback: narrator / dialogue voices from settings.
        // Both empty keeps the engine single-voice, identical to pre-M1.
        val voiceForSpeaker: (String, String) -> String? = voiceResolver ?: { speaker, _ ->
            (if (speaker == "narrator") settings.narratorVoice else settings.dialogueVoice)
                .takeIf { it.isNotBlank() }
        }
        return when {
            // Networked engines honour the master power switch; when it is off
            // they fall back to the local engine below (offline-first).
            settings.networkAiEnabled && settings.mode == TtsEngineMode.OPENAI_COMPAT && settings.isConfigured ->
                CloudTtsSynthesizer(context, OpenAiCompatTtsBackend(settings), listener, voiceForSpeaker)

            // MiMo-V2.5-TTS（小米云）：预置音色 / 设计音色 / 克隆音色三种 id 由
            // MiMoTtsBackend 按前缀分派模型，多角色管线的其余部分无感知。
            settings.networkAiEnabled && settings.mode == TtsEngineMode.MIMO && settings.isConfigured ->
                CloudTtsSynthesizer(context, MiMoTtsBackend(settings, context), listener, voiceForSpeaker)

            else -> SystemTtsSynthesizer(
                context,
                listener,
                zhVoice = settings.systemZhVoice,
                enVoice = settings.systemEnVoice
            )
        }
    }
}
