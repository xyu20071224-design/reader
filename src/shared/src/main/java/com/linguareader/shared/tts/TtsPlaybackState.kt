package com.linguareader.shared.tts

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
    val highlightLength: Int = 0
) {
    val isActive: Boolean get() = bookId != null
}
