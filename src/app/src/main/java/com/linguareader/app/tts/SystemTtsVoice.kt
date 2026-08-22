package com.linguareader.app.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One voice exposed by the Android system TTS engine (API 21+).
 *
 * The book player lets the user pick a Chinese and an English voice; an empty
 * selection means "follow the engine's default voice for that language".
 */
data class SystemVoiceInfo(
    val name: String,
    val locale: Locale,
    val isNetwork: Boolean = false
) {
    val language: String get() = locale.language.lowercase()
    // Domestic TTS engines often report ISO 639-3 codes (cmn = Mandarin,
    // yue = Cantonese, etc.) instead of the ISO 639-1 zh/en codes, so a plain
    // `language == "zh"` check would filter every voice out and leave the
    // dropdowns with only "跟随系统默认".
    val isChinese: Boolean get() = language in CHINESE_LANGUAGE_CODES
    val isEnglish: Boolean get() = language in ENGLISH_LANGUAGE_CODES

    /**
     * Assigner language for the multi-voice hard filter (PLAN-MULTI-VOICE
     * §13.2): "zh"/"en" when the engine's locale code is recognised (including
     * the ISO 639-3 and vendor codes above), blank (= multilingual, no language
     * constraint) for anything else.
     */
    val assignerLanguage: String
        get() = when {
            isChinese -> TtsLanguage.CHINESE
            isEnglish -> TtsLanguage.ENGLISH
            else -> ""
        }

    fun displayName(): String {
        val networkMark = if (isNetwork) "（网络）" else ""
        return "${locale.displayName} · $name$networkMark"
    }

    companion object {
        private val CHINESE_LANGUAGE_CODES = setOf(
            "zh", "cmn", "yue", "hak", "wuu", "nan",
            "cjy", "cpx", "gan", "hsn", "lzh", "czh", "czo", "mnp",
            // OPPO/ColorOS "TTS Accessibility Engine" reports Chinese voices
            // with the non-standard code "chn" (and English with "usa").
            "chn"
        )
        private val ENGLISH_LANGUAGE_CODES = setOf("en", "eng", "usa")
    }
}

/**
 * Engine detection for the SYSTEM-mode guidance panel (PLAN-MULTI-VOICE §13.5,
 * M5c): which of the three guidance states applies, and whether Google TTS is
 * installed. Pure queries — never switches anything automatically.
 */
object SystemTtsEngines {

    const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

    /** 三态引导：当前就是 Google TTS / 已装未启用 / 未安装。 */
    enum class Guide { RECOMMENDED, SWITCH_AVAILABLE, NOT_INSTALLED }

    fun guideState(currentEngine: String, googleTtsInstalled: Boolean): Guide = when {
        currentEngine == GOOGLE_TTS_PACKAGE -> Guide.RECOMMENDED
        googleTtsInstalled -> Guide.SWITCH_AVAILABLE
        else -> Guide.NOT_INSTALLED
    }

    fun isGoogleTtsInstalled(context: Context): Boolean =
        isInstalled(context, GOOGLE_TTS_PACKAGE)

    fun isInstalled(context: Context, packageName: String): Boolean =
        packageName.isNotBlank() && runCatching {
            context.applicationContext.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
}

/** Loads the voices of the currently selected system TTS engine. */
object SystemTtsVoices {

    /**
     * The engine package a default-constructed [TextToSpeech] binds to (the
     * user's selection in system settings). Synchronous — no connection needed.
     * Blank when the device never configured an engine.
     */
    fun currentEngine(context: Context): String =
        runCatching {
            Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.TTS_DEFAULT_SYNTH
            )
        }.getOrNull().orEmpty().trim()

    fun load(context: Context, onResult: (List<SystemVoiceInfo>) -> Unit) {
        lateinit var created: TextToSpeech
        created = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                onResult(emptyList())
                runCatching { created.shutdown() }
                return@TextToSpeech
            }

            fun deliver() {
                onResult(readVoices(created))
                runCatching { created.shutdown() }
            }

            val first = readVoices(created)
            if (first.isNotEmpty()) {
                deliver()
            } else {
                // Some engines populate their voice list slightly after the
                // init callback fires (getVoices() then returns null/empty the
                // first time); retry once before reporting "no voices".
                Handler(Looper.getMainLooper()).postDelayed({ deliver() }, 300)
            }
        }
    }

    /** Suspend variant of [load]. */
    suspend fun probe(context: Context): List<SystemVoiceInfo> =
        suspendCancellableCoroutine { continuation ->
            load(context) { continuation.resume(it) }
        }

    private fun readVoices(tts: TextToSpeech): List<SystemVoiceInfo> =
        // `getVoices()` is a platform type that may be null (e.g. while the
        // engine is still loading its voice list). `getOrNull().orEmpty()`
        // collapses both a null return and a binder failure to an empty set
        // instead of letting null reach `.map` and crash.
        runCatching { tts.voices }.getOrNull().orEmpty()
            .map { SystemVoiceInfo(it.name, it.locale, it.isNetworkConnectionRequired) }
            // Offline playback must never hand the user a voice that requires a
            // network download: selecting such a voice makes the engine silently
            // wait to fetch it (or just stay silent), which users report as
            // "no sound + each sentence advances extremely slowly". Skip them so
            // the dropdowns only offer already-available voices.
            .filterNot { it.isNetwork }
            .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
}
