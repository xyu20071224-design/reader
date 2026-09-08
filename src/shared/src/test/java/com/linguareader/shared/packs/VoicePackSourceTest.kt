package com.linguareader.shared.packs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoicePackSourceTest {

    private fun packRoot(vararg files: Pair<String, String>): File {
        val dir = File.createTempFile("voice-pack-", "").let { it.delete(); it.mkdirs(); it }
        files.forEach { (path, text) ->
            File(dir, path).apply {
                parentFile?.mkdirs()
                writeText(text)
            }
        }
        return dir
    }

    private fun voicePack(
        packId: String = "voices-1",
        voices: List<PackVoice>,
        sampleBytes: String? = "RIFF..."
    ): InstalledPack {
        val files = buildList {
            if (sampleBytes != null) add(PackFileEntry("samples/hero.wav", "b".repeat(64), sampleBytes.length.toLong()))
        }
        return InstalledPack(
            dir = PackPaths.directoryFor(PackType.VOICE, packId, "1.0.0"),
            installedAt = 0L,
            manifest = PackManifest(
                packId = packId,
                type = PackType.VOICE,
                version = "1.0.0",
                nameZh = "音色包",
                nameEn = "",
                schemaVersion = 1,
                minAppVersion = 0,
                files = files,
                payload = PackPayload.Voice(voices)
            )
        )
    }

    private fun clone(key: String = "hero", sample: String? = "samples/hero.wav") = PackVoice(
        key = key,
        displayName = "英雄",
        language = "en",
        gender = "male",
        style = listOf("deep"),
        mode = PackVoice.MODE_CLONE,
        sample = sample
    )

    private fun metadata(key: String = "narrator.wav") = PackVoice(
        key = key,
        displayName = "旁白",
        language = "en",
        gender = "female",
        style = listOf("calm"),
        mode = PackVoice.MODE_METADATA,
        sample = null
    )

    @Test
    fun `clone voice ids are namespaced and samples resolve inside the pack`() {
        val packsRoot = File.createTempFile("packs-", "").let { it.delete(); it.mkdirs(); it }
        val pack = voicePack(voices = listOf(clone()))
        val registry = PackRegistry().upsert(pack)
        pack.root(packsRoot).apply { mkdirs() }
        File(pack.root(packsRoot), "samples/hero.wav").apply {
            parentFile.mkdirs()
            writeText("RIFF")
        }

        val clones = VoicePackSource.cloneVoices(registry, packsRoot)
        assertEquals(1, clones.size)
        assertEquals("pack:voices-1/hero", clones.first().id)
        assertEquals("samples/hero.wav", clones.first().voice.sample)
        assertTrue(clones.first().sampleFile!!.isFile)
        assertTrue(VoicePackSource.isPackVoice("pack:voices-1/hero"))
        assertFalse(VoicePackSource.isPackVoice("mimo-clone:hero"))
        assertFalse(VoicePackSource.isPackVoice("narrator.wav"))

        assertEquals(
            clones.first().sampleFile,
            VoicePackSource.sampleFile(registry, packsRoot, "pack:voices-1/hero")
        )
        assertNull(VoicePackSource.sampleFile(registry, packsRoot, "pack:missing/hero"))
    }

    @Test
    fun `metadata voices are keyed by their target id and never produce samples`() {
        val packsRoot = File.createTempFile("packs-", "").let { it.delete(); it.mkdirs(); it }
        val registry = PackRegistry().upsert(voicePack(voices = listOf(metadata(), clone())))

        val metadataMap = VoicePackSource.metadata(registry)
        assertEquals(setOf("narrator.wav"), metadataMap.keys)
        assertEquals("female", metadataMap.getValue("narrator.wav").gender)
        // 克隆音色不是 metadata，不进叠加表
        assertNull(metadataMap["hero"])
        assertTrue(VoicePackSource.cloneVoices(registry, packsRoot).isNotEmpty())
    }

    @Test
    fun `missing sample file yields null instead of a stale path`() {
        val packsRoot = File.createTempFile("packs-", "").let { it.delete(); it.mkdirs(); it }
        val registry = PackRegistry().upsert(voicePack(voices = listOf(clone())))
        // 目录存在但没有样本文件
        assertNull(VoicePackSource.cloneVoices(registry, packsRoot).first().sampleFile)
        assertNull(VoicePackSource.sampleFile(registry, packsRoot, "pack:voices-1/hero"))
    }

    @Test
    fun `later installed pack wins for the same metadata target`() {
        val registry = PackRegistry()
            .upsert(voicePack(packId = "a", voices = listOf(metadata().copy(gender = "male"))))
            .upsert(voicePack(packId = "b", voices = listOf(metadata().copy(gender = "female"))))
        assertEquals("female", VoicePackSource.metadata(registry).getValue("narrator.wav").gender)
    }
}
