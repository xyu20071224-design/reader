package com.linguareader.app.tts

import android.content.Context
import com.linguareader.app.data.Book
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin UI-facing facade over [TtsPlaybackService]. The reader screen talks to
 * this object; the service owns the queue, synthesizer and media session.
 */
object TtsPlaybackController {
    val state: StateFlow<TtsPlaybackState> get() = TtsPlaybackService.state

    val chapterRequests: SharedFlow<Int> get() = TtsPlaybackService.chapterRequests

    fun startFromChapter(
        context: Context,
        book: Book,
        chapterIndex: Int,
        sentenceIndex: Int
    ) = TtsPlaybackService.startFromChapter(context, book, chapterIndex, sentenceIndex)

    /** Opens the listening session without playing; the user picks the start. */
    fun startStandby(
        context: Context,
        book: Book,
        chapterIndex: Int
    ) = TtsPlaybackService.startStandby(context, book, chapterIndex)

    fun startFromSentence(
        context: Context,
        book: Book,
        chapterIndex: Int,
        sentenceText: String
    ) = TtsPlaybackService.startFromSentence(context, book, chapterIndex, sentenceText)

    fun startFromBlockOffset(
        context: Context,
        book: Book,
        chapterIndex: Int,
        blockText: String,
        blockOffset: Int
    ) = TtsPlaybackService.startFromBlockOffset(
        context,
        book,
        chapterIndex,
        blockText,
        blockOffset
    )

    fun toggle(context: Context) = TtsPlaybackService.toggle(context)

    fun pause(context: Context) = TtsPlaybackService.pause(context)

    fun resume(context: Context) = TtsPlaybackService.resume(context)

    fun next(context: Context) = TtsPlaybackService.next(context)

    fun previous(context: Context) = TtsPlaybackService.previous(context)

    fun stop(context: Context) = TtsPlaybackService.stop(context)

    fun setRate(context: Context, rate: Float) = TtsPlaybackService.setRate(context, rate)

    fun onReaderChapterLoaded(bookId: String, chapterIndex: Int) =
        TtsPlaybackService.onReaderChapterLoaded(bookId, chapterIndex)

    fun onReaderChapterSelected(bookId: String, chapterIndex: Int) =
        TtsPlaybackService.onReaderChapterSelected(bookId, chapterIndex)

    fun onReaderPositionChanged(bookId: String, chapterIndex: Int, blockText: String) =
        TtsPlaybackService.onReaderPositionChanged(bookId, chapterIndex, blockText)

    fun onCloudSettingsChanged(context: Context) =
        TtsPlaybackService.onCloudSettingsChanged(context)
}
