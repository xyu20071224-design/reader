package com.linguareader.app.tts

import java.io.File

/**
 * Pluggable cloud synthesis backend (F-151). The cached chapter player only
 * depends on this interface, so an OpenAI-compatible self-hosted server
 * (Fish Speech / GPT-SoVITS adapters), MiMo, or any future provider can be
 * swapped in from settings without touching the playback queue.
 */
interface CloudTtsBackend {
    fun isConfigured(): Boolean

    /** Synthesize one sentence into an audio file. */
    suspend fun synthesize(text: String, voice: String, outputFile: File): Result<Unit>

    /** Voice short name to use for [text] (per-sentence language routing). */
    fun voiceFor(text: String): String

    /**
     * Whether whole-book pre-generation (全书缓存) is worth offering for this
     * backend. Slow engines (e.g. IndexTTS, ~1-14s per sentence) are exempt:
     * a whole book would take tens of hours. Fast backends (Kokoro, MiMo)
     * keep the default true.
     */
    val supportsWholeBookCache: Boolean get() = true

    /**
     * Optional async capability probe, called once when the synthesizer is
     * created. Default no-op; OpenAI-compatible backends query /v1/models to
     * identify slow engines like IndexTTS.
     */
    suspend fun refreshCapabilities() {}
}
