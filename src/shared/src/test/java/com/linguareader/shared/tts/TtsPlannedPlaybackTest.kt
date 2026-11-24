package com.linguareader.shared.tts

import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TtsPlannedPlaybackTest {
    private open class Fake(val listener: TtsSynthesizerListener) : TtsSynthesizer {
        override val isReady = true
        val raw = mutableListOf<String>()
        var stopped = 0
        override fun speak(text: String, rate: Float, utteranceId: String, voice: String?) { raw += text }
        override fun stop() { stopped++ }
        override fun shutdown() = Unit
    }
    private class Planned(listener: TtsSynthesizerListener) : Fake(listener), PlannedTtsSynthesizer, ChapterTtsPreparer, BookTtsPreparer {
        data class Call(val request: PlannedSpeechRequest, val id: String, val voice: String?)
        val plans = mutableListOf<Call>()
        val prepared = mutableListOf<ProsodyConfiguration>()
        var configuration = ProsodyConfiguration()
        val bookPreparations = mutableListOf<Pair<(Int, Int) -> Unit, (Boolean) -> Unit>>()
        override fun configureProsody(configuration: ProsodyConfiguration) { this.configuration = configuration }
        override fun speakPlanned(request: PlannedSpeechRequest, utteranceId: String, voice: String?) {
            plans += Call(request, utteranceId, voice)
        }
        override fun prepareChapter(book: Book, chapter: TtsChapter, onProgress: (Int, Int) -> Unit, onComplete: (Boolean) -> Unit) {
            prepared += configuration
            onComplete(true)
        }
        override fun prepareBook(book: Book, chapterCount: Int, chapterProvider: suspend (Int) -> TtsChapter, onProgress: (Int, Int) -> Unit, onComplete: (Boolean) -> Unit) {
            bookPreparations += onProgress to onComplete
        }
    }
    private val config = ProsodyConfiguration(ProsodyPlanner.Policy(mode = ProsodyPlanner.Mode.PHRASES, minimumPhraseChars = 5))
    private val text = "This is the first longer clause, followed by the final longer clause."
    private fun book(id: String = "book") = Book(id, id, "", "/tmp", null, listOf(Chapter("Chapter", "chapter.html")), 0L)
    private inner class Harness(scope: TestScope, planned: Boolean = true, chapter: TtsChapter = TtsChapter(0, "Chapter", listOf(text))) {
        lateinit var fake: Fake
        val created = mutableListOf<Fake>()
        val states = mutableListOf<TtsPlaybackState>()
        val engine = TtsPlaybackEngine(
            synthesizerFactory = { listener -> (if (planned) Planned(listener) else Fake(listener)).also { fake = it; created += it } },
            chapterLoader = { _, _ -> chapter }, isSystemEngine = { true }, onChapterRequest = {},
            onBookSwitched = {}, onProgressSave = { _, _, _ -> }, onState = { states += it },
            dispatcher = StandardTestDispatcher(scope.testScheduler), readerLoadedChapterFor = { 0 },
            voiceForSpeaker = { speaker, _ -> "voice-$speaker" }
        )
        val planned get() = fake as Planned
        val state get() = states.last()
    }

    @Test fun originalDefaultUsesOnlyTheLegacyPath() = runTest {
        val h = Harness(this)
        try {
            h.engine.startPlayback(book(), 0, 0); testScheduler.runCurrent()
            assertEquals(listOf(text), h.fake.raw)
            assertTrue(h.planned.plans.isEmpty())
            assertEquals(ProsodyPlanner.Mode.ORIGINAL, h.state.prosodyMode)
        } finally { h.engine.shutdown() }
    }

    @Test fun requestedPlanIsConfiguredBeforeChapterPreparationAndKeepsParentCoordinates() = runTest {
        val h = Harness(this)
        try {
            h.engine.setProsody(config)
            h.engine.startPlayback(book(), 0, 0); testScheduler.runCurrent()
            assertEquals(listOf(config), h.planned.prepared)
            val request = h.planned.plans.single().request
            assertTrue(h.fake.raw.isEmpty())
            assertEquals(2, request.units.size)
            assertTrue(request.units.all { it.utterance.sentenceIndex == 0 && it.utterance.segmentIndex == 0 })
            assertFalse(request.configuration.allowAudioPackRegeneration)
            assertEquals(ProsodyPlanner.Mode.PHRASES, h.state.prosodyEffectiveMode)
        } finally { h.engine.shutdown() }
    }

    @Test fun unsupportedBackendFallsBackVisiblyToOriginal() = runTest {
        val h = Harness(this, planned = false)
        try {
            h.engine.setProsody(config)
            h.engine.startPlayback(book(), 0, 0); testScheduler.runCurrent()
            assertEquals(listOf(text), h.fake.raw)
            assertEquals(ProsodyPlanner.Mode.PHRASES, h.state.prosodyMode)
            assertEquals(ProsodyPlanner.Mode.ORIGINAL, h.state.prosodyEffectiveMode)
            assertEquals(ProsodyFallbackReason.UNSUPPORTED_BACKEND, h.state.prosodyFallbackReason)
        } finally { h.engine.shutdown() }
    }

    @Test fun currentPackFallbackAndRangesAreVisibleButOldRequestEventsAreIgnored() = runTest {
        val h = Harness(this)
        try {
            h.engine.startPlayback(book(), 0, 0); testScheduler.runCurrent()
            h.engine.setProsody(config); testScheduler.runCurrent()
            val call = h.planned.plans.single()
            h.fake.listener.onProsody(call.id, ProsodyPlanner.Mode.ORIGINAL, ProsodyFallbackReason.AUDIO_PACK_ORIGINAL)
            h.fake.listener.onPlannedRange(call.id, 0, 32, 38)
            testScheduler.runCurrent()
            assertEquals(ProsodyFallbackReason.AUDIO_PACK_ORIGINAL, h.state.prosodyFallbackReason)
            assertEquals(32, h.state.highlightOffset)
            h.fake.listener.onProsody("old", ProsodyPlanner.Mode.PHRASES, null)
            h.fake.listener.onPlannedRange("old", 9, 999, 2)
            testScheduler.runCurrent()
            assertEquals(ProsodyPlanner.Mode.ORIGINAL, h.state.prosodyEffectiveMode)
            assertEquals(32, h.state.highlightOffset)
        } finally { h.engine.shutdown() }
    }

    @Test fun globalRateChangeDoesNotRestartChapterPreparation() = runTest {
        val h = Harness(this)
        try {
            h.engine.setProsody(config)
            h.engine.startPlayback(book(), 0, 0); testScheduler.runCurrent()
            h.engine.setRate(1.5f); testScheduler.runCurrent()
            assertEquals(1, h.planned.prepared.size)
            assertEquals(2, h.planned.plans.size)
            assertEquals(1.5f, h.planned.plans.last().request.units.first().rate)
        } finally { h.engine.shutdown() }
    }

    @Test fun regenerationPermissionIsRevokedOnBookSwitchAndStop() = runTest {
        val h = Harness(this)
        try {
            h.engine.setProsody(config)
            h.engine.startPlayback(book(), 0, 0); testScheduler.runCurrent()
            h.engine.setProsody(config.copy(allowAudioPackRegeneration = true)); testScheduler.runCurrent()
            assertTrue(h.planned.plans.last().request.configuration.allowAudioPackRegeneration)
            h.engine.startPlayback(book("other"), 0, 0); testScheduler.runCurrent()
            assertFalse(h.planned.plans.last().request.configuration.allowAudioPackRegeneration)
            h.engine.setProsody(config.copy(allowAudioPackRegeneration = true)); testScheduler.runCurrent()
            h.engine.stop()
            assertFalse(h.planned.configuration.allowAudioPackRegeneration)
        } finally { h.engine.shutdown() }
    }

    @Test fun switchingVoiceSegmentsPreservesSpeakerAndSentenceWithoutPersistingPartIndexes() = runTest {
        val chapter = TtsChapter(0, "Chapter", listOf("Gandalf said, \"This is a longer phrase, followed by another longer phrase.\""), listOf("Gandalf"))
        val h = Harness(this, chapter = chapter)
        try {
            h.engine.setProsody(config)
            h.engine.startPlayback(book(), 0, 0); testScheduler.runCurrent()
            val first = h.planned.plans.single()
            h.fake.listener.onDone(first.id); testScheduler.runCurrent()
            val second = h.planned.plans.last()
            assertEquals("narrator", first.request.source.speaker)
            assertEquals("Gandalf", second.request.source.speaker)
            assertEquals(first.request.source.sentenceIndex, second.request.source.sentenceIndex)
            assertEquals(1, second.request.source.segmentIndex)
            assertEquals("voice-Gandalf", second.voice)
            assertEquals(1, second.request.units.size)
        } finally { h.engine.shutdown() }
    }

    @Test fun changingProsodyReleasesTheCancelledWholeBookCacheState() = runTest {
        val h = Harness(this)
        try {
            h.engine.startPlayback(book(), 0, 0); testScheduler.runCurrent()
            h.engine.cacheWholeBook(); testScheduler.runCurrent()
            assertTrue(h.state.isCachingBook)
            val old = h.planned.bookPreparations.single()
            h.engine.setProsody(config); testScheduler.runCurrent()
            assertFalse(h.state.isCachingBook, "The backend cancelled old-mode generation")
            old.first(50, 100); testScheduler.runCurrent()
            assertFalse(h.state.isCachingBook, "Old progress must not revive the cancelled request")
            assertEquals(0, h.state.cachedTotal)
        } finally { h.engine.shutdown() }
    }

    @Test fun combinedBackendAndModeSaveOnlyPreparesTheReplacementBackendOnce() = runTest {
        val h = Harness(this)
        try {
            h.engine.startPlayback(book(), 0, 0); testScheduler.runCurrent()
            val old = h.planned
            h.engine.reconfigure(config); testScheduler.runCurrent()
            assertEquals(2, h.created.size)
            assertEquals(listOf(text), old.raw)
            assertTrue(old.plans.isEmpty(), "The previous backend must not receive a new-mode request")
            assertEquals(1, old.prepared.size)
            assertEquals(listOf(config), h.planned.prepared)
            assertEquals(1, h.planned.plans.size)
            assertTrue(h.planned.raw.isEmpty())
            assertEquals(ProsodyPlanner.Mode.PHRASES, h.state.prosodyMode)
        } finally { h.engine.shutdown() }
    }

    @Test fun cancelledWholeBookCompletionCannotFinishANewModeRequest() = runTest {
        val h = Harness(this)
        try {
            h.engine.startPlayback(book(), 0, 0); testScheduler.runCurrent()
            h.engine.cacheWholeBook(); testScheduler.runCurrent()
            val old = h.planned.bookPreparations.single()
            h.engine.setProsody(config); testScheduler.runCurrent()
            h.engine.cacheWholeBook(); testScheduler.runCurrent()
            old.second(false); testScheduler.runCurrent()
            assertTrue(h.state.isCachingBook, "A stale completion must not finish the new-mode cache request")
            assertEquals(2, h.planned.bookPreparations.size)
            val current = h.planned.bookPreparations.last()
            current.first(5, 10); testScheduler.runCurrent()
            assertEquals(5, h.state.cachedSentences)
            current.second(true); testScheduler.runCurrent()
            assertFalse(h.state.isCachingBook)
            assertEquals(10, h.state.cachedSentences)
        } finally { h.engine.shutdown() }
    }
}
