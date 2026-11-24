package com.linguareader.app.tts

import android.content.Context
import com.linguareader.shared.tts.ProsodyPlanner

/** Only the mode persists. Permission to bypass a paid audio pack is never persisted here. */
internal object ProsodySettings {
    private const val PREFERENCES = "tts_prosody"
    private const val MODE = "mode"

    fun mode(context: Context): ProsodyPlanner.Mode {
        val value = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(MODE, null)
        return ProsodyPlanner.Mode.entries.firstOrNull { it.name == value } ?: ProsodyPlanner.Mode.ORIGINAL
    }

    fun saveMode(context: Context, mode: ProsodyPlanner.Mode) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(MODE, mode.name).apply()
    }
}
