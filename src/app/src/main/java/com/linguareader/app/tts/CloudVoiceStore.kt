package com.linguareader.app.tts

import android.content.Context
import com.linguareader.app.data.AppPrefs
import org.json.JSONArray

/** Caches the voice list fetched from the user's Azure region. */
object CloudVoiceStore {
    fun load(context: Context): List<AzureVoice> {
        val raw = AppPrefs.get(context).cloudTtsVoices.voices
            ?: return emptyList()
        return runCatching { AzureVoice.parse(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun save(context: Context, voices: List<AzureVoice>) {
        val array = JSONArray()
        voices.forEach { array.put(it.toJson()) }
        AppPrefs.get(context).cloudTtsVoices.putVoices(array.toString())
    }
}
