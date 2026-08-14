package com.linguareader.app.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

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
    val isChinese: Boolean get() = language == "zh"
    val isEnglish: Boolean get() = language == "en"

    fun displayName(): String {
        val networkMark = if (isNetwork) "（网络）" else ""
        return "${locale.displayName} · $name$networkMark"
    }
}

/** Loads the voices of the currently selected system TTS engine. */
object SystemTtsVoices {
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

    private fun readVoices(tts: TextToSpeech): List<SystemVoiceInfo> =
        // `getVoices()` is a platform type that may be null (e.g. while the
        // engine is still loading its voice list). `getOrNull().orEmpty()`
        // collapses both a null return and a binder failure to an empty set
        // instead of letting null reach `.map` and crash.
        runCatching { tts.voices }.getOrNull().orEmpty()
            .map { SystemVoiceInfo(it.name, it.locale, it.isNetworkConnectionRequired) }
            .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
}
