package com.linguareader.app.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI-compatible self-hosted TTS backend (Fish Speech S2 via
 * SGLang-Omni / vLLM-Omni, or any server exposing POST /v1/audio/speech).
 */
class OpenAiCompatTtsBackend(
    private val settings: CloudTtsSettings
) : CloudTtsBackend {
    override val label: String = "自建服务器（OpenAI 兼容）"

    override fun isConfigured(): Boolean = settings.serverUrl.isNotBlank()

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

    override fun voiceFor(text: String): String =
        settings.serverVoice.ifBlank { "default" }

    companion object {
        internal fun buildRequestBody(text: String, voice: String, model: String): String =
            JSONObject()
                .put("model", model.ifBlank { "tts-1" })
                .put("input", text)
                .put("voice", voice.ifBlank { "default" })
                .put("response_format", "mp3")
                .toString()
    }
}
