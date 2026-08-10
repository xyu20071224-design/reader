package com.linguareader.app.tts

import com.linguareader.app.data.Book
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing facade over the platform book player. On desktop the MVP ships
 * without listening, so the desktop actual is a no-op stub.
 */
expect object TtsPlaybackController {
    val state: StateFlow<TtsPlaybackState>

    val chapterRequests: SharedFlow<Int>

    fun startFromChapter(book: Book, chapterIndex: Int, sentenceIndex: Int)

    fun startFromSentence(book: Book, chapterIndex: Int, sentenceText: String)

    fun startFromBlockOffset(book: Book, chapterIndex: Int, blockText: String, blockOffset: Int)

    fun toggle()

    fun pause()

    fun resume()

    fun next()

    fun previous()

    fun stop()

    fun setRate(rate: Float)

    fun onReaderChapterLoaded(bookId: String, chapterIndex: Int)

    fun onReaderChapterSelected(bookId: String, chapterIndex: Int)

    fun onReaderPositionChanged(bookId: String, chapterIndex: Int, blockText: String)
}
