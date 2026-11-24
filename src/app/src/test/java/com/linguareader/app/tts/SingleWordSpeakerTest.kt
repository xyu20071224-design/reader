package com.linguareader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleWordSpeakerTest {
    private class FakeEngine : SingleWordSpeaker.Engine {
        lateinit var listener: SingleWordSpeaker.Listener
        var languageResult = 0
        var speakResult = 0
        var initializationThrows = false
        var configureThrows = false
        var speakThrows = false
        var shutdownThrows = false
        var initializeImmediately: Int? = null
        var onSpeak: ((String) -> Unit)? = null
        var stops = 0
        var shutdowns = 0
        val spoken = mutableListOf<Pair<String, String>>()

        override fun initialize(listener: SingleWordSpeaker.Listener) {
            this.listener = listener
            if (initializationThrows) throw IllegalStateException("initialization failed")
            initializeImmediately?.let(listener::onInitialized)
        }
        override fun configureEnglish(): Int {
            if (configureThrows) throw IllegalStateException("engine disconnected")
            return languageResult
        }
        override fun speak(text: String, utteranceId: String): Int {
            if (speakThrows) throw IllegalStateException("engine disconnected")
            spoken += text to utteranceId
            onSpeak?.invoke(utteranceId)
            return speakResult
        }
        override fun stop() { stops++ }
        override fun shutdown() {
            shutdowns++
            if (shutdownThrows) throw IllegalStateException("already disconnected")
        }
    }

    private class Harness(vararg val engines: FakeEngine) {
        var created = 0
        val failures = mutableListOf<SingleWordSpeaker.Failure>()
        val events = mutableListOf<SingleWordSpeaker.Event>()
        val speaker = SingleWordSpeaker(
            engineFactory = { engines[created++] },
            onFailure = { failures += it },
            onEvent = { events += it }
        )
    }

    @Test fun latestRequestIsSpokenOnceAfterInitialization() {
        val engine = FakeEngine()
        val h = Harness(engine)
        h.speaker.speak("first")
        h.speaker.speak("take care")
        assertTrue(engine.spoken.isEmpty())
        engine.listener.onInitialized(0)
        engine.listener.onInitialized(0)
        assertEquals(listOf("take care"), engine.spoken.map { it.first })
        assertTrue(h.failures.isEmpty())
    }

    @Test fun synchronousInitializationCanSpeakWithoutAccessingAnUnboundEngine() {
        val engine = FakeEngine().apply { initializeImmediately = 0 }
        val h = Harness(engine)
        h.speaker.speak("hole")
        assertEquals(listOf("hole"), engine.spoken.map { it.first })
    }

    @Test fun missingEnglishDataDoesNotEnqueueSpeechAndReportsTheRequestedFailure() {
        val engine = FakeEngine().apply { languageResult = -1 }
        val h = Harness(engine)
        h.speaker.speak("hole")
        engine.listener.onInitialized(0)
        assertTrue(engine.spoken.isEmpty())
        assertEquals(listOf(SingleWordSpeaker.Failure.LANGUAGE_UNAVAILABLE), h.failures)
    }

    @Test fun unsupportedEnglishIsNotTreatedAsReady() {
        val engine = FakeEngine().apply { languageResult = -2 }
        val h = Harness(engine)
        h.speaker.speak("hole")
        engine.listener.onInitialized(0)
        assertTrue(engine.spoken.isEmpty())
        assertEquals(listOf(SingleWordSpeaker.Failure.LANGUAGE_UNAVAILABLE), h.failures)
    }

    @Test fun initializationFailureIsQuietUntilAUserRequest() {
        val first = FakeEngine()
        val retry = FakeEngine()
        val h = Harness(first, retry)
        h.speaker.start()
        first.listener.onInitialized(-1)
        assertTrue(h.failures.isEmpty())
        h.speaker.speak("hole")
        retry.listener.onInitialized(-1)
        assertEquals(listOf(SingleWordSpeaker.Failure.ENGINE_UNAVAILABLE), h.failures)
    }

    @Test fun aNewClickRetriesAfterMissingLanguageWithoutReplayingTheOldWord() {
        val first = FakeEngine().apply { languageResult = -1 }
        val retry = FakeEngine()
        val h = Harness(first, retry)
        h.speaker.speak("old")
        first.listener.onInitialized(0)
        h.speaker.speak("new")
        retry.listener.onInitialized(0)
        assertEquals(1, first.shutdowns)
        assertTrue(first.spoken.isEmpty())
        assertEquals(listOf("new"), retry.spoken.map { it.first })
    }

    @Test fun aLateInitializationFromTheReplacedEngineCannotFlushTheNewWord() {
        val first = FakeEngine().apply { initializationThrows = true }
        val retry = FakeEngine()
        val h = Harness(first, retry)
        h.speaker.speak("old")
        h.speaker.speak("new")
        first.listener.onInitialized(0)
        assertTrue(first.spoken.isEmpty())
        assertTrue(retry.spoken.isEmpty())
        retry.listener.onInitialized(0)
        assertEquals(listOf("new"), retry.spoken.map { it.first })
    }

    @Test fun initializationAfterDisposalCannotSpeakOrReportAnError() {
        val engine = FakeEngine()
        val h = Harness(engine)
        h.speaker.speak("hole")
        h.speaker.close()
        engine.listener.onInitialized(0)
        h.speaker.speak("after close")
        h.speaker.start()
        assertTrue(engine.spoken.isEmpty())
        assertTrue(h.failures.isEmpty())
        assertEquals(1, h.created)
        assertEquals(1, engine.shutdowns)
    }

    @Test fun staleQueueFlushCallbacksCannotClearOrFailTheReplacementRequest() {
        val engine = FakeEngine().apply { initializeImmediately = 0 }
        val h = Harness(engine)
        h.speaker.speak("old")
        val oldId = engine.spoken.last().second
        h.speaker.speak("new")
        val newId = engine.spoken.last().second
        assertNotEquals(oldId, newId)
        engine.listener.onStop(oldId)
        engine.listener.onError(oldId, -8)
        engine.listener.onDone(oldId)
        assertTrue(h.failures.isEmpty())
        engine.listener.onStart(newId)
        engine.listener.onError(newId, -8)
        assertEquals(listOf(SingleWordSpeaker.Failure.PLAYBACK_FAILED), h.failures)
    }

    @Test fun aRejectedQueueRequestReportsFailureInsteadOfAnEnqueuedEvent() {
        val engine = FakeEngine().apply { initializeImmediately = 0; speakResult = -1 }
        val h = Harness(engine)
        h.speaker.speak("hole")
        assertEquals(listOf(SingleWordSpeaker.Failure.REQUEST_REJECTED), h.failures)
        assertFalse(h.events.any { it.stage == SingleWordSpeaker.Stage.ENQUEUED })
    }

    @Test fun synchronousErrorAndRejectedReturnAreOnlyReportedOnce() {
        val engine = FakeEngine().apply {
            initializeImmediately = 0
            speakResult = -1
            onSpeak = { listener.onError(it, -8) }
        }
        val h = Harness(engine)
        h.speaker.speak("hole")
        assertEquals(listOf(SingleWordSpeaker.Failure.PLAYBACK_FAILED), h.failures)
    }

    @Test fun duplicateCompletionAndErrorCallbacksDoNotChangeFinishedSpeech() {
        val engine = FakeEngine().apply { initializeImmediately = 0 }
        val h = Harness(engine)
        h.speaker.speak("hole")
        val id = engine.spoken.last().second
        engine.listener.onDone(id)
        engine.listener.onDone(id)
        engine.listener.onError(id, -8)
        engine.listener.onStop(id)
        assertTrue(h.failures.isEmpty())
        assertEquals(1, h.events.count { it.stage == SingleWordSpeaker.Stage.DONE })
    }

    @Test fun anUnexpectedStopOfTheActiveRequestReportsFailure() {
        val engine = FakeEngine().apply { initializeImmediately = 0 }
        val h = Harness(engine)
        h.speaker.speak("hole")
        engine.listener.onStop(engine.spoken.last().second)
        assertEquals(listOf(SingleWordSpeaker.Failure.PLAYBACK_FAILED), h.failures)
    }

    @Test fun aDisconnectedLanguageConfigurationReportsEngineFailure() {
        val engine = FakeEngine().apply { configureThrows = true }
        val h = Harness(engine)
        h.speaker.speak("hole")
        engine.listener.onInitialized(0)
        assertTrue(engine.spoken.isEmpty())
        assertEquals(listOf(SingleWordSpeaker.Failure.ENGINE_UNAVAILABLE), h.failures)
    }

    @Test fun aDisconnectedQueueReportsRequestFailure() {
        val engine = FakeEngine().apply { initializeImmediately = 0; speakThrows = true }
        val h = Harness(engine)
        h.speaker.speak("hole")
        assertEquals(listOf(SingleWordSpeaker.Failure.REQUEST_REJECTED), h.failures)
    }

    @Test fun blankRequestsDoNotInitializeAnEngineOrExposeTextInDiagnostics() {
        val h = Harness()
        h.speaker.speak("  ")
        assertEquals(0, h.created)
        assertTrue(h.events.isEmpty())
        val engine = FakeEngine().apply { initializeImmediately = 0 }
        val actual = Harness(engine)
        actual.speaker.speak("private phrase")
        assertFalse(actual.events.any { it.toString().contains("private phrase") })
    }

    @Test fun repeatedCloseIsSafeEvenWhenTheEngineHasDisconnected() {
        val engine = FakeEngine().apply { initializeImmediately = 0; shutdownThrows = true }
        val h = Harness(engine)
        h.speaker.start()
        h.speaker.close()
        h.speaker.close()
        assertEquals(1, engine.shutdowns)
        assertTrue(h.failures.isEmpty())
    }

    @Test fun engineConstructionFailureRemainsQuietAndTheNextClickCanRecover() {
        val engine = FakeEngine().apply { initializeImmediately = 0 }
        var attempts = 0
        val failures = mutableListOf<SingleWordSpeaker.Failure>()
        val speaker = SingleWordSpeaker(
            engineFactory = {
                if (attempts++ == 0) throw IllegalStateException("engine missing")
                engine
            },
            onFailure = { failures += it }
        )
        speaker.start()
        assertTrue(failures.isEmpty())
        speaker.speak("recovered")
        assertEquals(listOf("recovered"), engine.spoken.map { it.first })
        assertTrue(failures.isEmpty())
    }

    @Test fun allNonnegativeLanguageAvailabilityLevelsAreUsable() {
        for (level in listOf(0, 1, 2)) {
            val engine = FakeEngine().apply { initializeImmediately = 0; languageResult = level }
            val h = Harness(engine)
            h.speaker.speak("hole")
            assertEquals(listOf("hole"), engine.spoken.map { it.first })
            assertTrue(h.failures.isEmpty())
        }
    }

    @Test fun playbackCallbacksAfterDisposalDoNotReportFailuresOrRestartAnything() {
        val engine = FakeEngine().apply { initializeImmediately = 0 }
        val h = Harness(engine)
        h.speaker.speak("hole")
        val id = engine.spoken.last().second
        h.speaker.close()
        engine.listener.onStart(id)
        engine.listener.onError(id, -8)
        engine.listener.onStop(id)
        engine.listener.onDone(id)
        assertTrue(h.failures.isEmpty())
        assertEquals(1, engine.spoken.size)
    }
}
