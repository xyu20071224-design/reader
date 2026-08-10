package com.linguareader.app.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal REST client for Azure Speech (21Vianet / China regions).
 *
 * - Voice list: GET  https://<region>.tts.speech.azure.cn/cognitiveservices/voices/list
 * - Synthesis: POST https://<region>.tts.speech.azure.cn/cognitiveservices/v1
 */
class AzureSpeechClient(
    private val region: String,
    private val apiKey: String
) {
    private val baseUrl = "https://${region.trim()}.tts.speech.azure.cn"

    suspend fun listVoices(): Result<List<AzureVoice>> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("$baseUrl/cognitiveservices/voices/list").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Ocp-Apim-Subscription-Key", apiKey)
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "获取音色失败（HTTP ${connection.responseCode}）"
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                AzureVoice.parse(JSONArray(body))
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun synthesize(
        text: String,
        voiceShortName: String,
        outputFile: File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("$baseUrl/cognitiveservices/v1").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 60_000
                connection.doOutput = true
                connection.setRequestProperty("Ocp-Apim-Subscription-Key", apiKey)
                connection.setRequestProperty("Content-Type", "application/ssml+xml")
                connection.setRequestProperty(
                    "X-Microsoft-OutputFormat",
                    "audio-24khz-48kbitrate-mono-mp3"
                )
                connection.setRequestProperty("User-Agent", "LinguaReader/1.3.0")
                val ssml = buildSsml(text, voiceShortName)
                connection.outputStream.use { it.write(ssml.toByteArray(Charsets.UTF_8)) }
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

    companion object {
        internal fun buildSsml(text: String, voiceShortName: String): String {
            val escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
            return "<speak version='1.0' xml:lang='en-US'>" +
                "<voice name='$voiceShortName'>$escaped</voice></speak>"
        }
    }
}
