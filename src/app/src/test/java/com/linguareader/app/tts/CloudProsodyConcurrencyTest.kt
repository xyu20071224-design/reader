package com.linguareader.app.tts

import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.data.Book
import com.linguareader.shared.tts.ProsodyConfiguration
import com.linguareader.shared.tts.ProsodyPlanner
import com.linguareader.shared.tts.TtsChapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudProsodyConcurrencyTest {
    @Test fun concurrentPreparationAfterModeConfigurationWaitsForTheSameWaveformOwner() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val backend = object : CloudTtsBackend {
            override fun isConfigured() = true
            override fun voiceFor(text: String) = "default"
            override suspend fun synthesize(text: String, voice: String, outputFile: File): Result<Unit> {
                calls++
                started.complete(Unit)
                release.await()
                outputFile.parentFile!!.mkdirs()
                outputFile.writeText("fake audio")
                return Result.success(Unit)
            }
        }
        val listener = object : TtsSynthesizerListener {
            override fun onReady() = Unit
            override fun onInitFailed(status: Int) = Unit
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = Unit
            override fun onError(utteranceId: String) = Unit
        }
        val synth = CloudTtsSynthesizer(ApplicationProvider.getApplicationContext(), backend, listener)
        try {
            synth.configureProsody(ProsodyConfiguration(ProsodyPlanner.Policy(mode = ProsodyPlanner.Mode.PHRASES)))
            val book = Book("deduplicated", "", "", "/tmp", null, emptyList(), 0L)
            val chapter = TtsChapter(0, "Chapter", listOf("A short phrase."))
            val source = chapter.utterances.single()
            val owner = async { synth.generateOne(book, chapter, source) }
            started.await()
            val waiter = async { synth.generateOne(book, chapter, source) }
            yield()
            release.complete(Unit)
            assertTrue(owner.await())
            assertTrue(waiter.await(), "Playback being stopped must not turn valid cache preparation into failure")
            assertEquals(1, calls)
        } finally { synth.shutdown() }
    }
}
