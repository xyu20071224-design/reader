package com.linguareader.app.tts

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.packs.PackRepository
import com.linguareader.shared.data.Book
import com.linguareader.shared.packs.PackHasher
import com.linguareader.shared.packs.PackManifest
import com.linguareader.shared.tts.PlannedSpeechRequest
import com.linguareader.shared.tts.ProsodyConfiguration
import com.linguareader.shared.tts.ProsodyFallbackReason
import com.linguareader.shared.tts.ProsodyPlanner
import com.linguareader.shared.tts.ProsodyPlanningContext
import com.linguareader.shared.tts.TtsCacheKey
import com.linguareader.shared.tts.TtsChapter
import com.linguareader.shared.tts.TtsPipelineContract
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudProsodyPlaybackTest {
    private class Backend : CloudTtsBackend {
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, File>>())
        var synthesisVersion = "voice-content-a"
        override fun isConfigured() = true
        override fun voiceFor(text: String) = "default"
        override suspend fun synthesisConfiguration(voice: String) = synthesisVersion
        override suspend fun synthesize(text: String, voice: String, outputFile: File): Result<Unit> {
            requests += text to outputFile
            outputFile.parentFile!!.mkdirs()
            outputFile.writeText("fake audio")
            return Result.success(Unit)
        }
    }
    private class Listener : TtsSynthesizerListener {
        val starts = mutableListOf<String>()
        val dones = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val modes = mutableListOf<Pair<ProsodyPlanner.Mode, ProsodyFallbackReason?>>()
        override fun onReady() = Unit
        override fun onInitFailed(status: Int) = Unit
        override fun onStart(utteranceId: String) { starts += utteranceId }
        override fun onDone(utteranceId: String) { dones += utteranceId }
        override fun onError(utteranceId: String) { errors += utteranceId }
        override fun onProsody(utteranceId: String, mode: ProsodyPlanner.Mode, reason: ProsodyFallbackReason?) { modes += mode to reason }
    }
    private val configuration = ProsodyConfiguration(ProsodyPlanner.Policy(mode = ProsodyPlanner.Mode.PHRASES, minimumPhraseChars = 5))
    private val chapter = TtsChapter(0, "Chapter", listOf("This is a longer first clause, followed by the second longer clause."))
    private val source get() = chapter.utterances.single()
    private fun book(id: String) = Book(id, "Test", "", "/tmp", null, emptyList(), 0L)
    private fun request(config: ProsodyConfiguration = configuration, rate: Float = 1f) = PlannedSpeechRequest(
        source, ProsodyPlanner.plan(source, rate, config.policy, ProsodyPlanningContext(chapter.blocks).protectedRanges(source)), config
    )
    private fun awaitDone(listener: Listener, expected: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (listener.dones.size < expected && listener.errors.isEmpty() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            Thread.sleep(10)
        }
        assertTrue(listener.errors.isEmpty(), "Fake-media playback reported ${listener.errors}")
        assertEquals(expected, listener.dones.size, "Parent request did not finish")
    }
    private fun media() { ShadowMediaPlayer.setMediaInfoProvider { _ -> ShadowMediaPlayer.MediaInfo(10, 0) } }

    @Test fun preparationAndPlaybackUseOnlyPhraseFilesAndRateChangeDoesNotResynthesize() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = Backend()
        val listener = Listener()
        val synth = CloudTtsSynthesizer(context, backend, listener)
        try {
            media()
            synth.configureProsody(configuration)
            assertTrue(synth.generateOne(book("phrase-book"), chapter, source))
            assertEquals(request().units.map { it.utterance.text }, backend.requests.map { it.first })
            assertTrue(backend.requests.all { it.second.parentFile!!.name.startsWith("prosody-v1-") })
            val tag = VoiceLibraryLoader.engineKey(CloudTtsSettings.load(context))
            assertFalse(TtsAudioCache(context).fileFor("phrase-book", 0, 0, 0, "default", tag).exists())
            synth.speakPlanned(request(), "phrase-book:0:0:0:1")
            awaitDone(listener, 1)
            val count = backend.requests.size
            synth.speakPlanned(request(rate = 1.5f), "phrase-book:0:0:0:2")
            awaitDone(listener, 2)
            assertEquals(count, backend.requests.size)
            assertEquals(listOf("phrase-book:0:0:0:1", "phrase-book:0:0:0:2"), listener.starts)
        } finally { synth.shutdown() }
    }

    @Test fun anExistingAudioPackFallsBackToOriginalWithoutAnySynthesis() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installPack(context, "packed-book")
        val backend = Backend()
        val listener = Listener()
        val synth = CloudTtsSynthesizer(context, backend, listener)
        try {
            media()
            synth.configureProsody(configuration)
            assertTrue(synth.generateOne(book("packed-book"), chapter, source))
            synth.speakPlanned(request(), "packed-book:0:0:0:1")
            awaitDone(listener, 1)
            assertTrue(backend.requests.isEmpty())
            assertEquals(1, listener.modes.size)
            assertEquals(ProsodyPlanner.Mode.ORIGINAL, listener.modes.single().first)
            assertEquals(ProsodyFallbackReason.AUDIO_PACK_ORIGINAL, listener.modes.single().second)
            assertEquals(0L, TtsAudioCache(context).totalBytes())
        } finally { synth.shutdown() }
    }

    @Test fun changedBackendConfigurationCannotReuseAnOldPhraseButLocalRateCan() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = Backend()
        val listener = Listener()
        val synth = CloudTtsSynthesizer(context, backend, listener)
        try {
            media()
            synth.configureProsody(configuration)
            assertTrue(synth.generateOne(book("config-book"), chapter, source))
            val first = backend.requests.map { it.second }.toSet()
            backend.synthesisVersion = "voice-content-b"
            assertTrue(synth.generateOne(book("config-book"), chapter, source))
            synth.speakPlanned(request(), "config-book:0:0:0:1")
            awaitDone(listener, 1)
            val second = backend.requests.drop(first.size).map { it.second }.toSet()
            assertEquals(first.size, second.size, "A changed backend configuration must synthesize each part once")
            assertTrue(first.intersect(second).isEmpty())
            val count = backend.requests.size
            synth.speakPlanned(request(rate = 1.4f), "config-book:0:0:0:2")
            awaitDone(listener, 2)
            assertEquals(count, backend.requests.size, "Playback speed must reuse the same audio")
        } finally { synth.shutdown() }
    }

    @Test fun onlyExplicitRegenerationPermissionMayBypassThePack() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installPack(context, "explicit-book")
        val backend = Backend()
        val listener = Listener()
        val synth = CloudTtsSynthesizer(context, backend, listener)
        try {
            media()
            val explicit = configuration.copy(allowAudioPackRegeneration = true)
            synth.configureProsody(explicit)
            assertTrue(synth.generateOne(book("explicit-book"), chapter, source))
            assertEquals(request(explicit).units.size, backend.requests.size)
            synth.speakPlanned(request(explicit), "explicit-book:0:0:0:1")
            awaitDone(listener, 1)
            assertEquals(request(explicit).units.size, backend.requests.size)
            assertEquals(1, listener.modes.size)
            assertEquals(ProsodyPlanner.Mode.PHRASES, listener.modes.single().first)
            assertNull(listener.modes.single().second)
            val packed = synth.packFile("explicit-book", 0, 0, 0, "default")
            assertTrue(packed != null && packed.isFile)
            synth.configureProsody(ProsodyConfiguration())
            assertTrue(synth.generateOne(book("explicit-book"), chapter, source))
            assertEquals(request(explicit).units.size, backend.requests.size)
        } finally { synth.shutdown() }
    }

    @Test fun wholeBookPreparationDoesNotAlsoGenerateParentAudioInPhraseMode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = Backend()
        val synth = CloudTtsSynthesizer(context, backend, Listener())
        try {
            synth.configureProsody(configuration)
            var result: Boolean? = null
            synth.prepareBook(book("prepared-book"), 1, { chapter }, { _, _ -> }) { result = it }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (result == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals(true, result)
            assertEquals(request().units.map { it.utterance.text }.toSet(), backend.requests.map { it.first }.toSet())
            assertEquals(request().units.size, backend.requests.size)
            assertTrue(backend.requests.all { it.second.parentFile!!.name.startsWith("prosody-v1-") })
        } finally { synth.shutdown() }
    }

    private suspend fun installPack(context: Context, bookId: String) {
        val tag = VoiceLibraryLoader.engineKey(CloudTtsSettings.load(context))
        val path = "audio/" + TtsCacheKey.relativePath(0, 0, 0, tag, "default")
        val staging = File.createTempFile("prosody-pack-", "").let { it.delete(); it.mkdirs(); it }
        val payload = File(staging, path).apply { parentFile!!.mkdirs(); writeText("fake packed audio") }
        val hash = com.linguareader.shared.importer.ImportSupport.sha256(payload)
        val manifest = JSONObject().put("packId", "prosody-test").put("type", "audio").put("version", "1.0.0")
            .put("nameZh", "测试音频").put("schemaVersion", 1).put("minAppVersion", 0)
            .put("files", JSONArray().put(JSONObject().put("path", path).put("sha256", hash).put("bytes", payload.length())))
            .put("audio", JSONObject().put("bookId", bookId).put("engineTag", tag).put("voice", "default")
                .put("pipelineVersion", TtsPipelineContract.VERSION)
                .put("chapters", JSONArray().put(JSONObject().put("index", 0).put("files", 1)
                    .put("treeSha256", PackHasher.treeSha256("audio/0", mapOf(path to hash))))))
        val zip = File(staging, "pack.lrpack")
        ZipOutputStream(zip.outputStream()).use { output ->
            output.putNextEntry(ZipEntry(PackManifest.FILE_NAME)); output.write(manifest.toString().toByteArray()); output.closeEntry()
            output.putNextEntry(ZipEntry(path)); output.write(payload.readBytes()); output.closeEntry()
        }
        PackRepository(context, appVersionCode = 26).install(Uri.fromFile(zip))
    }
}
