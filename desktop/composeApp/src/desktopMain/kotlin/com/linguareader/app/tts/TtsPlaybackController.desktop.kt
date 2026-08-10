package com.linguareader.app.tts

import com.linguareader.app.data.Book
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Desktop MVP stub: listening is excluded from the Windows first release. */
actual object TtsPlaybackController {
    private val stateFlow = MutableStateFlow(TtsPlaybackState())
    private val requests = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    actual val state: StateFlow<TtsPlaybackState> get() = stateFlow

    actual val chapterRequests: SharedFlow<Int> get() = requests

    actual fun startFromChapter(book: Book, chapterIndex: Int, sentenceIndex: Int) = Unit

    actual fun startFromSentence(book: Book, chapterIndex: Int, sentenceText: String) = Unit

    actual fun startFromBlockOffset(
        book: Book,
        chapterIndex: Int,
        blockText: String,
        blockOffset: Int
    ) = Unit

    actual fun toggle() = Unit

    actual fun pause() = Unit

    actual fun resume() = Unit

    actual fun next() = Unit

    actual fun previous() = Unit

    actual fun stop() = Unit

    actual fun setRate(rate: Float) = Unit

    actual fun onReaderChapterLoaded(bookId: String, chapterIndex: Int) = Unit

    actual fun onReaderChapterSelected(bookId: String, chapterIndex: Int) = Unit

    actual fun onReaderPositionChanged(bookId: String, chapterIndex: Int, blockText: String) = Unit
}
