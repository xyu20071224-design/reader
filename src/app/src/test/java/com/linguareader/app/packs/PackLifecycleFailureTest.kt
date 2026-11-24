package com.linguareader.app.packs

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.packs.PackManifest
import com.linguareader.shared.packs.PackHasher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real filesystem publication failures; assertions must pass both in memory and after reopening. */
@RunWith(RobolectricTestRunner::class)
class PackLifecycleFailureTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun clean() { File(context.filesDir, "packs").deleteRecursively() }

    private fun archive(manifest: JSONObject, payloads: Map<String, ByteArray>): File {
        val result = File.createTempFile("pack-lifecycle-", ".lrpack", context.cacheDir)
        ZipOutputStream(result.outputStream()).use { out ->
            (mapOf(PackManifest.FILE_NAME to manifest.toString().toByteArray()) + payloads).forEach { (path, bytes) ->
                out.putNextEntry(ZipEntry(path)); out.write(bytes); out.closeEntry()
            }
        }
        return result
    }
    private fun manifest(id: String, type: String, version: String, files: Map<String, ByteArray>) = JSONObject()
        .put("packId", id).put("type", type).put("version", version).put("nameZh", "测试资源")
        .put("schemaVersion", 1).put("minAppVersion", 0)
        .put("files", JSONArray().apply { files.forEach { (path, data) -> put(JSONObject()
            .put("path", path).put("sha256", PackHasher.sha256Hex(data.toString(Charsets.UTF_8)))
            .put("bytes", data.size)) } })
    private fun voice(id: String = "voices", version: String = "1.0.0", sample: String = "old sample"): File {
        val files = mapOf("samples/hero.wav" to sample.toByteArray())
        return archive(manifest(id, "voice", version, files).put("voice", JSONObject().put("voices", JSONArray()
            .put(JSONObject().put("key", "hero").put("mode", "clone").put("sample", "samples/hero.wav")))), files)
    }
    private fun bundle(member: File, declaredId: String): File {
        val files = mapOf("members/member.lrpack" to member.readBytes())
        // ZIP bytes are arbitrary binary; use their actual digest rather than the text helper.
        val meta = manifest("bundle", "bundle", "1.0.0", files)
        meta.getJSONArray("files").getJSONObject(0).put("sha256",
            com.linguareader.shared.importer.ImportSupport.sha256(member))
        return archive(meta.put("bundle", JSONObject().put("members", JSONArray().put(JSONObject()
            .put("packId", declaredId).put("type", "voice").put("version", "1.0.0")
            .put("path", "members/member.lrpack")))), files)
    }
    private fun install(repo: PackRepository, file: File) = runBlocking { repo.install(Uri.fromFile(file)) }
    private fun blockRegistryWrite(repo: PackRepository) {
        assertTrue(File(repo.packsRoot, "registry.json.tmp").mkdir())
    }

    @Test fun `failed same version replacement keeps old payload and registry usable after restart`() {
        val repo = PackRepository(context)
        install(repo, voice())
        val before = File(repo.packsRoot, "registry.json").readText()
        blockRegistryWrite(repo)
        assertTrue(runCatching { install(repo, voice(sample = "replacement sample")) }.isFailure)
        assertEquals(before, File(repo.packsRoot, "registry.json").readText())
        assertEquals("old sample", PackRepository(context).packVoiceSample("pack:voices/hero")!!.readText())
        assertTrue(PackRepository(context).verify("voices").ok)
    }

    @Test fun `failed upgrade publication leaves no unregistered destination or half install`() {
        val repo = PackRepository(context)
        install(repo, voice())
        blockRegistryWrite(repo)
        assertTrue(runCatching { install(repo, voice(version = "2.0.0")) }.isFailure)
        assertEquals("1.0.0", PackRepository(context).registry().byId("voices")!!.version)
        assertFalse(File(repo.packsRoot, "voice/voices/2.0.0").exists())
        assertTrue(File(repo.packsRoot, ".tmp").listFiles().orEmpty().isEmpty())
        assertTrue(PackRepository(context).verify("voices").ok)
    }

    @Test fun `failed uninstall publication preserves registered pack files`() = runBlocking {
        val repo = PackRepository(context)
        repo.install(Uri.fromFile(voice()))
        blockRegistryWrite(repo)
        assertTrue(runCatching { repo.uninstall("voices") }.isFailure)
        assertNotNull(PackRepository(context).packVoiceSample("pack:voices/hero"))
        assertTrue(PackRepository(context).verify("voices").ok)
    }

    @Test fun `bundle member identity mismatch cannot install a different package`() {
        val repo = PackRepository(context)
        val result = runCatching { install(repo, bundle(voice(id = "unexpected"), "expected")) }
        assertTrue(result.isFailure)
        assertNotNull(repo.registry().byId("bundle"))
        assertNull(repo.registry().byId("unexpected"))
        assertTrue(repo.registry().packs.none { it.packId == "expected" })
    }

    @Test fun `cancellation during source copy cannot publish a pack after cancellation`() = runBlocking {
        val repo = PackRepository(context)
        val job = Job()
        val source = voice().readBytes()
        val uri = Uri.parse("content://pack-fixture/cancel")
        shadowOf(context.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(source) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                job.cancel(CancellationException("cancel while copying"))
                return super.read(buffer, offset, length)
            }
        })
        assertTrue(runCatching { withContext(job) { repo.install(uri) } }.exceptionOrNull() is CancellationException)
        assertTrue(PackRepository(context).registry().packs.isEmpty())
        assertFalse(File(repo.packsRoot, "voice/voices/1.0.0").exists())
        assertTrue(File(repo.packsRoot, ".tmp").listFiles().orEmpty().isEmpty())
    }
    @Test fun `residual cleanup cannot remove the currently installing temporary files`() = runBlocking {
        val repo = PackRepository(context)
        val uri = Uri.parse("content://pack-fixture/cleanup")
        var attempted = false
        shadowOf(context.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(voice().readBytes()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!attempted) { attempted = true; assertEquals(0L, repo.cleanResiduals()) }
                return super.read(buffer, offset, length)
            }
        })
        repo.install(uri)
        assertTrue(attempted)
        assertTrue(repo.verify("voices").ok)
    }

    @Test fun `registry rename failure after payload publication restores same version and cleans new version`() {
        for (incoming in listOf("1.0.0", "2.0.0")) {
            clean()
            val repo = PackRepository(context)
            install(repo, voice())
            val before = File(repo.packsRoot, "registry.json").readText()
            repo.beforeInstallPublication = { assertTrue(File(repo.packsRoot, "registry.json.tmp").delete()) }
            assertTrue(runCatching { install(repo, voice(version = incoming, sample = "new sample")) }.isFailure)
            assertEquals(before, File(repo.packsRoot, "registry.json").readText())
            assertEquals("old sample", PackRepository(context).packVoiceSample("pack:voices/hero")!!.readText())
            assertTrue(PackRepository(context).verify("voices").ok)
            assertTrue(File(repo.packsRoot, ".tmp").listFiles().orEmpty().isEmpty())
            if (incoming == "2.0.0") assertFalse(File(repo.packsRoot, "voice/voices/2.0.0").exists())
        }
    }

    @Test fun `cancellation at payload publication restores original resources`() = runBlocking {
        val repo = PackRepository(context)
        repo.install(Uri.fromFile(voice()))
        val job = Job()
        repo.beforeInstallPublication = { job.cancel(CancellationException("cancel before commit")) }
        assertTrue(runCatching { withContext(job) { repo.install(Uri.fromFile(voice(sample = "new sample"))) } }
            .exceptionOrNull() is CancellationException)
        assertEquals("old sample", PackRepository(context).packVoiceSample("pack:voices/hero")!!.readText())
        assertTrue(PackRepository(context).verify("voices").ok)
        assertTrue(File(repo.packsRoot, ".tmp").listFiles().orEmpty().isEmpty())
    }

}
