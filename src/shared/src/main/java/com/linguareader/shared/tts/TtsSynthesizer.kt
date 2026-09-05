package com.linguareader.shared.tts

import com.linguareader.shared.data.Book

/**
 * 播放器与合成后端之间的接口面（桌面迁移 M2 刀9 自 TtsSynthesizer.kt 逐字下沉；
 * Android 的 SystemTtsSynthesizer 实现与 Cloud/MiMo 后端留在 :app，实现本接口）。
 *
 * The player only talks to this interface. Today the implementation is the
 * Android system TTS engine; a cloud TTS (OpenAI TTS etc.) can later be
 * plugged in through [TtsSynthesizerFactory] without touching the queue,
 * progress or UI layers.
 */
interface TtsSynthesizer {
    val isReady: Boolean

    /** Queue [text] for synthesis; [utteranceId] is echoed back by callbacks.
     *  [voice] optionally overrides the engine's default voice per utterance
     *  (multi-voice M1: narrator / dialogue); null keeps the configured one. */
    fun speak(text: String, rate: Float, utteranceId: String, voice: String? = null)

    fun stop()

    fun shutdown()
}

interface TtsSynthesizerListener {
    fun onReady()
    fun onInitFailed(status: Int)
    fun onStart(utteranceId: String)
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String)

    /** Fired after an async backend capability probe (e.g. slow-engine
     *  detection for 全书缓存) finished; the engine re-evaluates what the UI
     *  may offer. Default no-op. */
    fun onCapabilitiesChanged() {}
}

/**
 * Per-chapter pre-generation capability: pre-generate one chapter's audio
 * ahead of playback (slow engines keep the queue fed this way).
 */
interface ChapterTtsPreparer {
    fun prepareChapter(
        book: Book,
        chapter: TtsChapter,
        onProgress: (prepared: Int, total: Int) -> Unit,
        onComplete: (success: Boolean) -> Unit
    )
}

/**
 * Whole-book pre-generation capability (F-151 全书缓存). Implemented by
 * synthesizers that can pre-generate the entire book; the playback engine
 * feature-detects via `is BookTtsPreparer`.
 */
interface BookTtsPreparer {
    /**
     * Whether whole-book pre-generation is enabled for this synthesizer.
     * Default true; slow engines (IndexTTS via OpenAI-compatible backend)
     * report false after the capability probe so the UI hides the button.
     */
    val supportsWholeBookCache: Boolean get() = true

    fun prepareBook(
        book: Book,
        chapterCount: Int,
        chapterProvider: suspend (Int) -> TtsChapter,
        onProgress: (done: Int, total: Int) -> Unit,
        onComplete: (success: Boolean) -> Unit
    )
}
