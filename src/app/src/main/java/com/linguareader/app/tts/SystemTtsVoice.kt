package com.linguareader.app.tts

import android.content.Context
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
            if (status == TextToSpeech.SUCCESS) {
                val voices = runCatching { created.voices }
                    .getOrDefault(emptySet())
                    .map { SystemVoiceInfo(it.name, it.locale, it.isNetworkConnectionRequired) }
                    .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
                onResult(voices)
            } else {
                onResult(emptyList())
            }
            runCatching { created.shutdown() }
        }
    }
}
