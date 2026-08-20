package com.linguareader.app.tts

import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterizes the pure [TtsPlaybackEngine] state machine with scripted fake
 * synthesizers: the whole queue (play/pause/skip/chapter/fallback) is driven
 * without any Android runtime. Mirrors Readest's tts-fake-audio approach.
 */
class TtsPlaybackEngineTest {

    private data class Spoken(
        val text: String,
        val rate: Float,
        val utteranceId: String,
        val voice: String? = null
    )

    private class FakeTtsSynthesizer(
        val listener: TtsSynthesizerListener,
        override val isReady: Boolean = true
    ) : TtsSynthesizer {
        val spoken = mutableListOf<Spoken>()
        var stopped = 0
        var shutdown = 0
        override fun speak(text: String, rate: Float, utteranceId: String, voice: String?) {
            spoken += Spoken(text, rate, utteranceId, voice)
        }
        override fun stop() { stopped++ }
        override fun shutdown() { shutdown++ }
        fun emitStart(id: String) = listener.onStart(id)
        fun emitDone(id: String) = listener.onDone(id)
        fun emitError(id: String) = listener.onError(id)
        fun emitReady() = listener.onReady()
        fun emitInitFailed(status: Int) = listener.onInitFailed(status)
    }

    private class FakeCloudTtsSynthesizer(
        val listener: TtsSynthesizerListener,
        override val isReady: Boolean = true,
        var prepareResult: Boolean = true
    ) : TtsSynthesizer, ChapterTtsPreparer {
        val spoken = mutableListOf<Spoken>()
        var stopped = 0
        var shutdown = 0
        var prepareCalls = 0
        override fun speak(text: String, rate: Float, utteranceId: String, voice: String?) {
            spoken += Spoken(text, rate, utteranceId, voice)
        }
        override fun stop() { stopped++ }
        override fun shutdown() { shutdown++ }
        override fun prepareChapter(
            book: Book,
            chapter: TtsChapter,
            onProgress: (Int, Int) -> Unit,
            onComplete: (Boolean) -> Unit
        ) {
            prepareCalls++
            onProgress(chapter.sentenceCount, chapter.sentenceCount)
            onComplete(prepareResult)
        }
        fun emitStart(id: String) = listener.onStart(id)
        fun emitDone(id: String) = listener.onDone(id)
        fun emitError(id: String) = listener.onError(id)
    }

    private class Harness(
        dispatcher: TestDispatcher,
        factory: (TtsSynthesizerListener) -> TtsSynthesizer,
        isSystem: (TtsSynthesizer) -> Boolean,
        chapters: suspend (Book, Int) -> TtsChapter,
        readerLoaded: (String) -> Int? = { null },
        voiceForSpeaker: (String, String) -> String? = { _, _ -> null }
    ) {
        val synthesizers = mutableListOf<TtsSynthesizer>()
        private val recordingFactory: (TtsSynthesizerListener) -> TtsSynthesizer = { l ->
            factory(l).also { synthesizers += it }
        }
        val chapterRequests = mutableListOf<Int>()
        val progressSaves = mutableListOf<Triple<String, Int, Int>>()
        val states = mutableListOf<TtsPlaybackState>()
        val engine = TtsPlaybackEngine(
            synthesizerFactory = recordingFactory,
            chapterLoader = chapters,
            isSystemEngine = isSystem,
            engineLabelForSettings = { "系统语音" },
            engineLabelForSynthesizer = { "系统语音" },
            onChapterRequest = { chapterRequests += it },
            onBookSwitched = {},
            onProgressSave = { b, c, s -> progressSaves += Triple(b.id, c, s) },
            onState = { states += it },
            dispatcher = dispatcher,
            readerLoadedChapterFor = readerLoaded,
            voiceForSpeaker = voiceForSpeaker
        )
        val state: TtsPlaybackState get() = states.last()
        val synthesizer: TtsSynthesizer get() = synthesizers.first()
    }

    private fun book(id: String = "b1", chapterCount: Int = 1): Book = Book(
        id = id,
        title = "Test",
        author = "",
        extractedDir = "/tmp",
        coverRelativePath = null,
        chapters = (0 until chapterCount).map { Chapter("Ch" + it, "ch" + it + ".html") },
        addedAt = 0L
    )

    private fun chapter(vararg sentences: String, chapterIndex: Int = 0): TtsChapter =
        TtsChapter(chapterIndex, "Ch" + chapterIndex, sentences.toList())

    private fun plainHarness(
        dispatcher: TestDispatcher,
        chapters: suspend (Book, Int) -> TtsChapter,
        readerLoaded: (String) -> Int? = { null }
    ): Harness = Harness(
        dispatcher = dispatcher,
        factory = { l -> FakeTtsSynthesizer(l) },
        isSystem = { it is FakeTtsSynthesizer },
        chapters = chapters,
        readerLoaded = readerLoaded
    )

    @Test
    fun playsFirstSentenceAndHighlights() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("Hello.", "World.") })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer

        assertEquals(1, fake.spoken.size)
        assertEquals("Hello.", fake.spoken[0].text)
        assertEquals(1f, fake.spoken[0].rate)
        assertEquals("b1:0:0:1", fake.spoken[0].utteranceId)
        assertEquals("Hello.", h.state.currentSentence)
        assertTrue(h.state.isPlaying)
        assertEquals(0, h.state.highlightBlockIndex)
        assertEquals(0, h.state.highlightOffset)
        assertEquals("Hello.".length, h.state.highlightLength)
        h.engine.shutdown()
    }

    @Test
    fun appliesRefinedSpeakerTagsMidPlayback() = runTest {
        // M2: the LLM tagger answers after playback started; the refined tags
        // are applied to the loaded chapter without touching the queue, so the
        // next sentence is spoken with its character voice.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loaded = TtsChapter(
            0, "Ch0",
            blocks = listOf("She nodded. \"Yes,\" she said."),
            speakers = listOf("narrator", "dialogue")
        )
        val h = Harness(
            dispatcher = dispatcher,
            factory = { l -> FakeTtsSynthesizer(l) },
            isSystem = { it is FakeTtsSynthesizer },
            chapters = { _, _ -> loaded },
            voiceForSpeaker = { speaker, _ ->
                when (speaker) {
                    "narrator" -> "af_maple"
                    "Gandalf" -> "am_onyx"
                    else -> "af_sol"
                }
            }
        )
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals("af_maple", fake.spoken[0].voice)

        h.engine.applySpeakerTags("b1", 0, listOf("narrator", "Gandalf"))
        testScheduler.advanceUntilIdle()
        fake.emitStart("b1:0:0:1")
        fake.emitDone("b1:0:0:1")
        testScheduler.advanceUntilIdle()

        assertEquals(2, fake.spoken.size)
        assertEquals("am_onyx", fake.spoken[1].voice)
        assertEquals("Gandalf", h.engine.currentChapter?.speakerAt(1))
        h.engine.shutdown()
    }

    @Test
    fun ignoresStaleOrMismatchedSpeakerTags() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loaded = TtsChapter(
            0, "Ch0",
            blocks = listOf("She nodded. \"Yes,\" she said."),
            speakers = listOf("narrator", "dialogue")
        )
        val h = plainHarness(dispatcher, { _, _ -> loaded })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()

        // Another book, another chapter, and a list of the wrong length: all
        // dropped, so voices can never shift onto the wrong sentences.
        h.engine.applySpeakerTags("other", 0, listOf("Gandalf", "Gandalf"))
        h.engine.applySpeakerTags("b1", 3, listOf("Gandalf", "Gandalf"))
        h.engine.applySpeakerTags("b1", 0, listOf("Gandalf"))
        testScheduler.advanceUntilIdle()

        assertEquals("dialogue", h.engine.currentChapter?.speakerAt(1))
        h.engine.shutdown()
    }

    @Test
    fun passesPerSpeakerVoiceToSynthesizer() = runTest {
        // M1 multi-voice: narrator sentences get the narrator voice, dialogue
        // sentences get the dialogue voice, both via the injected resolver.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tagged = TtsChapter(
            0, "Ch0",
            blocks = listOf("She nodded. \"Yes,\" she said."),
            speakers = listOf("narrator", "dialogue")
        )
        val h = Harness(
            dispatcher = dispatcher,
            factory = { l -> FakeTtsSynthesizer(l) },
            isSystem = { it is FakeTtsSynthesizer },
            chapters = { _, _ -> tagged },
            voiceForSpeaker = { speaker, _ ->
                if (speaker == "narrator") "af_maple" else "af_sol"
            }
        )
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer

        assertEquals(1, fake.spoken.size)
        assertEquals("af_maple", fake.spoken[0].voice)
        fake.emitStart("b1:0:0:1")
        fake.emitDone("b1:0:0:1")
        testScheduler.advanceUntilIdle()
        assertEquals(2, fake.spoken.size)
        assertEquals("af_sol", fake.spoken[1].voice)
        h.engine.shutdown()
    }

    @Test
    fun noVoicePassedWhenResolverDisabled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("Hello.", "World.") })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertNull(fake.spoken[0].voice)
        h.engine.shutdown()
    }

    @Test
    fun advancesSentencesUntilFinishAndSavesProgress() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("A.", "B.", "C.") })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer

        assertEquals("A.", fake.spoken.last().text)
        fake.emitDone(fake.spoken.last().utteranceId)
        testScheduler.advanceUntilIdle()
        assertEquals("B.", fake.spoken.last().text)

        fake.emitDone(fake.spoken.last().utteranceId)
        testScheduler.advanceUntilIdle()
        assertEquals("C.", fake.spoken.last().text)

        fake.emitDone(fake.spoken.last().utteranceId)
        testScheduler.advanceUntilIdle()
        assertEquals(3, fake.spoken.size)
        assertNull(h.state.bookId)
        assertEquals(Triple("b1", 0, 2), h.progressSaves.last())
        h.engine.shutdown()
    }

    @Test
    fun pauseStopsThenResumeRespeaksCurrent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("A.", "B.") })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals(1, fake.spoken.size)

        h.engine.pause()
        assertFalse(h.state.isPlaying)
        assertTrue(fake.stopped >= 1)

        h.engine.resume()
        testScheduler.advanceUntilIdle()
        assertEquals(2, fake.spoken.size)
        assertEquals("A.", fake.spoken.last().text)
        assertTrue(h.state.isPlaying)
        h.engine.shutdown()
    }

    @Test
    fun nextAndPreviousSkipSentences() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("A.", "B.", "C.") })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals("A.", fake.spoken.last().text)

        h.engine.next()
        testScheduler.advanceUntilIdle()
        assertEquals("B.", fake.spoken.last().text)

        h.engine.previous()
        testScheduler.advanceUntilIdle()
        assertEquals("A.", fake.spoken.last().text)
        h.engine.shutdown()
    }

    @Test
    fun previousAtChapterStartLoadsPreviousChapterLastSentence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val chapters: suspend (Book, Int) -> TtsChapter = { _, i ->
            if (i == 0) chapter("X.", "Y.", chapterIndex = 0)
            else chapter("Z.", chapterIndex = 1)
        }
        val h = plainHarness(dispatcher, chapters)
        h.engine.startPlayback(book(chapterCount = 2), 1, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals("Z.", fake.spoken.last().text)

        h.engine.previous()
        testScheduler.advanceUntilIdle()
        assertEquals("Y.", fake.spoken.last().text)
        h.engine.shutdown()
    }

    @Test
    fun autoAdvancesToNextChapter() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val chapters: suspend (Book, Int) -> TtsChapter = { _, i ->
            if (i == 0) chapter("X.", chapterIndex = 0) else chapter("Y.", chapterIndex = 1)
        }
        val h = plainHarness(dispatcher, chapters)
        h.engine.startPlayback(book(chapterCount = 2), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals("X.", fake.spoken.last().text)

        fake.emitDone(fake.spoken.last().utteranceId)
        testScheduler.advanceUntilIdle()
        assertEquals("Y.", fake.spoken.last().text)
        h.engine.shutdown()
    }

    @Test
    fun cloudPrepareSuccessDoesNotFallback() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val created = mutableListOf<TtsSynthesizer>()
        val factory: (TtsSynthesizerListener) -> TtsSynthesizer = { l ->
            FakeCloudTtsSynthesizer(l, prepareResult = true).also { created += it }
        }
        val h = Harness(
            dispatcher = dispatcher,
            factory = factory,
            isSystem = { it is FakeTtsSynthesizer },
            chapters = { _, _ -> chapter("A.", "B.") },
        )
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()

        assertEquals(1, created.size)
        val cloud = created[0] as FakeCloudTtsSynthesizer
        assertEquals(1, cloud.prepareCalls)
        assertEquals(1, cloud.spoken.size)
        assertEquals("A.", cloud.spoken[0].text)
        assertFalse(h.state.isPreparing)
        h.engine.shutdown()
    }

    @Test
    fun cloudPrepareFailureFallsBackToSystemEngine() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val created = mutableListOf<TtsSynthesizer>()
        var first = true
        val factory: (TtsSynthesizerListener) -> TtsSynthesizer = { l ->
            if (first) {
                first = false
                FakeCloudTtsSynthesizer(l, prepareResult = false).also { created += it }
            } else {
                FakeTtsSynthesizer(l).also { created += it }
            }
        }
        val h = Harness(
            dispatcher = dispatcher,
            factory = factory,
            isSystem = { it is FakeTtsSynthesizer },
            chapters = { _, _ -> chapter("A.") },
        )
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()

        assertEquals(2, created.size)
        val cloud = created[0] as FakeCloudTtsSynthesizer
        val system = created[1] as FakeTtsSynthesizer
        assertTrue(cloud.spoken.isEmpty())
        // Fallback re-speaks the first sentence on the system engine (the stale
        // chapter handshake plus the re-queued load both land on it — a
        // pre-existing behaviour, not a regression this refactor introduced).
        assertTrue(system.spoken.isNotEmpty())
        assertEquals("A.", system.spoken.first().text)
        assertEquals("系统语音（云 TTS 失败）", h.state.engineLabel)
        h.engine.shutdown()
    }

    @Test
    fun alreadyLoadedChapterSpeaksWithoutHandshake() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(
            dispatcher,
            { _, _ -> chapter("A.") },
            readerLoaded = { id -> if (id == "b1") 0 else null },
        )
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer

        assertEquals(1, fake.spoken.size)
        assertTrue(h.chapterRequests.isEmpty())
        h.engine.shutdown()
    }

    @Test
    fun unconfirmedChapterSpeaksAfterGraceTimeout() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("A.") })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.runCurrent()

        assertEquals(listOf(0), h.chapterRequests)
        assertTrue((h.synthesizer as FakeTtsSynthesizer).spoken.isEmpty())

        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals(1, fake.spoken.size)
        assertEquals("A.", fake.spoken[0].text)
        h.engine.shutdown()
    }

    @Test
    fun errorAdvancesToNextSentence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("A.", "B.") })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals("A.", fake.spoken.last().text)

        fake.emitError(fake.spoken.last().utteranceId)
        testScheduler.advanceUntilIdle()
        assertEquals("B.", fake.spoken.last().text)
        assertTrue(h.state.isPlaying)
        h.engine.shutdown()
    }

    @Test
    fun repeatedErrorsPauseAfterThreshold() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sentences = (0 until 30).map { "S" + it + "." }.toTypedArray()
        val h = plainHarness(dispatcher, { _, _ -> chapter(*sentences) })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer

        repeat(25) {
            fake.emitError(fake.spoken.last().utteranceId)
            testScheduler.advanceUntilIdle()
        }
        assertFalse(h.state.isPlaying)
        h.engine.shutdown()
    }

    @Test
    fun standbyDoesNotSpeakUntilResume() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("A.") })
        h.engine.startStandby(book(), 0)
        testScheduler.advanceUntilIdle()

        assertFalse(h.state.isPlaying)
        assertTrue(h.synthesizers.isEmpty())

        h.engine.resume()
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals(1, fake.spoken.size)
        assertEquals("A.", fake.spoken[0].text)
        h.engine.shutdown()
    }
}

