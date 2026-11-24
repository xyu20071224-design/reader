package com.linguareader.app.tts

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.net.Uri
import com.linguareader.app.packs.PackRepository
import com.linguareader.shared.packs.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class PackVoiceConsumptionTest {
    private val missing = "pack:missing/hero"
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `empty library cannot return a missing locked package voice for consumption`() {
        val existing = BookVoiceMap("book", narrator = mapOf("en" to missing), characterVoice = mapOf("Alice" to missing),
            userLocked = setOf("narrator:en", "alice"), engine = "mimo")
        val result = VoiceAssigner.assign("book", listOf(VoiceCharacter("Alice")), VoiceLibrary(engine = "mimo"), existing = existing)
        assertNull(result.narratorFor("en"))
        assertNull(result.voiceFor("Alice"))
        assertEquals(existing.userLocked, result.userLocked)
        assertEquals(missing, existing.voiceFor("Alice"))
    }

    @Test fun `service must not send missing package mapping or manual voice to synthesis`() {
        val controller = Robolectric.buildService(TtsPlaybackService::class.java)
        val service = controller.get()
        fun set(name: String, value: Any) {
            TtsPlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        set("cachedVoiceSettings", CloudTtsSettings(mode = TtsEngineMode.MIMO, mimoApiKey = "fixture-only", narratorVoice = "Mia"))
        set("activeVoiceMap", BookVoiceMap("book", narrator = mapOf("en" to missing)))
        val resolve = TtsPlaybackService::class.java.getDeclaredMethod("resolveVoice", String::class.java, String::class.java)
            .apply { isAccessible = true }
        assertEquals("Mia", resolve.invoke(service, "narrator", "A resource test."))
        set("cachedVoiceSettings", CloudTtsSettings(mode = TtsEngineMode.MIMO, mimoApiKey = "fixture-only", narratorVoice = missing,
            mimoEnVoice = CloudTtsSettings.DEFAULT_MIMO_EN_VOICE))
        assertEquals(CloudTtsSettings.DEFAULT_MIMO_EN_VOICE, resolve.invoke(service, "narrator", "A resource test."))
    }

    @Test fun `effective resolution preserves non package choices and reports distinct missing or unsupported reasons`() {
        val settings = CloudTtsSettings(mode = TtsEngineMode.MIMO, mimoApiKey = "fixture-only", narratorVoice = "Mia", dialogueVoice = "custom-user")
        val missingResult = PackVoiceSelectionPolicy.resolve("narrator", "Hello.", missing, settings) { false }
        assertEquals("Mia", missingResult.voiceId)
        assertEquals(PackVoiceUnavailableReason.MISSING_SAMPLE, missingResult.unavailable[missing])
        val unsupported = PackVoiceSelectionPolicy.resolve("narrator", "Hello.", missing,
            settings.copy(mode = TtsEngineMode.OPENAI_COMPAT)) { true }
        assertEquals(PackVoiceUnavailableReason.UNSUPPORTED_ENGINE, unsupported.unavailable[missing])
        assertEquals("custom-user", PackVoiceSelectionPolicy.resolve("Alice", "Hello.", null, settings) { false }.voiceId)
        assertEquals("Mia", settings.narratorVoice)
        assertEquals("custom-user", settings.dialogueVoice)
    }

    @Test fun `all manual and language default package choices are filtered without editing settings`() {
        val settings = CloudTtsSettings(mode = TtsEngineMode.OPENAI_COMPAT, serverUrl = "http://127.0.0.1", narratorVoice = missing,
            dialogueVoice = missing, serverEnVoice = missing, serverZhVoice = missing, serverVoice = missing)
        assertEquals("default", PackVoiceSelectionPolicy.resolve("narrator", "Hello.", null, settings) { false }.voiceId)
        assertEquals("default", PackVoiceSelectionPolicy.resolve("Alice", "你好。", null, settings) { false }.voiceId)
        val mimo = settings.copy(mode = TtsEngineMode.MIMO, mimoApiKey = "fixture-only", mimoEnVoice = missing, mimoZhVoice = missing)
        assertEquals(CloudTtsSettings.DEFAULT_MIMO_EN_VOICE,
            PackVoiceSelectionPolicy.resolve("narrator", "Hello.", null, mimo) { false }.voiceId)
        assertEquals(CloudTtsSettings.DEFAULT_MIMO_ZH_VOICE,
            PackVoiceSelectionPolicy.resolve("narrator", "你好。", null, mimo) { false }.voiceId)
        assertEquals(missing, settings.serverVoice)
        assertEquals(missing, mimo.mimoEnVoice)
    }

    @Test fun `persistent missing package maps and locks survive effective fallback and restore on reinstallation`() = runBlocking {
        val repository = VoiceMapRepository(context, { listOf(VoiceCharacter("Alice")) })
        val bookId = "missing-pack-intent"
        repository.lock(bookId, "Alice", missing)
        repository.lockNarrator(bookId, "en", missing)
        val original = repository.load(bookId)!!
        val empty = repository.ensureFor(bookId, VoiceLibrary(engine = "mimo"))!!
        assertNull(empty.voiceFor("Alice"))
        assertEquals(original, repository.load(bookId))
        val fallback = repository.ensureFor(bookId, VoiceLibrary(listOf(VoiceInfo("Mia", "en")), "mimo"))!!
        assertEquals("Mia", fallback.voiceFor("Alice"))
        assertEquals(missing, repository.load(bookId)!!.voiceFor("Alice"))
        assertEquals(original.userLocked, repository.load(bookId)!!.userLocked)
        val restored = repository.ensureFor(bookId, VoiceLibrary(listOf(VoiceInfo(missing, "en"), VoiceInfo("Mia", "en")), "mimo"))!!
        assertEquals(missing, restored.voiceFor("Alice"))
        assertEquals(missing, restored.narratorFor("en"))
        assertEquals(original.userLocked, restored.userLocked)
    }

    @Test fun `actual installed sample is usable then missing and restored without clearing user intent`() = runBlocking {
        val packs = PackRepository(context)
        packs.uninstall("voice-consumption-fixture")
        val sample = "RIFF fixed sample".toByteArray()
        val path = "samples/hero.wav"
        val manifest = PackManifest("voice-consumption-fixture", PackType.VOICE, "1.0.0", "测试", "Fixture", 1, 0,
            listOf(PackFileEntry(path, java.security.MessageDigest.getInstance("SHA-256").digest(sample).joinToString("") { "%02x".format(it) }, sample.size.toLong())),
            PackPayload.Voice(listOf(PackVoice("hero", "Hero", "en", "", emptyList(), "clone", path))))
        val zip = File.createTempFile("voice-consumption-", ".lrpack", context.cacheDir)
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("manifest.json")); out.write(manifest.toJson().toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry(path)); out.write(sample); out.closeEntry()
        }
        val selected = "pack:voice-consumption-fixture/hero"
        val settings = CloudTtsSettings(mode = TtsEngineMode.MIMO, mimoApiKey = "fixture-only", narratorVoice = selected)
        fun resolve() = PackVoiceSelectionPolicy.resolve("narrator", "Hello.", selected, settings) { packs.packVoiceSample(it) != null }
        try {
            packs.install(Uri.fromFile(zip)); assertEquals(selected, resolve().voiceId)
            packs.uninstall("voice-consumption-fixture")
            assertEquals(CloudTtsSettings.DEFAULT_MIMO_EN_VOICE, resolve().voiceId)
            assertEquals(PackVoiceUnavailableReason.MISSING_SAMPLE, resolve().unavailable[selected])
            packs.install(Uri.fromFile(zip)); assertEquals(selected, resolve().voiceId)
            assertEquals(selected, settings.narratorVoice)
        } finally { packs.uninstall("voice-consumption-fixture"); zip.delete() }
    }

    @Test fun `explicit unavailable backend synthesis and audition issue zero actual HTTP requests and write no fallback file`() = runBlocking {
        val count = AtomicInteger()
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val accepting = AtomicBoolean(true)
        val worker = thread(isDaemon = true, name = "voice-http-counter") {
            while (accepting.get()) {
                try {
                    server.accept().use { socket ->
                        count.incrementAndGet()
                        socket.getOutputStream().write("HTTP/1.1 500 Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                } catch (_: java.io.IOException) { if (accepting.get()) count.incrementAndGet() }
            }
        }
        val output = File(context.cacheDir, "missing-pack-output.mp3").apply { delete() }
        try {
            val settings = CloudTtsSettings(mode = TtsEngineMode.OPENAI_COMPAT, serverUrl = "http://127.0.0.1:${server.localPort}", serverEnVoice = missing)
            val backend = OpenAiCompatTtsBackend(settings)
            assertEquals("default", backend.voiceFor("Hello."))
            val failure = backend.synthesize("Hello.", missing, output).exceptionOrNull()
            assertTrue(failure is PackVoiceUnavailableException)
            assertEquals(PackVoiceUnavailableReason.UNSUPPORTED_ENGINE, (failure as PackVoiceUnavailableException).reason)
            assertTrue(runCatching { backend.synthesisConfiguration(missing) }.exceptionOrNull() is PackVoiceUnavailableException)
            val audition = VoiceAudition.play(context, settings, missing, "Hello.").exceptionOrNull()
            assertTrue(audition is PackVoiceUnavailableException)
            assertEquals(false, TtsPlaybackController.packVoiceFallback.value!!.usedFallback)
            assertEquals(setOf(PackVoiceUnavailableReason.UNSUPPORTED_ENGINE), TtsPlaybackController.packVoiceFallback.value!!.reasons)
            val mimo = MiMoTtsBackend(CloudTtsSettings(mode = TtsEngineMode.MIMO, mimoEnVoice = missing), context)
            assertEquals(CloudTtsSettings.DEFAULT_MIMO_EN_VOICE, mimo.voiceFor("Hello."))
            assertTrue(mimo.synthesize("Hello.", missing, output).exceptionOrNull() is PackVoiceUnavailableException)
            assertTrue(runCatching { mimo.synthesisConfiguration(missing) }.exceptionOrNull() is PackVoiceUnavailableException)
            assertEquals(0, count.get())
            assertFalse(output.exists())
        } finally { accepting.set(false); server.close(); worker.join(1000); output.delete() }
    }
    @Test fun `network disabled playback treats a package clone as unsupported by the actual system fallback`() {
        val settings = CloudTtsSettings(mode = TtsEngineMode.MIMO, mimoApiKey = "fixture-only", networkAiEnabled = false,
            narratorVoice = missing)
        val result = PackVoiceSelectionPolicy.resolve("narrator", "Hello.", missing, settings) { true }
        assertNull(result.voiceId)
        assertEquals(PackVoiceUnavailableReason.UNSUPPORTED_ENGINE, result.unavailable[missing])
        assertEquals(missing, settings.narratorVoice)
    }

    @Test fun `resource change clears old notice without a network probe or a backend empty result clearing it early`() {
        val reason = PackVoiceUnavailableReason.MISSING_SAMPLE
        PackVoiceFallbackMonitor.report(PackVoiceResolution("Mia", mapOf(missing to reason)))
        PackVoiceFallbackMonitor.report(PackVoiceResolution("Mia"))
        assertEquals(setOf(reason), TtsPlaybackController.packVoiceFallback.value!!.reasons)
        assertTrue(TtsPlaybackController.packVoiceFallback.value!!.usedFallback)
        TtsPlaybackController.onVoiceResourcesChanged()
        assertNull(TtsPlaybackController.packVoiceFallback.value)
    }

}
