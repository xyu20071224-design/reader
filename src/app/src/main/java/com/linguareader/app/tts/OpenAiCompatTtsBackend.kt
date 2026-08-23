package com.linguareader.app.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One voice as advertised by a self-hosted server. Everything but [id] is
 * optional: a bare Kokoro id carries no metadata and gets its attributes from
 * [VoiceNaming], while an IndexTTS clone voice may describe itself.
 */
data class ServerVoice(
    val id: String,
    val language: String = "",
    val gender: String = "",
    val style: List<String> = emptyList()
)

/**
 * OpenAI-compatible self-hosted TTS backend (Fish Speech S2 via
 * SGLang-Omni / vLLM-Omni, or any server exposing POST /v1/audio/speech).
 */
class OpenAiCompatTtsBackend(
    private val settings: CloudTtsSettings
) : CloudTtsBackend {
    /** Probe result: null until [refreshCapabilities] ran (optimistic true). */
    @Volatile
    private var probedWholeBookCache: Boolean? = null

    override val supportsWholeBookCache: Boolean
        get() = probedWholeBookCache ?: true

    override fun isConfigured(): Boolean = settings.serverUrl.isNotBlank()

    override suspend fun refreshCapabilities() {
        probedWholeBookCache = runCatching {
            withContext(Dispatchers.IO) {
                val endpoint = settings.serverUrl.trim().trimEnd('/') + "/v1/models"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 4_000
                    connection.readTimeout = 4_000
                    if (settings.serverToken.isNotBlank()) {
                        connection.setRequestProperty(
                            "Authorization",
                            "Bearer ${settings.serverToken}"
                        )
                    }
                    // Non-standard servers (404, empty body) keep the default
                    // (whole-book cache allowed) so nothing regresses.
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext true
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val ids = JSONObject(body).optJSONArray("data")?.let { arr ->
                        (0 until arr.length())
                            .mapNotNull { i -> arr.optJSONObject(i)?.optString("id") }
                    }.orEmpty()
                    val joined = ids.joinToString(" ").lowercase()
                    !SLOW_ENGINE_MARKERS.any { joined.contains(it) }
                } finally {
                    connection.disconnect()
                }
            }
        }.getOrDefault(true)
    }

    override suspend fun synthesize(
        text: String,
        voice: String,
        outputFile: File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = settings.serverUrl.trim().trimEnd('/') + "/v1/audio/speech"
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 120_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                if (settings.serverToken.isNotBlank()) {
                    connection.setRequestProperty(
                        "Authorization",
                        "Bearer ${settings.serverToken}"
                    )
                }
                val body = buildRequestBody(text, voice, settings.serverModel)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    error("合成失败（HTTP ${connection.responseCode}）：${error.take(200)}")
                }
                outputFile.parentFile?.mkdirs()
                connection.inputStream.use { input ->
                    outputFile.outputStream().use { output -> input.copyTo(output) }
                }
                check(outputFile.length() > 0) { "合成结果为空" }
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Per-sentence language routing (M1.5): a self-hosted engine may need one
     * reference voice per language - IndexTTS clones a single speaker per
     * request, so English and Chinese narration use different reference audio.
     * Both blank keeps the previous single-voice behaviour.
     */
    override fun voiceFor(text: String): String {
        val perLanguage = if (TtsLanguage.of(text) == TtsLanguage.CHINESE) {
            settings.serverZhVoice
        } else {
            settings.serverEnVoice
        }
        return perLanguage.ifBlank { settings.serverVoice }.ifBlank { "default" }
    }

    /**
     * Voice ids the server offers (multi-voice M3 音色库).
     *
     * Tries the local Kokoro wrapper (`GET /voices`) first and the
     * Kokoro-FastAPI style endpoint (`GET /v1/audio/voices`) next; a server
     * without either simply yields an empty list and the library falls back to
     * the configured voice ids.
     */
    suspend fun listVoices(): Result<List<ServerVoice>> = withContext(Dispatchers.IO) {
        runCatching {
            val root = settings.serverUrl.trim().trimEnd('/')
            check(root.isNotEmpty()) { "未配置服务器地址" }
            for (path in VOICE_LIST_PATHS) {
                val voices = runCatching { fetchVoices(root + path) }.getOrDefault(emptyList())
                if (voices.isNotEmpty()) return@runCatching voices
            }
            emptyList()
        }
    }

    private fun fetchVoices(endpoint: String): List<ServerVoice> {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            if (settings.serverToken.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer ${settings.serverToken}")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseVoiceList(body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private val VOICE_LIST_PATHS = listOf("/voices", "/v1/audio/voices")

        /**
         * Accepts every shape seen in the wild: `{"voices":[...]}` (local Kokoro
         * and IndexTTS wrappers), `{"data":[{"id":…}]}` (OpenAI style) and a bare
         * array, with entries as plain strings or as objects carrying
         * `id`/`name` plus the optional multi-voice metadata
         * (`language`/`gender`/`style`, M1.5: clone voices describe themselves).
         */
        internal fun parseVoiceList(body: String): List<ServerVoice> {
            val trimmed = body.trim()
            if (trimmed.isEmpty()) return emptyList()
            val array = when {
                trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
                else -> runCatching { JSONObject(trimmed) }.getOrNull()?.let { json ->
                    // voice_profiles carries the multi-voice metadata (IndexTTS
                    // clone voices); voices/data are the plain id lists.
                    json.optJSONArray("voice_profiles")
                        ?: json.optJSONArray("voices")
                        ?: json.optJSONArray("data")
                }
            } ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                when (val item = array.opt(index)) {
                    is String -> item.trim().takeIf { it.isNotEmpty() }?.let { ServerVoice(it) }
                    is JSONObject -> {
                        val id = item.optString("id").ifBlank { item.optString("name") }.trim()
                        if (id.isEmpty()) {
                            null
                        } else {
                            ServerVoice(
                                id = id,
                                language = item.optString("language").trim(),
                                gender = item.optString("gender").trim(),
                                style = item.optJSONArray("style")?.let { styles ->
                                    (0 until styles.length()).mapNotNull { position ->
                                        styles.optString(position).trim().takeIf(String::isNotEmpty)
                                    }
                                }.orEmpty()
                            )
                        }
                    }

                    else -> null
                }
            }.distinctBy { it.id }
        }

        /** Model ids containing any of these markers are treated as slow
         *  engines for which whole-book pre-generation is disabled. */
        private val SLOW_ENGINE_MARKERS = listOf("indextts", "index-tts")

        internal fun buildRequestBody(text: String, voice: String, model: String): String =
            JSONObject()
                .put("model", model.ifBlank { "tts-1" })
                .put("input", text)
                .put("voice", voice.ifBlank { "default" })
                .put("response_format", "mp3")
                .toString()
    }
}
