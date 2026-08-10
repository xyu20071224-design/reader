package com.linguareader.app.tts

import java.io.File

/**
 * Pluggable cloud synthesis backend (F-151). The cached chapter player only
 * depends on this interface, so Azure, an OpenAI-compatible self-hosted
 * server (Fish Speech / GPT-SoVITS adapters), or any future provider can be
 * swapped in from settings without touching the playback queue.
 */
interface CloudTtsBackend {
    val label: String

    fun isConfigured(): Boolean

    /** Synthesize one sentence into an audio file. */
    suspend fun synthesize(text: String, voice: String, outputFile: File): Result<Unit>

    /** Voice short name to use for [text] (per-sentence language routing). */
    fun voiceFor(text: String): String
}
