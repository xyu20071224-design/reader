package com.linguareader.shared.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/** API23 trap: newer method must never be reached, even though this test host has that JDK method. */
class ReleaseHttpApi23Test {
    private val url = "https://github.com/${ReleaseUrls.REPOSITORY}/releases/download/v1.0.0/a.bin"
    private fun connection(header: String?, body: ByteArray, fallback: Int = -1) = object : HttpURLConnection(URL(url)) {
        var reads = 0
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getHeaderField(name: String?) = if (name.equals("Content-Length", true)) header else null
        override fun getHeaderFields(): Map<String, List<String>> = header?.let { mapOf("Content-Length" to listOf(it)) }.orEmpty()
        override fun getContentLength() = fallback
        override fun getContentLengthLong(): Long = throw NoSuchMethodError("API23 has no getContentLengthLong")
        override fun getInputStream(): ByteArrayInputStream { reads++; return ByteArrayInputStream(body) }
    }

    @Test fun `metadata uses API1 headers and still rejects truncated responses`() = runBlocking {
        val complete = connection("2", "{}".toByteArray())
        assertEquals("{}", HttpReleaseTransport { complete }.text(url, ReleaseUrlPolicy { true }, 100).body)
        val short = connection("5", "{}".toByteArray())
        val failure = runCatching { HttpReleaseTransport { short }.text(url, ReleaseUrlPolicy { true }, 100) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("Truncated"))
    }

    @Test fun `download handles long unknown and truncated lengths without invoking API24`() = runBlocking {
        val directory = Files.createTempDirectory("api23-release-").toFile()
        val payload = byteArrayOf(1, 2, 3)
        val asset = ReleaseAsset("github:${ReleaseUrls.REPOSITORY}", "a.bin", url)
        try {
            val good = connection("3", payload)
            val file = HttpReleaseTransport { good }.download(asset, directory, ReleaseUrlPolicy { true }, 100)
            assertArrayEquals(payload, file.readBytes()); file.delete()
            val huge = connection("4294967296", payload)
            assertTrue(runCatching { HttpReleaseTransport { huge }.download(asset, directory, ReleaseUrlPolicy { true }, 100) }.exceptionOrNull() is IllegalStateException)
            assertEquals(0, huge.reads)
            val short = connection("9", payload)
            assertTrue(runCatching { HttpReleaseTransport { short }.download(asset, directory, ReleaseUrlPolicy { true }, 100) }.exceptionOrNull() is IllegalStateException)
            assertTrue(directory.listFiles()!!.isEmpty())
            for (header in listOf(null, "-1", "invalid")) {
                val unknown = connection(header, payload)
                val downloaded = HttpReleaseTransport { unknown }.download(asset, directory, ReleaseUrlPolicy { true }, 100)
                assertArrayEquals(payload, downloaded.readBytes()); downloaded.delete()
            }
        } finally { directory.deleteRecursively() }
    }
}
