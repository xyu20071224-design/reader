package com.linguareader.shared.packs

import com.linguareader.shared.importer.ImportSupport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackRegistryTest {

    private fun manifest(
        packId: String = "ecdict-zh",
        type: PackType = PackType.DICTIONARY,
        version: String = "1.0.0"
    ): PackManifest = PackManifest(
        packId = packId,
        type = type,
        version = version,
        nameZh = "包 $packId",
        nameEn = "",
        schemaVersion = 1,
        minAppVersion = 0,
        files = listOf(PackFileEntry("payload.bin", "a".repeat(64), 1)),
        payload = when (type) {
            PackType.DICTIONARY -> PackPayload.Dictionary("payload.bin", null, "", "")
            PackType.AUDIO -> PackPayload.Audio("book1", "engine", "voice", 1, listOf(AudioChapter(0, 1, "b".repeat(64))))
            PackType.VOICE -> PackPayload.Voice(listOf(PackVoice("k", "K", "en", "female", emptyList(), PackVoice.MODE_METADATA, null)))
        }
    )

    private fun installed(packId: String = "ecdict-zh", type: PackType = PackType.DICTIONARY, version: String = "1.0.0") =
        InstalledPack(
            dir = PackPaths.directoryFor(type, packId, version),
            installedAt = 123L,
            manifest = manifest(packId, type, version)
        )

    @Test
    fun `registry round-trips through json`() {
        val registry = PackRegistry(activeDictionary = "ecdict-zh")
            .upsert(installed())
            .upsert(installed("voices-x", PackType.VOICE))

        val parsed = PackRegistry.parse(registry.toJson())

        assertEquals(2, parsed.packs.size)
        assertEquals("ecdict-zh", parsed.activeDictionary)
        assertEquals(registry.packs.map { it.packId }.toSet(), parsed.packs.map { it.packId }.toSet())
        assertEquals(PackType.VOICE, parsed.byId("voices-x")?.type)
    }

    @Test
    fun `upsert replaces same pack id and keeps others`() {
        val registry = PackRegistry()
            .upsert(installed(version = "1.0.0"))
            .upsert(installed(version = "2.0.0"))
            .upsert(installed("voices-x", PackType.VOICE))

        assertEquals(2, registry.packs.size)
        assertEquals("2.0.0", registry.byId("ecdict-zh")?.version)
        assertEquals("dictionary/ecdict-zh/2.0.0", registry.byId("ecdict-zh")?.dir)
    }

    @Test
    fun `removing active dictionary clears activation`() {
        val registry = PackRegistry(activeDictionary = "ecdict-zh").upsert(installed())
        val removed = registry.remove("ecdict-zh")
        assertNull(removed.activeDictionary)
        assertTrue(removed.packs.isEmpty())
    }

    @Test
    fun `active dictionary must be a dictionary pack`() {
        val registry = PackRegistry().upsert(installed("voices-x", PackType.VOICE))
        assertNull(registry.withActiveDictionary("voices-x").activeDictionary)
        assertNull(registry.withActiveDictionary("missing").activeDictionary)

        val withDictionary = registry.upsert(installed())
        assertEquals("ecdict-zh", withDictionary.withActiveDictionary("ecdict-zh").activeDictionary)
        assertNull(withDictionary.withActiveDictionary(null).activeDictionary)
    }

    @Test
    fun `dictionary source resolves active pack file and falls back when missing`() {
        val packsRoot = File.createTempFile("packs-", "").let { it.delete(); it.mkdirs(); it }
        val pack = installed()
        val registry = PackRegistry(activeDictionary = pack.packId).upsert(pack)
        val root = pack.root(packsRoot).apply { mkdirs() }
        val payload = File(root, "payload.bin").apply { writeBytes(byteArrayOf(1)) }

        assertEquals(payload, DictionarySource.resolve(registry, packsRoot))

        // 包文件被删 → 静默回退内置（返回 null），查词不会因此哑掉
        payload.delete()
        assertNull(DictionarySource.resolve(registry, packsRoot))
    }

    @Test
    fun `no active pack means built-in`() {
        assertNull(DictionarySource.resolve(PackRegistry.EMPTY, File("/nonexistent")))
    }

    @Test
    fun `audio pack source matches by book and relative path`() {
        val packsRoot = File.createTempFile("packs-", "").let { it.delete(); it.mkdirs(); it }
        val pack = installed("book1-audio", PackType.AUDIO)
        val registry = PackRegistry().upsert(pack)
        val root = pack.root(packsRoot).apply { mkdirs() }
        val file = File(root, "audio/0/e1~v1~voice/s1-0.mp3").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        assertEquals(file, AudioPackSource.resolve(registry, packsRoot, "book1", "0/e1~v1~voice/s1-0.mp3"))
        assertNull(AudioPackSource.resolve(registry, packsRoot, "other", "0/e1~v1~voice/s1-0.mp3"))
        assertNull(AudioPackSource.resolve(registry, packsRoot, "book1", "1/e1~v1~voice/s1-0.mp3"))
    }

    @Test
    fun `validator rejects hash mismatch missing and unlisted files`() {
        val root = File.createTempFile("payload-", "").let { it.delete(); it.mkdirs(); it }
        val payload = File(root, "payload.bin").apply { writeBytes("hello".toByteArray()) }
        val good = PackManifest(
            packId = "p", type = PackType.DICTIONARY, version = "1.0.0",
            nameZh = "p", nameEn = "", schemaVersion = 1, minAppVersion = 0,
            files = listOf(PackFileEntry("payload.bin", ImportSupport.sha256(payload), payload.length())),
            payload = PackPayload.Dictionary("payload.bin", null, "", "")
        )
        assertTrue(PackValidator.validateFiles(good, root).ok)

        val wrongHash = good.copy(
            files = listOf(PackFileEntry("payload.bin", "0".repeat(64), payload.length()))
        )
        assertTrue(!PackValidator.validateFiles(wrongHash, root).ok)

        val wrongSize = good.copy(
            files = listOf(PackFileEntry("payload.bin", ImportSupport.sha256(payload), 999))
        )
        assertTrue(!PackValidator.validateFiles(wrongSize, root).ok)

        File(root, "extra.bin").writeBytes(byteArrayOf(9))
        assertTrue(!PackValidator.validateFiles(good, root).ok)

        File(root, "extra.bin").delete()
        payload.delete()
        assertTrue(!PackValidator.validateFiles(good, root).ok)
    }

    @Test
    fun `manifest file itself is not part of the payload reconciliation`() {
        val root = File.createTempFile("payload-", "").let { it.delete(); it.mkdirs(); it }
        val payload = File(root, "payload.bin").apply { writeBytes("x".toByteArray()) }
        File(root, PackManifest.FILE_NAME).writeText("{}")
        val manifest = PackManifest(
            packId = "p", type = PackType.DICTIONARY, version = "1.0.0",
            nameZh = "p", nameEn = "", schemaVersion = 1, minAppVersion = 0,
            files = listOf(PackFileEntry("payload.bin", ImportSupport.sha256(payload), payload.length())),
            payload = PackPayload.Dictionary("payload.bin", null, "", "")
        )
        assertTrue(PackValidator.validateFiles(manifest, root).ok)
    }
}
