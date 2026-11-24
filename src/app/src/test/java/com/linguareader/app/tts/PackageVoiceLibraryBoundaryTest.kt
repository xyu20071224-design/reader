package com.linguareader.app.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.packs.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** load is local-only; cached/configured/metadata sources must not masquerade as supported clone voices. */
@RunWith(RobolectricTestRunner::class)
class PackageVoiceLibraryBoundaryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `OPENAI local library excludes reserved package ids from all merged sources and preserves ordinary choices`() {
        val settings = CloudTtsSettings(mode = TtsEngineMode.OPENAI_COMPAT, serverUrl = "http://127.0.0.1:1",
            narratorVoice = "pack:missing/tone", serverVoice = "ordinary-user-voice", dialogueVoice = "pack:missing/dialogue")
        ServerVoiceStore.save(context, settings.serverUrl, listOf(ServerVoice("pack:cached/tone"), ServerVoice("cached-normal")))
        val root = File(context.filesDir, "packs").apply { mkdirs() }
        val registry = File(root, "registry.json")
        val original = registry.takeIf { it.isFile }?.readBytes()
        val metadata = PackManifest("reserved-metadata-fixture", PackType.VOICE, "1.0.0", "测试", "Fixture", 1, 0,
            listOf(PackFileEntry("license.txt", "0".repeat(64), 1)), PackPayload.Voice(listOf(
                PackVoice("pack:metadata/tone", "Invalid reserved metadata", "en", "", emptyList(), "metadata", null),
                PackVoice("metadata-normal", "Ordinary metadata", "en", "", emptyList(), "metadata", null))))
        // The effective registry may contain an old entry made by a prior app version; load must still guard its output.
        // Current manifest parsing rejects reserved metadata keys, so use the actual metadata merge helper for that branch.
        try {
            val library = VoiceLibraryLoader.load(context, settings)
            assertTrue("unsupported clone id must not become available: ${library.voices.map { it.id }}",
                library.voices.none { it.id.startsWith("pack:") })
            assertNotNull(library.byId("ordinary-user-voice"))
            assertNotNull(library.byId("cached-normal"))
            assertEquals("pack:missing/tone", settings.narratorVoice)
            val merged = VoiceLibraryLoader.applyPackMetadata(emptyList(), metadata.voicePayload!!.voices.associateBy { it.key })
            assertTrue("metadata must not reintroduce reserved clone ids", merged.none { it.id.startsWith("pack:") })
            assertTrue(merged.any { it.id == "metadata-normal" })
        } finally {
            if (original == null) registry.delete() else registry.writeBytes(original)
        }
    }
}
