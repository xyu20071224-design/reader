package com.linguareader.shared.packs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class RemotePackClientTest {
    private val bytes = "verified resource bytes".toByteArray()
    private fun sha(body: ByteArray) = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    private fun pack() = RemotePack("test.lrpack", "https://62.234.28.97:8443/packs/test.lrpack", bytes.size.toLong(), sha(bytes))
    private fun connection(source: String, body: ByteArray, status: Int = 200, headers: Map<String, String> = emptyMap()): HttpURLConnection =
        object : HttpURLConnection(URL(source)) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = status
            override fun getInputStream(): InputStream = ByteArrayInputStream(body)
            override fun getHeaderField(name: String) = headers[name]
        }
    private fun <T> directory(block: (File) -> T): T {
        val dir = Files.createTempDirectory("pack-client-test-").toFile()
        return try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun downloadedArchivePassesTheExistingInstallationContract() = directory { dir -> runBlocking {
        val payload = "Resource distribution integration fixture".toByteArray()
        val manifest = PackManifest(
            "integration-voice", PackType.VOICE, "1.0.0", "测试音色", "Test voice", 1, 1,
            listOf(PackFileEntry("README.txt", sha(payload), payload.size.toLong())),
            PackPayload.Voice(listOf(PackVoice("english", "English", "en-US", "", emptyList(), PackVoice.MODE_METADATA, null)))
        )
        val archive = File(dir, "fixture.lrpack")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toJson().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt")); zip.write(payload); zip.closeEntry()
        }
        val body = archive.readBytes()
        val remote = RemotePack("fixture.lrpack", pack().downloadUrl, body.size.toLong(), sha(body))
        val downloaded = RemotePackClient { connection(it, body) }.download(remote, File(dir, "downloads")).getOrThrow()
        val parsed = ZipFile(downloaded).use { zip ->
            PackManifest.parse(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
        }
        assertTrue(PackValidator.validateManifest(parsed, 26).ok)
        val destination = File(dir, "installed").apply { mkdirs() }
        val limits = PackLimits.of(parsed.type)
        SafeZip.extract(downloaded, destination, limits.maxEntries, limits.maxUnzippedBytes)
        assertTrue(PackValidator.validateFiles(parsed, destination).ok)
        assertArrayEquals(payload, File(destination, "README.txt").readBytes())
    } }

    @Test fun verifiesWholeFileAndAvoidsRepeatDownload() = directory { dir -> runBlocking {
        var requests = 0
        val client = RemotePackClient { requests++; connection(it, bytes) }
        val file = client.download(pack(), dir).getOrThrow()
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(file, client.download(pack(), dir).getOrThrow())
        assertEquals(1, requests)
    } }

    @Test fun resumesVerifiedPartialWith206() = directory { dir -> runBlocking {
        File(dir, "${pack().sha256}.part").writeBytes(bytes.copyOfRange(0, 5))
        lateinit var request: HttpURLConnection
        val client = RemotePackClient { source ->
            connection(source, bytes.copyOfRange(5, bytes.size), 206,
                mapOf("Content-Range" to "bytes 5-${bytes.lastIndex}/${bytes.size}")).also { request = it }
        }
        assertArrayEquals(bytes, client.download(pack(), dir).getOrThrow().readBytes())
        assertEquals("bytes=5-", request.getRequestProperty("Range"))
    } }

    @Test fun ignoresRangeResponse200ByReplacingInsteadOfAppending() = directory { dir -> runBlocking {
        File(dir, "${pack().sha256}.part").writeBytes(bytes.copyOfRange(0, 5))
        assertArrayEquals(bytes, RemotePackClient { connection(it, bytes) }.download(pack(), dir).getOrThrow().readBytes())
    } }

    @Test fun wrongDigestAndWrongRangeNeverReachInstaller() = directory { dir -> runBlocking {
        assertTrue(RemotePackClient { connection(it, ByteArray(bytes.size)) }.download(pack(), dir).isFailure)
        assertTrue(dir.listFiles().orEmpty().all { it.name == ".remote-pack-locks" })
        File(dir, "${pack().sha256}.part").writeBytes(bytes.copyOfRange(0, 5))
        val client = RemotePackClient { connection(it, bytes.copyOfRange(5, bytes.size), 206,
            mapOf("Content-Range" to "bytes 0-${bytes.lastIndex}/${bytes.size}")) }
        assertTrue(client.download(pack(), dir).isFailure)
        assertTrue(dir.listFiles().orEmpty().all { it.name == ".remote-pack-locks" })
    } }

    @Test fun truncatedResponseKeepsVerifiedVersionPartialForRetry() = directory { dir -> runBlocking {
        assertTrue(RemotePackClient { connection(it, bytes.copyOfRange(0, 5)) }.download(pack(), dir).isFailure)
        assertEquals(5L, File(dir, "${pack().sha256}.part").length())
    } }

    @Test fun mirrorsUseSameContentHashAndRejectHttpsDowngrade() = directory { dir -> runBlocking {
        val mirrorPack = pack().copy(mirrors = listOf("https://mirror.example/test.lrpack"))
        val requests = mutableListOf<String>()
        val client = RemotePackClient { source ->
            requests += source
            if (requests.size == 1) throw IOException("unavailable")
            connection(source, bytes)
        }
        assertArrayEquals(bytes, client.download(mirrorPack, dir).getOrThrow().readBytes())
        assertEquals(2, requests.size)
        dir.listFiles().orEmpty().forEach { it.delete() }
        val downgrade = RemotePackClient { connection(it, ByteArray(0), 302, mapOf("Location" to "http://example.com/test.lrpack")) }
        assertTrue(downgrade.download(pack(), dir).isFailure)
    } }

    @Test fun cancellationPropagatesAndKeepsResumableBytes() = directory { dir -> runBlocking {
        try {
            RemotePackClient { connection(it, bytes) }.download(pack(), dir) { _, _ -> throw CancellationException() }
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals(bytes.size.toLong(), File(dir, "${pack().sha256}.part").length())
    } }

    private fun entry(minimum: Int = 1) = JSONObject()
        .put("packId", "test").put("version", "1.0.0").put("type", "voice")
        .put("name", "test.lrpack").put("minAppVersion", minimum)
        .put("bytes", bytes.size).put("sha256", sha(bytes))
        .put("urls", JSONArray().put(pack().downloadUrl))
    private fun catalog(vararg entries: JSONObject) = JSONObject().put("formatVersion", 1)
        .put("packs", JSONArray(entries.toList())).toString()

    @Test fun independentCatalogChecksCompatibilityAndRequiredIntegrity() {
        assertEquals(1, RemotePackCatalog.parse(catalog(entry()), 26).size)
        assertTrue(RemotePackCatalog.parse(catalog(entry(27)), 26).isEmpty())
        for (invalid in listOf(entry().put("sha256", ""), entry().put("name", "../test.lrpack"),
            entry().put("bytes", 0), entry().put("urls", JSONArray().put("http://example.com/test.lrpack")))) {
            assertTrue(runCatching { RemotePackCatalog.parse(catalog(invalid), 26) }.isFailure)
        }
        assertTrue(runCatching { RemotePackCatalog.parse(catalog(entry(), entry()), 26) }.isFailure)
    }

    @Test fun catalogRequestIsExplicitBoundedAndDoesNotNeedGithub() = runBlocking {
        var calls = 0
        val client = RemotePackClient { source ->
            calls++
            assertEquals(RemotePackCatalog.DEFAULT_URL, source)
            connection(source, catalog(entry()).toByteArray())
        }
        assertEquals(0, calls)
        assertEquals(1, client.fetchCatalog(26).getOrThrow().size)
        assertEquals(1, calls)
        val huge = RemotePackClient { connection(it, ByteArray(RemotePackCatalog.MAX_CATALOG_BYTES + 1)) }
        assertTrue(huge.fetchCatalog(26).isFailure)
    }
}
