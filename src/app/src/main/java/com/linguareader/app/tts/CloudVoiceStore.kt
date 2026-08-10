package com.linguareader.app.tts

import android.content.Context
import org.json.JSONArray

/** Caches the voice list fetched from the user's Azure region. */
object CloudVoiceStore {
    private const val PREFS = "cloud_tts_voices"
    private const val KEY_VOICES = "voices"

    fun load(context: Context): List<AzureVoice> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VOICES, null)
            ?: return emptyList()
        return runCatching { AzureVoice.parse(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun save(context: Context, voices: List<AzureVoice>) {
        val array = JSONArray()
        voices.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VOICES, array.toString())
            .apply()
    }
}
