package com.linguareader.shared.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 用 JDK 自带的 HttpServer 起一个假后端，验证 HttpSyncApi 的请求/响应映射与错误处理。 */
class HttpSyncApiTest {

    private lateinit var server: HttpServer
    private var lastAuthorization: String? = null
    private var lastPushBody: JSONObject? = null

    private fun startServer(responder: (String, String, String) -> Pair<Int, String>) {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange: HttpExchange ->
            val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            lastAuthorization = exchange.requestHeaders.getFirst("Authorization")
            val status: Int
            val payload: String
            val result = responder(exchange.requestMethod, exchange.requestURI.toString(), body)
            status = result.first
            payload = result.second
            val bytes = payload.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun baseUrl(): String = "http://127.0.0.1:" + server.address.port

    @AfterTest
    fun stopServer() {
        if (::server.isInitialized) server.stop(0)
    }

    @Test
    fun loginStoresTokenAndSendsItOnLaterCalls() = runBlocking {
        startServer { method, path, _ ->
            when {
                path.endsWith("/auth/login") -> 200 to "{\"token\":\"tok-123\",\"expiresAt\":99}"
                path.contains("/changes") && method == "GET" -> 200 to "{\"records\":[],\"nextSeq\":7,\"hasMore\":false}"
                else -> 404 to "{\"error\":{\"code\":\"not_found\",\"message\":\"nope\"}}"
            }
        }
        val api = HttpSyncApi(baseUrl())
        assertEquals("tok-123", api.login("alice", "secret-password"))
        assertEquals("tok-123", api.currentToken())

        val page = api.pull(0)
        assertEquals(7L, page.nextSeq)
        assertEquals("Bearer tok-123", lastAuthorization)
    }

    @Test
    fun pushParsesAppliedAndConflicts() = runBlocking {
        startServer { _, path, body ->
            lastPushBody = JSONObject(body)
            val response = JSONObject()
                .put("applied", org.json.JSONArray().put(SyncRecord("progress", "book-1", 100, 0, JSONObject().put("progress", 0.5)).toJson()))
                .put("conflicts", org.json.JSONArray().put(SyncRecord("progress", "book-2", 200, 0, JSONObject().put("progress", 0.9)).toJson()))
                .put("nextSeq", 2)
            assertTrue(path.endsWith("/changes"))
            200 to response.toString()
        }
        val api = HttpSyncApi(baseUrl(), token = "tok")
        val result = api.push(listOf(SyncRecord("progress", "book-1", 100, 0, JSONObject().put("progress", 0.5))))

        assertEquals(1, result.applied.size)
        assertEquals(1, result.conflicts.size)
        assertEquals(200L, result.conflicts.single().updatedAt)
        assertEquals("book-1", lastPushBody!!.getJSONArray("records").getJSONObject(0).getString("id"))
    }

    @Test
    fun nonSuccessStatusBecomesSyncExceptionWithCode() = runBlocking {
        startServer { _, _, _ -> 401 to "{\"error\":{\"code\":\"bad_credentials\",\"message\":\"no\"}}" }
        val api = HttpSyncApi(baseUrl())
        val error = assertFailsWith<SyncException> { api.login("alice", "wrong") }
        assertEquals("bad_credentials", error.message)
        assertEquals(401, error.status)
    }

    @Test
    fun unreachableHostBecomesLocalFailureNotCrash() = runBlocking {
        val api = HttpSyncApi("http://127.0.0.1:1")
        val error = assertFailsWith<SyncException> { api.pull(0) }
        assertEquals(0, error.status)
    }

    @Test
    fun fingerprintHelpersMatchOpensslFormat() {
        val hex = HttpSyncApi.sha256Hex("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
        assertEquals(hex, HttpSyncApi.normalizeFingerprint("BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD"))
    }
}
