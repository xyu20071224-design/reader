package com.linguareader.app.tts

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Volcano Engine / Doubao Speech backend (F-151).
 *
 * Uses the V3 HTTP SSE unidirectional streaming endpoint:
 * POST https://openspeech.bytedance.com/api/v3/tts/unidirectional/sse
 *
 * Authentication supports both consoles:
 * - New console: `X-Api-Key: <API Key>` (recommended)
 * - Legacy console: `X-Api-App-Id: <AppID>` + `X-Api-Access-Key: <Token>`
 *
 * Audio comes back as SSE `data:` frames whose JSON `data` field is base64.
 * The model/voice generation is selected with `X-Api-Resource-Id`
 * (`seed-tts-2.0` for Doubao model 2.0 voices, `seed-tts-1.0` for the
 * classic `BV*_streaming` voices).
 */
class VolcanoTtsBackend(
    private val settings: CloudTtsSettings,
    private val endpoint: String = ENDPOINT
) : CloudTtsBackend {
    override fun isConfigured(): Boolean = settings.isConfigured

    override suspend fun synthesize(
        text: String,
        voice: String,
        outputFile: File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 120_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty(
                    "X-Api-Resource-Id",
                    settings.volcResourceId.ifBlank { DEFAULT_RESOURCE_ID }
                )
                connection.setRequestProperty(
                    "X-Api-Request-Id",
                    UUID.randomUUID().toString()
                )
                if (settings.volcApiKey.isNotBlank()) {
                    connection.setRequestProperty("X-Api-Key", settings.volcApiKey)
                } else {
                    connection.setRequestProperty("X-Api-App-Id", settings.volcAppId)
                    connection.setRequestProperty("X-Api-Access-Key", settings.volcToken)
                }
                val body = buildRequestBody(text, voice)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    error("合成失败（HTTP ${connection.responseCode}）：${error.take(200)}")
                }
                val audio = readSseAudio(connection.inputStream)
                check(audio.isNotEmpty()) { "合成结果为空" }
                outputFile.parentFile?.mkdirs()
                outputFile.writeBytes(audio)
            } finally {
                connection.disconnect()
            }
        }
    }

    override fun voiceFor(text: String): String = when {
        text.any(::isHan) ->
            settings.volcZhVoice.ifBlank { DEFAULT_ZH_VOICE }

        else ->
            settings.volcEnVoice.ifBlank { DEFAULT_EN_VOICE }
    }

    companion object {
        const val ENDPOINT = "https://openspeech.bytedance.com/api/v3/tts/unidirectional/sse"
        const val DEFAULT_RESOURCE_ID = "seed-tts-2.0"
        const val DEFAULT_ZH_VOICE = "zh_female_shuangkuaisisi_uranus_bigtts"
        const val DEFAULT_EN_VOICE = "en_female_dacey_uranus_bigtts"

        internal fun buildRequestBody(text: String, voice: String): String {
            val audioParams = JSONObject()
                .put("format", "mp3")
                .put("speech_rate", 0)
                .put("loudness_rate", 0)
                .put("bit_rate", 64000)
            return JSONObject()
                .put("user", JSONObject().put("uid", "linguareader"))
                .put(
                    "req_params",
                    JSONObject()
                        .put("text", text)
                        .put("speaker", voice.ifBlank { DEFAULT_ZH_VOICE })
                        .put("sample_rate", 24000)
                        .put("audio_params", audioParams)
                )
                .toString()
        }

        internal fun readSseAudio(input: InputStream): ByteArray {
            val audio = java.io.ByteArrayOutputStream()
            val reader = input.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                val code = json.optInt("code", 0)
                if (code == 20_000_000) break // end frame
                if (code != 0) {
                    error("合成失败（code=$code）：${json.optString("message")}")
                }
                val data = json.optString("data")
                if (data.isNotEmpty()) {
                    audio.write(Base64.decode(data, Base64.DEFAULT))
                }
            }
            return audio.toByteArray()
        }

        private fun isHan(char: Char): Boolean {
            val code = char.code
            return code in 0x4E00..0x9FFF ||
                code in 0x3400..0x4DBF ||
                code in 0xF900..0xFAFF ||
                code in 0x20000..0x2FA1F
        }
    }
}
