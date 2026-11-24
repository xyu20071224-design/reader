package com.linguareader.app.packs

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.app.data.TextImporter
import com.linguareader.app.data.WordLookup
import com.linguareader.app.tts.CloudTtsBackend
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.CloudTtsSynthesizer
import com.linguareader.app.tts.TtsEngineMode
import com.linguareader.app.tts.VoiceLibraryLoader
import com.linguareader.shared.tts.TtsCacheKey
import com.linguareader.shared.tts.TtsChapter
import com.linguareader.shared.tts.TtsSynthesizerListener
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Host-generated fixtures are pushed before execution; no device or network is acquired by this test source. */
@RunWith(AndroidJUnit4::class)
class PackLifecycleInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repo: PackRepository
    private lateinit var fixtures: File
    private lateinit var inventory: JSONObject
    private var previousActive: String? = null
    private val owned = setOf("fixture-dictionary", "fixture-audio", "fixture-voices",
        "fixture-bundle-complete", "fixture-bundle-partial")

    @Before fun prepare() = runBlocking {
        check(context.packageName.endsWith(".verify")) { "Run on the isolated verify application only" }
        fixtures = File(context.getExternalFilesDir(null), "pack-fixtures")
        check(File(fixtures, "inventory.json").isFile) { "Push generated device-fixtures to externalFiles/pack-fixtures first" }
        inventory = JSONObject(File(fixtures, "inventory.json").readText())
        repo = PackRepository(context)
        previousActive = repo.registry().activeDictionary?.takeIf { it !in owned }
        cleanOwned()
    }

    @After fun finish() = runBlocking {
        if (::repo.isInitialized) {
            File(repo.packsRoot, "registry.json.tmp").takeIf { it.isDirectory }?.delete()
            cleanOwned()
            repo.setActiveDictionary(previousActive)
        }
    }

    private suspend fun cleanOwned() { owned.forEach { repo.uninstall(it) } }
    private suspend fun install(name: String) = repo.install(Uri.fromFile(File(fixtures, name).also { check(it.isFile) }))
    private suspend fun lookup(dictionary: DictionaryRepository, word: String) = dictionary.lookup(
        WordLookup(word, "The word is $word.", "The word is $word.", 12, 0f, 0f)).entry
    private fun evidence(message: String) { Log.i("PackLifecycleAcceptance", message) }

    @Test fun dictionaryLifecycleConsumesUpdatesAndFallsBackToBuiltIn() = runBlocking {
        val dictionary = DictionaryRepository(context, repo::dictionarySource)
        try {
            install("dictionary-1.0.0.lrpack")
            assertTrue(lookup(dictionary, "packprobe")!!.senses.any { it.text.contains("资源包初版命中") })
            install("dictionary-2.0.0.lrpack"); dictionary.invalidate()
            assertTrue(lookup(dictionary, "packprobe")!!.senses.any { it.text.contains("资源包新版命中") })
            install("dictionary-2.0.0.lrpack"); dictionary.invalidate()
            assertTrue(runCatching { install("dictionary-0.9.0.lrpack") }.isFailure)
            assertEquals("2.0.0", PackRepository(context).registry().byId("fixture-dictionary")!!.version)
            assertTrue(PackRepository(context).verify("fixture-dictionary").ok)
            repo.setActiveDictionary(null); dictionary.invalidate()
            assertNotNull(lookup(dictionary, "hello"))
            repo.setActiveDictionary("fixture-dictionary")
            assertTrue(repo.dictionarySource()!!.delete()); dictionary.invalidate()
            assertNotNull(lookup(dictionary, "hello"))
            assertFalse(repo.verify("fixture-dictionary").ok)
            repo.uninstall("fixture-dictionary"); dictionary.invalidate()
            assertNull(repo.dictionarySource())
            assertNotNull(lookup(dictionary, "hello"))
            evidence("dictionary: v1->v2->same/reopen->reject downgrade->disable/missing/uninstall built-in lookup OK")
        } finally { dictionary.invalidate() }
    }

    @Test fun failedReplacementAndUninstallPreserveRealInstalledFiles() = runBlocking {
        install("voice-1.0.0.lrpack")
        val before = File(repo.packsRoot, "registry.json").readText()
        val sample = repo.packVoiceSample("pack:fixture-voices/tone")!!.readBytes()
        val blocker = File(repo.packsRoot, "registry.json.tmp")
        assertTrue(blocker.mkdir())
        try {
            assertTrue(runCatching { install("voice-1.0.0.lrpack") }.isFailure)
            assertTrue(runCatching { install("voice-2.0.0.lrpack") }.isFailure)
            assertTrue(runCatching { repo.uninstall("fixture-voices") }.isFailure)
            assertEquals(before, File(repo.packsRoot, "registry.json").readText())
            assertArrayEquals(sample, PackRepository(context).packVoiceSample("pack:fixture-voices/tone")!!.readBytes())
            assertTrue(PackRepository(context).verify("fixture-voices").ok)
            assertFalse(File(repo.packsRoot, "voice/fixture-voices/2.0.0").exists())
            assertTrue(File(repo.packsRoot, ".tmp").listFiles().orEmpty().isEmpty())
            evidence("filesystem: registry-write failures preserve existing resource, no new destination or staging")
        } finally { assertTrue(blocker.delete()) }
    }

    @Test fun voiceAndBundleResourcesHaveObservableLocalConsumers() = runBlocking {
        assertTrue(runCatching { install("bundle-partial.lrpack") }.exceptionOrNull()?.message.orEmpty().contains("fixture-voices"))
        assertNotNull(repo.registry().byId("fixture-bundle-partial"))
        assertNotNull(repo.registry().byId("fixture-dictionary"))
        assertNotNull(repo.registry().byId("fixture-audio"))
        assertNull(repo.registry().byId("fixture-voices"))
        install("bundle-complete.lrpack")
        val voice = inventory.getString("voice")
        assertEquals(listOf("fixture-1.0.0"), repo.voiceMetadata()[voice]!!.style)
        assertEquals("RIFF", repo.packVoiceSample("pack:fixture-voices/tone")!!.inputStream().use {
            ByteArray(4).also { bytes -> assertEquals(4, it.read(bytes)) }.toString(Charsets.US_ASCII)
        })
        install("voice-2.0.0.lrpack")
        assertEquals(listOf("fixture-2.0.0"), PackRepository(context).voiceMetadata()[voice]!!.style)
        repo.uninstall("fixture-voices")
        assertNull(PackRepository(context).voiceMetadata()[voice])
        assertNull(PackRepository(context).packVoiceSample("pack:fixture-voices/tone"))
        repo.uninstall("fixture-bundle-complete")
        assertNotNull(repo.registry().byId("fixture-dictionary"))
        assertNotNull(repo.registry().byId("fixture-audio"))
        evidence("bundle: partial keeps dictionary/audio; complete installs clone sample+metadata; uninstall removes voice source, preserves independent members")
    }

    @Test fun audioPackPlaysThroughCloudMediaPlayerWithZeroSynthesisAndFallsBackAfterUninstall() = runBlocking {
        val saved = CloudTtsSettings.load(context)
        val settings = CloudTtsSettings(mode = TtsEngineMode.OPENAI_COMPAT, networkAiEnabled = false,
            serverUrl = "http://127.0.0.1:18765", serverModel = "tts-1", serverVoice = "narrator.wav")
        assertEquals("Generate fixtures with the documented loopback engineTag", VoiceLibraryLoader.engineKey(settings),
            inventory.getString("engineTag"))
        val bookDirectory = File(context.cacheDir, "pack-acceptance-book-${System.nanoTime()}")
        val book = TextImporter(context, bookDirectory).import(Uri.fromFile(File(fixtures, "resource-fixture.txt")))
        assertEquals(inventory.getString("bookId"), book.id)
        val cacheDirectory = File(context.filesDir, "tts_cache/${book.id}")
        cacheDirectory.deleteRecursively()
        val requests = AtomicInteger()
        val allowFixtureSynthesis = AtomicBoolean(false)
        val backend = object : CloudTtsBackend {
            override fun isConfigured() = true
            override fun voiceFor(text: String) = "narrator.wav"
            override suspend fun synthesize(text: String, voice: String, outputFile: File): Result<Unit> {
                requests.incrementAndGet()
                if (!allowFixtureSynthesis.get()) return Result.failure(IllegalStateException("Unexpected synthesis during pack playback"))
                outputFile.parentFile!!.mkdirs()
                File(fixtures, "fixture-audio.mp3").copyTo(outputFile, overwrite = true)
                return Result.success(Unit)
            }
        }
        val played = CountDownLatch(1)
        val starts = AtomicInteger()
        val errors = AtomicInteger()
        val player = CloudTtsSynthesizer(context, backend, object : TtsSynthesizerListener {
            override fun onReady() {}
            override fun onInitFailed(status: Int) { errors.incrementAndGet(); played.countDown() }
            override fun onStart(utteranceId: String) { starts.incrementAndGet() }
            override fun onDone(utteranceId: String) { played.countDown() }
            override fun onError(utteranceId: String) { errors.incrementAndGet(); played.countDown() }
        })
        try {
            check(File(fixtures, "fixture-audio.mp3").isFile) { "Host must include the original valid MP3 for fallback verification" }
            CloudTtsSettings.save(context, settings)
            install("audio-1.0.0.lrpack")
            val relative = TtsCacheKey.relativePath(0, 0, 0, inventory.getString("engineTag"), "narrator.wav")
            assertTrue(repo.audioPackFile(book.id, relative)!!.canonicalPath.startsWith(repo.packsRoot.canonicalPath))
            player.speak("A resource test.", 1f, "${book.id}:0:0:0:1", "narrator.wav")
            assertTrue("MediaPlayer did not finish package playback", played.await(15, TimeUnit.SECONDS))
            assertEquals(0, errors.get())
            assertEquals(1, starts.get())
            assertEquals("Package playback must not request synthesis", 0, requests.get())
            assertFalse(cacheDirectory.walkTopDown().any { it.isFile && it.extension == "mp3" })
            repo.uninstall("fixture-audio")
            assertNull(PackRepository(context).audioPackFile(book.id, relative))
            allowFixtureSynthesis.set(true)
            val prepared = CountDownLatch(1)
            val success = AtomicBoolean(false)
            player.prepareChapter(book, TtsChapter(0, "Fixture", listOf("A resource test.")), { _, _ -> }) {
                success.set(it); prepared.countDown()
            }
            assertTrue("Cloud fallback did not complete", prepared.await(15, TimeUnit.SECONDS))
            assertTrue(success.get())
            assertEquals("Missing package must reach the explicit test synthesis backend once", 1, requests.get())
            assertTrue(File(cacheDirectory, relative).isFile)
            evidence("audio: MediaPlayer start=1 completion=1 errors=0 synthesis=0 with pack; uninstall -> explicit fixture backend synthesis=1 cached")
        } finally {
            player.shutdown()
            CloudTtsSettings.save(context, saved)
            cacheDirectory.deleteRecursively()
            bookDirectory.deleteRecursively()
        }
    }
}
