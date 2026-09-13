package com.linguareader.shared.tts

/**
 * 播放已降级的原因（第四轮审查 6-6 第二项）。
 *
 * 报告原文的症状是「音色在章中途突变，**无用户可见的重试入口**」：云端合成失败后引擎会
 * 自动回退系统语音，若再连续失败还会静默 `pause()` —— 用户只知道"声音变了/突然停了"，
 * 不知道原因。本枚举让 [TtsPlaybackState.degradedReason] 把原因带出去，由壳层显示。
 */
enum class DegradedReason {
    /** 云 TTS 反复失败 → 已回退系统语音（离线可读，但音色不同）。 */
    FALLBACK_TO_SYSTEM,

    /** 单句连续失败达阈值 → 已暂停朗读（避免无声空转）。 */
    PAUSED_AFTER_ERRORS
}

/** UI-visible state of the book player. */
data class TtsPlaybackState(
    val bookId: String? = null,
    val chapterIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val sentenceCount: Int = 0,
    val currentSentence: String = "",
    val isPlaying: Boolean = false,
    val speechRate: Float = 1f,
    /** Whether the current engine can pre-generate the whole book. */
    val canCacheBook: Boolean = false,
    /** Whole-book cache (全书缓存) progress, across all chapters. */
    val isCachingBook: Boolean = false,
    val cachedSentences: Int = 0,
    val cachedTotal: Int = 0,
    /** Exact DOM location of the sentence being read (-1 = not available). */
    val highlightBlockIndex: Int = -1,
    val highlightOffset: Int = 0,
    val highlightLength: Int = 0,
    /**
     * 当前是否处于降级状态及原因；null = 一切正常（第四轮审查 6-6）。
     *
     * 由引擎在降级/暂停时置位，恢复播放（重新 `startPlayback`、切引擎、或重新朗读成功）时清空。
     */
    val degradedReason: DegradedReason? = null
) {
    val isActive: Boolean get() = bookId != null
}
