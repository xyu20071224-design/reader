package com.linguareader.app.tts

/** UI-visible state of the book player. */
data class TtsPlaybackState(
    val bookId: String? = null,
    val bookTitle: String = "",
    val chapterIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val sentenceCount: Int = 0,
    val currentSentence: String = "",
    val isPlaying: Boolean = false,
    val speechRate: Float = 1f,
    val engineLabel: String = "系统语音",
    val isPreparing: Boolean = false,
    val preparedCount: Int = 0,
    val preparedTotal: Int = 0,
    /** Exact DOM location of the sentence being read (-1 = not available). */
    val highlightBlockIndex: Int = -1,
    val highlightOffset: Int = 0,
    val highlightLength: Int = 0
) {
    val isActive: Boolean get() = bookId != null
}
