package com.linguareader.shared.packs

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real loopback sockets. Only the injected factory maps public HTTPS identities to local HTTP. */
class RemotePackHttpTest {
    private val body = ByteArray(8192) { (it % 251).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun pack(bytes: ByteArray = body, path: String = "/pack") =
        RemotePack("fixture.lrpack", "https://packs.example$path", bytes.size.toLong(), sha(bytes))
    private fun send(exchange: HttpExchange, bytes: ByteArray, status: Int = 200, headers: Map<String, String> = emptyMap(), chunked: Boolean = false) {
        try {
            headers.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
            exchange.sendResponseHeaders(status, if (chunked) 0 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } finally { exchange.close() }
    }
    private class Server(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "remote-pack-http-test").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handler(exchange) }; this.executor = this@Server.executor; start()
        }
        fun client() = RemotePackClient { publicUrl ->
            val uri = URI(publicUrl)
            URL("http://127.0.0.1:${server.address.port}${uri.rawPath}" + (uri.rawQuery?.let { "?$it" } ?: ""))
                .openConnection() as HttpURLConnection
        }
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
    private fun <T> directory(action: (File) -> T): T {
        val dir = Files.createTempDirectory("remote-pack-http-").toFile()
        return try { action(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun actualTruncatedTransferResumesFromAnotherDeclaredMirrorWithTheSameHash() = directory { dir -> runBlocking {
        val ranges = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        Server { request ->
            ranges += request.requestURI.path to request.requestHeaders.getFirst("Range")
            if (request.requestURI.path == "/primary") send(request, body.copyOfRange(0, 17), chunked = true)
            else send(request, body.copyOfRange(17, body.size), 206, mapOf("Content-Range" to "bytes 17-${body.lastIndex}/${body.size}"))
        }.use { server ->
            val remote = pack(path = "/primary").copy(mirrors = listOf("https://mirror.example/secondary"))
            assertArrayEquals(body, server.client().download(remote, dir).getOrThrow().readBytes())
            assertEquals(listOf("/primary" to null, "/secondary" to "bytes=17-"), ranges)
        }
    } }

    @Test fun explicitRetryResumesButA200ResponseReplacesThePartial() = directory { dir -> runBlocking {
        val calls = AtomicInteger()
        val ranges = Collections.synchronizedList(mutableListOf<String?>())
        Server { request ->
            ranges += request.requestHeaders.getFirst("Range")
            if (calls.incrementAndGet() == 1) send(request, body.copyOfRange(0, 19), chunked = true)
            else send(request, body)
        }.use { server ->
            assertTrue(server.client().download(pack(), dir).isFailure)
            assertEquals(19, File(dir, "${sha(body)}.part").length().toInt())
            assertFalse(dir.listFiles().orEmpty().any { it.extension == "lrpack" })
            assertArrayEquals(body, server.client().download(pack(), dir).getOrThrow().readBytes())
            assertEquals(listOf(null, "bytes=19-"), ranges)
        }
    } }

    @Test fun malformed206AndCompressionAreRejectedOverRealHttp() = directory { dir -> runBlocking {
        for (headers in listOf(mapOf("Content-Range" to "bytes 0-0/${body.size}"),
            mapOf("Content-Range" to "bytes 0-${body.lastIndex}/${body.size}", "Content-Encoding" to "gzip"))) {
            Server { send(it, body, 206, headers) }.use { server ->
                assertTrue(server.client().download(pack(), dir).isFailure)
                assertFalse(dir.listFiles().orEmpty().any { it.extension == "lrpack" || it.extension == "part" })
            }
        }
    } }

    @Test fun redirectsStayInTheirDeclaredOriginAndNeverDowngrade() = directory { dir -> runBlocking {
        val paths = Collections.synchronizedList(mutableListOf<String>())
        Server { request ->
            paths += request.requestURI.path
            when (request.requestURI.path) {
                "/redirect" -> send(request, byteArrayOf(1), 302, mapOf("Location" to "/complete"))
                "/escape" -> send(request, byteArrayOf(1), 302, mapOf("Location" to "https://other.example/complete"))
                "/downgrade" -> send(request, byteArrayOf(1), 302, mapOf("Location" to "http://packs.example/complete"))
                else -> send(request, body)
            }
        }.use { server ->
            assertArrayEquals(body, server.client().download(pack(path = "/redirect"), dir).getOrThrow().readBytes())
            for (path in listOf("/escape", "/downgrade")) {
                val remote = pack(path = path).copy(name = path.drop(1) + ".lrpack")
                assertTrue(server.client().download(remote, dir).isFailure)
            }
            assertEquals(listOf("/redirect", "/complete", "/escape", "/downgrade"), paths)
        }
    } }

    @Test fun concurrentClientsPublishOneVerifiedFileWithoutSharingAWritablePartial() = directory { dir -> runBlocking {
        val calls = AtomicInteger()
        val progress = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        Server { calls.incrementAndGet(); send(it, body) }.use { server ->
            val first = async(Dispatchers.IO) { server.client().download(pack(), dir) { _, _ ->
                progress.complete(Unit); check(release.await(5, TimeUnit.SECONDS))
            } }
            try {
                withTimeout(5000) { progress.await() }
                assertFalse(dir.listFiles().orEmpty().any { it.extension == "lrpack" })
                val second = async(Dispatchers.IO) { server.client().download(pack(), dir) }
                // First is deliberately held mid-transfer; a waiter must not finish or open another transfer.
                assertNull(withTimeoutOrNull(150) { second.await() })
                release.countDown()
                val a = first.await().getOrThrow(); val b = second.await().getOrThrow()
                assertEquals(a, b); assertArrayEquals(body, a.readBytes()); assertEquals(1, calls.get())
            } finally { release.countDown() }
        }
    } }

    @Test fun cancelledTransferPropagatesAndItsHashBoundPartialCanBeRetried() = directory { dir -> runBlocking {
        val calls = AtomicInteger()
        Server { calls.incrementAndGet(); send(it, body) }.use { server ->
            try {
                server.client().download(pack(), dir) { _, _ -> throw CancellationException("test cancellation") }
                fail("Cancellation was swallowed")
            } catch (_: CancellationException) { }
            assertEquals(body.size.toLong(), File(dir, "${sha(body)}.part").length())
            assertArrayEquals(body, server.client().download(pack(), dir).getOrThrow().readBytes())
            assertEquals(1, calls.get()) // complete partial is verified and published without another request
        }
    } }

    @Test fun unrelatedContentDoesNotShareALockAndFailedPublicationIsRetryable() = directory { dir -> runBlocking {
        val other = body.copyOf().apply { this[0] = 99 }
        val gate = CyclicBarrier(2)
        Server { request ->
            gate.await(5, TimeUnit.SECONDS)
            send(request, if (request.requestURI.path == "/other") other else body)
        }.use { server ->
            val a = async(Dispatchers.IO) { server.client().download(pack(), dir).getOrThrow() }
            val b = async(Dispatchers.IO) { server.client().download(pack(other, "/other"), dir).getOrThrow() }
            assertArrayEquals(body, a.await().readBytes()); assertArrayEquals(other, b.await().readBytes())
        }
        val blocked = File(dir, "${sha(body)}-blocked.lrpack").apply { mkdir() }
        File(blocked, "keep").writeText("unchanged")
        var calls = 0
        Server { calls++; send(it, body) }.use { server ->
            val remote = pack().copy(name = "blocked.lrpack")
            assertTrue(server.client().download(remote, dir).isFailure)
            assertEquals("unchanged", File(blocked, "keep").readText())
            assertEquals(body.size.toLong(), File(dir, "${sha(body)}.part").length())
            blocked.deleteRecursively()
            assertArrayEquals(body, server.client().download(remote, dir).getOrThrow().readBytes())
            assertEquals(1, calls)
        }
    } }

    @Test fun catalogUsesActualHttpHeadersAndIgnoresUntrustedPublicationHistory() = runBlocking {
        val entry = JSONObject().put("packId", "fixture").put("version", "1.0.0").put("type", "audio")
            .put("minAppVersion", 12).put("bytes", body.size).put("sha256", sha(body)).put("name", "fixture.lrpack")
            .put("urls", JSONArray().put(pack().downloadUrl))
        val json = JSONObject().put("formatVersion", 1).put("packs", JSONArray().put(entry))
            .put("publishedVersions", JSONObject().put("untrusted", "ignored")).toString().toByteArray()
        val paths = Collections.synchronizedList(mutableListOf<String>())
        Server { request -> paths += request.requestURI.path; send(request, json) }.use { server ->
            val client = server.client()
            assertTrue(paths.isEmpty())
            val packs = client.fetchCatalog(26, "https://packs.example/index.json").getOrThrow()
            assertEquals(1, packs.size); assertEquals(PackType.AUDIO, packs.single().type); assertEquals(12, packs.single().minAppVersion)
            assertEquals(listOf("/index.json"), paths)
        }
    }

    @Test fun cancellationWhileWaitingForTheSameContentLockDoesNotAffectTheOwner() = directory { dir -> runBlocking {
        val progress = CompletableDeferred<Unit>()
        val waiterStarted = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        Server { calls.incrementAndGet(); send(it, body) }.use { server ->
            val first = async(Dispatchers.IO) { server.client().download(pack(), dir) { _, _ ->
                progress.complete(Unit); check(release.await(5, TimeUnit.SECONDS))
            } }
            try {
                withTimeout(5000) { progress.await() }
                val waiter = launch(Dispatchers.IO) { waiterStarted.complete(Unit); server.client().download(pack(), dir) }
                waiterStarted.await(); waiter.cancelAndJoin()
                release.countDown()
                assertArrayEquals(body, first.await().getOrThrow().readBytes()); assertEquals(1, calls.get())
            } finally { release.countDown() }
        }
    } }

    @Test fun legacyWithoutDigestNeverResumesAndCancellationLeavesNoUnverifiedPartial() = directory { dir -> runBlocking {
        val ranges = Collections.synchronizedList(mutableListOf<String?>())
        Server { request -> ranges += request.requestHeaders.getFirst("Range"); send(request, body) }.use { server ->
            val remote = pack().copy(sha256 = null)
            try {
                server.client().download(remote, dir) { _, _ -> throw CancellationException("cancel legacy") }
                fail("Cancellation swallowed")
            } catch (_: CancellationException) { }
            assertFalse(dir.listFiles().orEmpty().any { it.extension == "part" || it.extension == "lrpack" })
            assertArrayEquals(body, server.client().download(remote, dir).getOrThrow().readBytes())
            assertEquals(listOf(null, null), ranges)
        }
    } }
}
