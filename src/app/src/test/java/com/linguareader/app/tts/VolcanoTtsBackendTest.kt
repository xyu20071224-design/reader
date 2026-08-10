package com.linguareader.app.tts

import android.util.Base64
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VolcanoTtsBackendTest {

    private lateinit var server: FakeHttpServer

    @Before
    fun startServer() {
        server = FakeHttpServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.stop()
    }

    private fun backend(
        mode: TtsEngineMode = TtsEngineMode.VOLC,
        apiKey: String = "new-key",
        appId: String = "",
        token: String = "",
        resourceId: String = "seed-tts-2.0",
        zhVoice: String = "",
        enVoice: String = ""
    ): VolcanoTtsBackend = VolcanoTtsBackend(
        CloudTtsSettings(
            mode = mode,
            volcApiKey = apiKey,
            volcAppId = appId,
            volcToken = token,
            volcResourceId = resourceId,
            volcZhVoice = zhVoice,
            volcEnVoice = enVoice
        ),
        endpoint = "http://127.0.0.1:${server.port}/api/v3/tts/unidirectional/sse"
    )

    @Test
    fun requestBodyCarriesTextSpeakerSampleRateAndMp3Format() {
        val body = JSONObject(
            VolcanoTtsBackend.buildRequestBody("Hello 世界", "zh_female_vv_uranus_bigtts")
        )
        val req = body.getJSONObject("req_params")
        val audio = req.getJSONObject("audio_params")

        assertEquals("Hello 世界", req.getString("text"))
        assertEquals("zh_female_vv_uranus_bigtts", req.getString("speaker"))
        assertEquals(24000, req.getInt("sample_rate"))
        assertEquals("mp3", audio.getString("format"))
        assertEquals(0, audio.getInt("speech_rate"))
        assertEquals(0, audio.getInt("loudness_rate"))
        assertEquals(64000, audio.getInt("bit_rate"))
        assertEquals("linguareader", body.getJSONObject("user").getString("uid"))
    }

    @Test
    fun voiceForRoutesChineseToZhAndEnglishToEn() {
        val backend = backend(
            apiKey = "k",
            zhVoice = "zh_male_m191_uranus_bigtts",
            enVoice = "en_male_tim_uranus_bigtts"
        )
        val defaults = backend(apiKey = "k")

        assertEquals("zh_male_m191_uranus_bigtts", backend.voiceFor("你好，世界。"))
        assertEquals("en_male_tim_uranus_bigtts", backend.voiceFor("Hello world."))
        assertEquals(CloudTtsSettings.DEFAULT_VOLC_ZH_VOICE, defaults.voiceFor("你好。"))
        assertEquals(CloudTtsSettings.DEFAULT_VOLC_EN_VOICE, defaults.voiceFor("Hello."))
    }

    @Test
    fun isConfiguredRequiresApiKeyOrLegacyPair() {
        assertFalse(backend(apiKey = "").isConfigured())
        assertFalse(backend(apiKey = "", appId = "app", token = "").isConfigured())
        assertFalse(backend(apiKey = "", appId = "", token = "tok").isConfigured())
        assertTrue(backend(apiKey = "key").isConfigured())
        assertTrue(backend(apiKey = "", appId = "app", token = "tok").isConfigured())
    }

    @Test
    fun synthesizeWritesDecodedAudioFromSseFrames() {
        val first = "frame-one-bytes".toByteArray()
        val second = "frame-two-bytes".toByteArray()
        server.handler = {
            val body = buildString {
                append("data: {\"code\":0,\"data\":\"")
                append(Base64.encodeToString(first, Base64.NO_WRAP))
                append("\"}\n")
                append("event: audio\n")
                append("data: {\"code\":0,\"data\":\"")
                append(Base64.encodeToString(second, Base64.NO_WRAP))
                append("\"}\n")
                append("data: {\"code\":20000000}\n")
            }
            200 to body
        }

        val output = File.createTempFile("volc", ".mp3")
        val result = runBlocking {
            backend().synthesize("Hello", "en_female_dacey_uranus_bigtts", output)
        }

        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        assertEquals(
            "frame-one-bytesframe-two-bytes",
            output.readBytes().toString(Charsets.UTF_8)
        )
        output.delete()
    }

    @Test
    fun synthesizeSendsAuthHeadersAndRequestId() {
        var seenHeaders: Map<String, String>? = null
        var seenBody = ""
        server.handler = { request ->
            seenHeaders = request.headers
            seenBody = request.body
            val audio = Base64.encodeToString("ok".toByteArray(), Base64.NO_WRAP)
            val body = "data: {\"code\":0,\"data\":\"$audio\"}\ndata: {\"code\":20000000}\n"
            200 to body
        }

        val output = File.createTempFile("volc", ".mp3")
        val result = runBlocking {
            backend(apiKey = "secret-key").synthesize("Hi", "en", output)
        }

        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        assertEquals("secret-key", seenHeaders?.get("x-api-key"))
        assertEquals("seed-tts-2.0", seenHeaders?.get("x-api-resource-id"))
        assertFalse(seenHeaders?.get("x-api-request-id").isNullOrBlank())
        assertTrue(seenBody.contains("\"text\":\"Hi\""))
        output.delete()
    }

    @Test
    fun synthesizeUsesLegacyAuthHeadersWhenNoApiKey() {
        var seenHeaders: Map<String, String>? = null
        server.handler = { request ->
            seenHeaders = request.headers
            val audio = Base64.encodeToString("ok".toByteArray(), Base64.NO_WRAP)
            val body = "data: {\"code\":0,\"data\":\"$audio\"}\ndata: {\"code\":20000000}\n"
            200 to body
        }

        val output = File.createTempFile("volc", ".mp3")
        val result = runBlocking {
            backend(apiKey = "", appId = "app-123", token = "tok-456")
                .synthesize("Hi", "en", output)
        }

        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        assertEquals("app-123", seenHeaders?.get("x-api-app-id"))
        assertEquals("tok-456", seenHeaders?.get("x-api-access-key"))
        output.delete()
    }

    @Test
    fun synthesizeFailsOnApiErrorFrame() {
        server.handler = {
            val body = "data: {\"code\":55000000,\"message\":\"speaker mismatch\"}\n"
            200 to body
        }

        val output = File.createTempFile("volc", ".mp3")
        val result = runBlocking {
            backend().synthesize("Hi", "en", output)
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("55000000") == true)
        output.delete()
    }

    @Test
    fun synthesizeFailsOnHttpError() {
        server.handler = { 401 to "unauthorized" }

        val output = File.createTempFile("volc", ".mp3")
        val result = runBlocking {
            backend().synthesize("Hi", "en", output)
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTP 401") == true)
        output.delete()
    }

    @Test
    fun readSseAudioSkipsNonDataLinesAndStopsAtEndFrame() {
        val chunk = "audio-bytes".toByteArray()
        val input = ByteArrayInputStream(
            (
                "event: start\n" +
                    "\n" +
                    "data: {\"code\":0,\"data\":\"${Base64.encodeToString(chunk, Base64.NO_WRAP)}\"}\n" +
                    "data: {\"code\":20000000}\n" +
                    "data: {\"code\":0,\"data\":\"ignored\"}\n"
                ).toByteArray(Charsets.UTF_8)
        )

        assertEquals(
            "audio-bytes",
            VolcanoTtsBackend.readSseAudio(input).toString(Charsets.UTF_8)
        )
    }

    @Test
    fun endpointConstantPointsToOfficialSseUrl() {
        assertEquals(
            "https://openspeech.bytedance.com/api/v3/tts/unidirectional/sse",
            VolcanoTtsBackend.ENDPOINT
        )
    }

    /** Minimal one-request-at-a-time HTTP stub backed by a plain socket. */
    private class FakeHttpServer {
        data class Request(
            val method: String,
            val path: String,
            val headers: Map<String, String>,
            val body: String
        )

        var handler: (Request) -> Pair<Int, String> = { 200 to "" }

        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val thread = thread(start = false, isDaemon = true) { serve() }

        val port: Int get() = serverSocket.localPort

        fun start() {
            thread.start()
        }

        fun stop() {
            runCatching { serverSocket.close() }
            thread.join(2000)
        }

        private fun serve() {
            while (!serverSocket.isClosed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { handle(socket) }
            }
        }

        private fun handle(socket: Socket) {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val body = StringBuilder()
                val buffer = CharArray(1024)
                var remaining = length
                while (remaining > 0) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    body.append(buffer, 0, count)
                    remaining -= count
                }
                val (status, responseBody) = handler(
                    Request(parts[0], parts[1], headers, body.toString())
                )
                val bytes = responseBody.toByteArray(Charsets.UTF_8)
                s.getOutputStream().use { out ->
                    out.write(
                        (
                            "HTTP/1.1 $status ${statusText(status)}\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.UTF_8)
                    )
                    out.write(bytes)
                    out.flush()
                }
            }
        }

        private fun statusText(status: Int): String = when (status) {
            200 -> "OK"
            401 -> "Unauthorized"
            else -> "Error"
        }
    }
}
