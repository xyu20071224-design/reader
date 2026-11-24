package com.linguareader.shared.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.concurrent.thread

class ReleaseHttpTransportTest {
    private val canonical = "https://github.com/${ReleaseUrls.REPOSITORY}/releases/download/v2.3.1/test.lrpack"
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val loopback = ReleaseUrlPolicy { it.startsWith("http://127.0.0.1:") }
    private fun serve(body: ByteArray, status: String = "200 OK", extra: String = "", declaredLength: Int = body.size): Pair<ServerSocket, String> {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            runCatching { server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 $status\r\nContent-Length: $declaredLength\r\n$extra" +
                        "Connection: close\r\n\r\n").toByteArray())
                    write(body); flush()
                }
            } }
        }
        return server to "http://127.0.0.1:${server.localPort}/asset"
    }
    private fun directory() = Files.createTempDirectory("release-transfer-").toFile()

    @Test fun `verified transfer returns original bytes with unique local path`() = runBlocking {
        val payload = ByteArray(90_000) { (it % 251).toByte() }
        val (server, url) = serve(payload)
        val dir = directory()
        try {
            val file = HttpReleaseTransport().download(ReleaseAsset("test", "test.lrpack", canonical, url,
                payload.size.toLong(), sha(payload)), dir, loopback, 100_000)
            assertArrayEquals(payload, file.readBytes())
            assertEquals(dir, file.parentFile)
            assertTrue(file.name.endsWith(".lrpack"))
        } finally { server.close(); dir.deleteRecursively() }
    }

    @Test fun `wrong hash never leaves a completed or partial file`() = runBlocking {
        val payload = "payload".toByteArray()
        val (server, url) = serve(payload)
        val dir = directory()
        try {
            val result = runCatching { HttpReleaseTransport().download(ReleaseAsset("test", "test.lrpack", canonical,
                url, payload.size.toLong(), "0".repeat(64)), dir, loopback, 1000) }
            assertTrue(result.isFailure)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { server.close(); dir.deleteRecursively() }
    }

    @Test fun `truncated or oversized asset fails and removes download`() = runBlocking {
        val payload = "payload".toByteArray()
        for (expected in listOf(2L, 20L)) {
            val (server, url) = serve(payload)
            val dir = directory()
            try {
                val result = runCatching { HttpReleaseTransport().download(ReleaseAsset("test", "test.lrpack", canonical,
                    url, expected, sha(payload)), dir, loopback, 1000) }
                assertTrue(result.isFailure)
                assertTrue(dir.listFiles()!!.isEmpty())
            } finally { server.close(); dir.deleteRecursively() }
        }
    }

    @Test fun `cancellation is propagated and partial download removed`() = runBlocking {
        val payload = ByteArray(90_000) { 1 }
        val (server, url) = serve(payload)
        val dir = directory()
        try {
            val error = runCatching { HttpReleaseTransport().download(ReleaseAsset("test", "test.lrpack", canonical,
                url, payload.size.toLong(), sha(payload)), dir, loopback, 100_000) { _, _ ->
                throw CancellationException("user cancelled")
            } }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { server.close(); dir.deleteRecursively() }
    }

    @Test fun `unknown asset size still rejects truncated content length response`() = runBlocking {
        val (server, url) = serve("short".toByteArray(), declaredLength = 50)
        val dir = directory()
        val policy = ReleaseUrlPolicy { it == canonical || loopback.allows(it) }
        val transport = HttpReleaseTransport { URL(url).openConnection() as HttpURLConnection }
        try {
            val result = runCatching { transport.download(ReleaseAsset("github", "test.lrpack", canonical),
                dir, policy, 1000) }
            assertTrue(result.isFailure)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { server.close(); dir.deleteRecursively() }
    }

    @Test fun `redirect outside selected policy is rejected before target connection`() = runBlocking {
        val (server, url) = serve(ByteArray(0), "302 Found", "Location: https://other.invalid/file\r\n")
        try {
            val error = runCatching { HttpReleaseTransport().text(url, loopback, 1024) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error!!.message!!.contains("outside"))
        } finally { server.close() }
    }

    @Test fun `metadata is bounded and malformed UTF8 is not silently replaced`() = runBlocking {
        for ((body, limit) in listOf("too long".toByteArray() to 2L, byteArrayOf(0xc3.toByte(), 0x28) to 1024L)) {
            val (server, url) = serve(body)
            try { assertTrue(runCatching { HttpReleaseTransport().text(url, loopback, limit) }.isFailure) }
            finally { server.close() }
        }
    }
}
