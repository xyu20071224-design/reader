package com.linguareader.app.tts

import com.linguareader.app.data.Book
import com.linguareader.app.platform.androidAppContext
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin UI-facing facade over [TtsPlaybackService]. The reader screen talks to
 * this object; the service owns the queue, synthesizer and media session.
 */
actual object TtsPlaybackController {
    actual val state: StateFlow<TtsPlaybackState> get() = TtsPlaybackService.state

    actual val chapterRequests: SharedFlow<Int> get() = TtsPlaybackService.chapterRequests

    actual fun startFromChapter(
        book: Book,
        chapterIndex: Int,
        sentenceIndex: Int
    ) = TtsPlaybackService.startFromChapter(androidAppContext, book, chapterIndex, sentenceIndex)

    actual fun startFromSentence(
        book: Book,
        chapterIndex: Int,
        sentenceText: String
    ) = TtsPlaybackService.startFromSentence(androidAppContext, book, chapterIndex, sentenceText)

    actual fun startFromBlockOffset(
        book: Book,
        chapterIndex: Int,
        blockText: String,
        blockOffset: Int
    ) = TtsPlaybackService.startFromBlockOffset(
        androidAppContext,
        book,
        chapterIndex,
        blockText,
        blockOffset
    )

    actual fun toggle() = TtsPlaybackService.toggle(androidAppContext)

    actual fun pause() = TtsPlaybackService.pause(androidAppContext)

    actual fun resume() = TtsPlaybackService.resume(androidAppContext)

    actual fun next() = TtsPlaybackService.next(androidAppContext)

    actual fun previous() = TtsPlaybackService.previous(androidAppContext)

    actual fun stop() = TtsPlaybackService.stop(androidAppContext)

    actual fun setRate(rate: Float) = TtsPlaybackService.setRate(androidAppContext, rate)

    actual fun onReaderChapterLoaded(bookId: String, chapterIndex: Int) =
        TtsPlaybackService.onReaderChapterLoaded(bookId, chapterIndex)

    actual fun onReaderChapterSelected(bookId: String, chapterIndex: Int) =
        TtsPlaybackService.onReaderChapterSelected(bookId, chapterIndex)

    actual fun onReaderPositionChanged(bookId: String, chapterIndex: Int, blockText: String) =
        TtsPlaybackService.onReaderPositionChanged(bookId, chapterIndex, blockText)
}
