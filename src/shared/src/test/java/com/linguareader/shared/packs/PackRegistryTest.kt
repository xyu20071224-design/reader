package com.linguareader.shared.packs

import com.linguareader.shared.importer.ImportSupport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            PackType.BUNDLE -> PackPayload.Bundle(
                listOf(BundleMember(PackType.DICTIONARY, "member-dict", "payload.bin", "1.0.0"))
            )
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

    /**
     * 第四轮审查 7-8：registry 的 `dir` 必须与内嵌 manifest 推导值一致，漂移按损坏处理
     * （否则会静默读错目录：界面显示的版本与实际加载的内容不是同一个包）。
     */
    @Test
    fun `registry entry whose dir disagrees with its manifest is treated as damaged`() {
        val good = installed("ecdict-zh")
        // 对照：正常条目必须能解析
        assertEquals(
            "ecdict-zh",
            PackRegistry.parse(PackRegistry().upsert(good).toJson()).byId("ecdict-zh")?.packId
        )

        // 把 dir 改成与清单推导不符（指向另一个版本）
        val tampered = PackRegistry().upsert(good).toJson()
            .replace("dictionary/ecdict-zh/1.0.0", "dictionary/ecdict-zh/9.9.9")
        assertTrue(tampered.contains("9.9.9"), "字符串替换应生效")

        val error = assertFailsWith<PackFormatException> { PackRegistry.parse(tampered) }
        assertTrue(
            error.message.orEmpty().contains("目录与清单不符"),
            "拒绝原因应说明目录与清单不符：${error.message}"
        )
    }

    @Test
    fun `no active pack means built-in`() {
        assertNull(DictionarySource.resolve(PackRegistry.EMPTY, File("/nonexistent")))
    }

    /**
     * 第四轮审查 6-8：manifest 声明了某文件时，加载要校验实际大小；被截断/替换的
     * 文件不得被当成命中（否则直接播坏音频）。
     */
    @Test
    fun `audio pack resolve rejects truncated file when manifest declares size`() {
        val packsRoot = File.createTempFile("packs-", "").let { it.delete(); it.mkdirs(); it }
        val relative = "0/e1~v1~voice/s1-0.mp3"
        val payloadPath = "audio/$relative"

        fun packWithDeclaredBytes(declared: Long) = InstalledPack(
            dir = PackPaths.directoryFor(PackType.AUDIO, "book1-audio", "1.0.0"),
            installedAt = 1L,
            manifest = manifest("book1-audio", PackType.AUDIO).copy(
                files = listOf(PackFileEntry(payloadPath, "a".repeat(64), declared))
            )
        )

        val file = File(packWithDeclaredBytes(3).root(packsRoot), payloadPath).apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        // 声明 3 字节、实际 3 字节 → 命中
        val ok = PackRegistry().upsert(packWithDeclaredBytes(3))
        assertEquals(file, AudioPackSource.resolve(ok, packsRoot, "book1", relative))

        // 声明 3 字节、实际被截断成 2 字节 → 不命中（降级现场合成）
        file.writeBytes(byteArrayOf(1, 2))
        assertNull(
            AudioPackSource.resolve(ok, packsRoot, "book1", relative),
        )

        // declared < 0（清单未给字节数）时保持原判据，不误伤旧包/合成夹具
        val legacy = PackRegistry().upsert(packWithDeclaredBytes(-1))
        assertEquals(file, AudioPackSource.resolve(legacy, packsRoot, "book1", relative))
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
