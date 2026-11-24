package com.linguareader.shared.packs

import com.linguareader.shared.tts.TtsCacheKey
import com.linguareader.shared.tts.TtsPipelineContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PackLifecycleContractTest {
    private fun bundle(): JSONObject = JSONObject().put("packId", "bundle").put("type", "bundle")
        .put("version", "1.0").put("nameZh", "测试集合").put("schemaVersion", 1)
        .put("files", JSONArray().put(JSONObject().put("path", "a.lrpack").put("bytes", 1).put("sha256", "a".repeat(64)))
            .put(JSONObject().put("path", "b.lrpack").put("bytes", 1).put("sha256", "b".repeat(64))))
        .put("bundle", JSONObject().put("members", JSONArray().put(member("a.lrpack"))))
    private fun member(path: String) = JSONObject().put("packId", "member").put("type", "voice")
        .put("version", "1.0").put("path", path)

    @Test fun `bundle cannot declare the same package twice through different member files`() {
        val json = bundle()
        json.getJSONObject("bundle").getJSONArray("members").put(member("b.lrpack"))
        assertFalse(PackValidator.validateManifest(PackManifest.parse(json.toString()), 100).ok)
    }

    @Test fun `malformed bundle members cannot be silently dropped`() {
        val json = bundle()
        json.getJSONObject("bundle").getJSONArray("members").put("not a member object")
        assertTrue(runCatching { PackManifest.parse(json.toString()) }.exceptionOrNull() is PackFormatException)
    }

    @Test fun `future registry schema is rejected rather than interpreted as current state`() {
        val json = PackRegistry.EMPTY.toJson().let(::JSONObject).put("version", 999)
        assertTrue(runCatching { PackRegistry.parse(json.toString()) }.exceptionOrNull() is PackFormatException)
    }

    @Test fun `zip entries that resolve to the same output cannot overwrite earlier content`() {
        val root = java.nio.file.Files.createTempDirectory("pack-alias-").toFile()
        try {
            val zip = File(root, "alias.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                listOf("payload.txt", "./payload.txt").forEach { name ->
                    out.putNextEntry(ZipEntry(name)); out.write("data".toByteArray()); out.closeEntry()
                }
            }
            val destination = File(root, "dest").apply { mkdirs() }
            assertTrue(runCatching { SafeZip.extract(zip, destination, 10, 1024) }.isFailure)
        } finally { root.deleteRecursively() }
    }

    @Test fun `real generated audio package passes install validator and resolves canonical cache path`() {
        var search: File? = File(System.getProperty("user.dir")).absoluteFile
        while (search != null && !File(search, "scripts/build_audio_pack.py").isFile) search = search.parentFile
        val generator = File(requireNotNull(search), "scripts/build_audio_pack.py")
        val root = java.nio.file.Files.createTempDirectory("pack-generation-").toFile()
        try {
            val engine = "server:http://fixture.invalid@deadbeef"
            val voice = "fixture.wav"
            val relative = TtsCacheKey.relativePath(0, 0, 0, engine, voice)
            val cache = File(root, "cache")
            File(cache, relative).apply { parentFile.mkdirs(); writeText("fixed audio fixture") }
            val out = File(root, "generated.lrpack")
            val process = ProcessBuilder("python3", generator.path, "--cache", cache.path, "--book-id", "fixture-book",
                "--pack-id", "fixture-audio", "--engine-tag", engine, "--voice", voice, "--out", out.path,
                "--pipeline-version", TtsPipelineContract.VERSION.toString(), "--min-app-version", "0")
                .redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(log, 0, process.waitFor())
            val manifest = ZipFile(out).use { zip -> zip.getInputStream(zip.getEntry("manifest.json"))
                .use { PackManifest.parse(it.readBytes().toString(Charsets.UTF_8)) } }
            val extracted = File(root, "installed").apply { mkdirs() }
            SafeZip.extract(out, extracted, 100, 1024 * 1024)
            val digests = PackValidator.digests(extracted, manifest.files.map { it.path })
            val audioCheck = PackValidator.validateAudioChapters(manifest, digests)
            assertTrue("Generated package rejected by installer: $audioCheck", audioCheck.ok)
            val pack = InstalledPack(PackPaths.directoryFor(PackType.AUDIO, manifest.packId, manifest.version), 1, manifest)
            val packs = File(root, "packs")
            val target = pack.root(packs).apply { parentFile.mkdirs() }
            assertTrue(extracted.renameTo(target))
            assertEquals("fixed audio fixture", AudioPackSource.resolve(PackRegistry(packs = listOf(pack)),
                packs, "fixture-book", relative)?.readText())
        } finally { root.deleteRecursively() }
    }
}
