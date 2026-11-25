package com.linguareader.shared.packs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RemotePackManifestTest {
    private val address = "https://packs.example/fixture.lrpack"
    private val license = "Original test fixture".toByteArray()
    private val manifest = PackManifest("actual", PackType.VOICE, "1.0.0", "测试", "Fixture", 1, 1,
        listOf(PackFileEntry("README.txt", sha(license), license.size.toLong())),
        PackPayload.Voice(listOf(PackVoice("english", "English", "en", "", emptyList(), PackVoice.MODE_METADATA, null))))
    private fun archive(text: String = manifest.toJson()): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(text.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt")); zip.write(license); zip.closeEntry()
        }
    }.toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun remote(bytes: ByteArray) = RemotePack("fixture.lrpack", address, bytes.size.toLong(), sha(bytes),
        packId = "actual", version = "1.0.0", type = PackType.VOICE, minAppVersion = 1)
    private fun connection(bytes: ByteArray) = object : HttpURLConnection(URL(address)) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getHeaderField(name: String?) = if (name.equals("Content-Length", true)) bytes.size.toString() else null
        override fun getInputStream() = ByteArrayInputStream(bytes)
    }
    private fun <T> directory(action: (File) -> T): T {
        val dir = Files.createTempDirectory("remote-manifest-").toFile()
        return try { action(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun realZipWithDifferentCatalogIdentityIsNeverPublished() = directory { dir -> runBlocking {
        assertTrue(PackValidator.validateManifest(PackManifest.parse(manifest.toJson()), 26).ok)
        val bytes = archive()
        val result = RemotePackClient { connection(bytes) }.download(remote(bytes).copy(packId = "claimed"), dir)
        assertTrue("Catalog claimed a different package than the real ZIP", result.isFailure)
        assertFalse(dir.listFiles().orEmpty().any { it.extension == "lrpack" || it.extension == "part" })
    } }

    @Test fun cachedArchiveStillRequiresCurrentCatalogIdentity() = directory { dir -> runBlocking {
        val bytes = archive()
        var calls = 0
        val client = RemotePackClient { calls++; connection(bytes) }
        val file = client.download(remote(bytes), dir).getOrThrow()
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(file, client.download(remote(bytes), dir).getOrThrow())
        assertTrue(client.download(remote(bytes).copy(version = "2.0.0"), dir).isFailure)
        assertEquals(1, calls)
        assertArrayEquals(bytes, file.readBytes())
    } }

    @Test fun typeAndMinimumVersionMustMatchInnerManifest() = directory { dir -> runBlocking {
        val bytes = archive()
        for (pack in listOf(remote(bytes).copy(type = PackType.DICTIONARY), remote(bytes).copy(minAppVersion = 2))) {
            assertTrue(RemotePackClient { connection(bytes) }.download(pack, dir).isFailure)
        }
        assertFalse(dir.listFiles().orEmpty().any { it.extension == "lrpack" })
    } }

    @Test fun manifestReadIsBoundedEvenWhenZipBytesAreSmall() = directory { dir -> runBlocking {
        val bytes = archive(" ".repeat(RemotePackCatalog.MAX_CATALOG_BYTES + 1))
        assertTrue(RemotePackClient { connection(bytes) }.download(remote(bytes), dir).isFailure)
        assertFalse(dir.listFiles().orEmpty().any { it.extension == "lrpack" })
    } }
}
