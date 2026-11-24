package com.linguareader.shared.tts

import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Controls arriving during the sentence-end pause must supersede that completed request. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TtsPauseCancellationTest {
    private data class Spoken(val text: String, val id: String, val rate: Float)
    private class FakeSynthesizer(val listener: TtsSynthesizerListener) : TtsSynthesizer {
        override val isReady = true
        val spoken = mutableListOf<Spoken>()
        override fun speak(text: String, rate: Float, utteranceId: String, voice: String?) {
            spoken += Spoken(text, utteranceId, rate)
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
    }
    private class Harness(scope: TestScope, initialSentence: Int) {
        lateinit var synth: FakeSynthesizer
        val book = book("first")
        val states = mutableListOf<TtsPlaybackState>()
        val engine = TtsPlaybackEngine(
            synthesizerFactory = { FakeSynthesizer(it).also { fake -> synth = fake } },
            chapterLoader = { b, i -> TtsChapter(i, "Chapter", listOf("${b.id} A.", "${b.id} B.", "${b.id} C.")) },
            isSystemEngine = { true },
            onChapterRequest = {},
            onBookSwitched = {},
            onProgressSave = { _, _, _ -> },
            onState = { states += it },
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            readerLoadedChapterFor = { 0 }
        )
        init {
            engine.startPlayback(book, 0, initialSentence)
            scope.testScheduler.runCurrent()
        }
        val texts get() = synth.spoken.map { it.text }
        fun done() = synth.listener.onDone(synth.spoken.last().id)
    }
    companion object {
        private fun book(id: String) = Book(
            id = id, title = id, author = "", extractedDir = "/tmp", coverRelativePath = null,
            chapters = listOf(Chapter("Chapter", "chapter.html")), addedAt = 0L
        )
    }

    @Test fun nextDuringPauseDoesNotSkipTheManuallySelectedSentence() = runTest {
        val h = Harness(this, 0)
        try {
            h.done()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(100)
            h.engine.next()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(SENTENCE_END_PAUSE_MILLIS)
            testScheduler.runCurrent()
            assertEquals(listOf("first A.", "first B."), h.texts)
        } finally { h.engine.shutdown() }
    }

    @Test fun previousDuringPauseDoesNotJumpForwardAgain() = runTest {
        val h = Harness(this, 1)
        try {
            h.done()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(100)
            h.engine.previous()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(SENTENCE_END_PAUSE_MILLIS)
            testScheduler.runCurrent()
            assertEquals(listOf("first B.", "first A."), h.texts)
        } finally { h.engine.shutdown() }
    }

    @Test fun pauseDoesNotSilentlyAdvanceTheSentenceResumedLater() = runTest {
        val h = Harness(this, 0)
        try {
            h.done()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(100)
            h.engine.pause()
            testScheduler.advanceTimeBy(SENTENCE_END_PAUSE_MILLIS)
            testScheduler.runCurrent()
            h.engine.resume()
            testScheduler.runCurrent()
            assertEquals(listOf("first A.", "first A."), h.texts)
        } finally { h.engine.shutdown() }
    }

    @Test fun speedChangeDuringPauseDoesNotSkipTheReplayedSentence() = runTest {
        val h = Harness(this, 0)
        try {
            h.done()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(100)
            h.engine.setRate(1.2f)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(SENTENCE_END_PAUSE_MILLIS)
            testScheduler.runCurrent()
            assertEquals(listOf("first A.", "first A."), h.texts)
            assertEquals(1.2f, h.synth.spoken.last().rate)
        } finally { h.engine.shutdown() }
    }

    @Test fun switchingBooksDuringPauseDoesNotAdvanceTheNewBook() = runTest {
        val h = Harness(this, 0)
        try {
            h.done()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(100)
            h.engine.startPlayback(book("replacement"), 0, 0)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(SENTENCE_END_PAUSE_MILLIS)
            testScheduler.runCurrent()
            assertEquals(listOf("first A.", "replacement A."), h.texts)
        } finally { h.engine.shutdown() }
    }

    @Test fun stopThenRestartDuringPauseDoesNotAdvanceTheRestartedSentence() = runTest {
        val h = Harness(this, 0)
        try {
            h.done()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(100)
            h.engine.stop()
            h.engine.startPlayback(h.book, 0, 0)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(SENTENCE_END_PAUSE_MILLIS)
            testScheduler.runCurrent()
            assertEquals(listOf("first A.", "first A."), h.texts)
        } finally { h.engine.shutdown() }
    }

    @Test fun duplicateCompletionOnlyAdvancesOnce() = runTest {
        val h = Harness(this, 0)
        try {
            h.done()
            h.done()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(SENTENCE_END_PAUSE_MILLIS)
            testScheduler.runCurrent()
            assertEquals(listOf("first A.", "first B."), h.texts)
        } finally { h.engine.shutdown() }
    }
}
