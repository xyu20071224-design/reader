package com.linguareader.shared.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Gemini 官方 :generateContent 线路。差异：鉴权走 `x-goog-api-key`
 * 头、system 走 `system_instruction`、模型名在路径里（剥掉列表返回的
 * `models/` 前缀）、回复在 `candidates[0].content.parts[].text`。
 * JSON 模式走 `generationConfig.responseMimeType`——旧端点可能拒绝它，
 * 重试时去掉（同 OpenAI 的 response_format 降级策略）。
 */
class GeminiCompatTranslator(
    baseUrl: String,
    apiKey: String,
    model: String,
    displayName: String
) : JsonChatTranslator(baseUrl, apiKey, model, displayName) {

    override val id = "gemini"

    override suspend fun chat(
        system: String,
        user: String,
        jsonMode: Boolean,
        readTimeoutMs: Int,
        maxTokens: Int?
    ): JSONObject = withContext(Dispatchers.IO) {
        val first = runCatching { request(system, user, jsonMode, readTimeoutMs, maxTokens) }
        first.getOrElse { error ->
            if (!jsonMode) throw error
            when {
                shouldRetryWithoutJsonMode(error) ->
                    request(system, user, jsonMode = false, readTimeoutMs, maxTokens)
                // BUG-013: a parse failure means the model ignored the JSON
                // constraint — dropping the constraint on retry guarantees
                // another non-JSON reply. Keep it and ask again.
                JsonChatTranslator.shouldRetryKeepingJsonMode(error) ->
                    request(system, user, jsonMode = true, readTimeoutMs, maxTokens)
                else -> throw error
            }
        }
    }

    private fun request(
        system: String,
        user: String,
        jsonMode: Boolean,
        readTimeoutMs: Int,
        maxTokens: Int?
    ): JSONObject {
        val url = URL(buildEndpointUrl(endpointBaseUrl, endpointModel))
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("x-goog-api-key", endpointApiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val body = buildRequestBody(system, user, jsonMode, maxTokens)
            connection.outputStream.use { stream ->
                stream.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val payload = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = payload?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw AiRequestException("AI 接口返回 HTTP $code：${text.take(300)}")
            }
            val content = extractReplyText(JSONObject(text))
            return parseJsonObject(content)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** 常规小请求的输出上限；整本翻译批次显式传 8192。 */
        const val DEFAULT_MAX_TOKENS = 4_096

        /** 模型名在路径里；剥掉模型列表返回的 `models/` 前缀。 */
        internal fun buildEndpointUrl(baseUrl: String, model: String): String {
            val bareModel = model.trim().removePrefix("models/").trimEnd('/')
            return "${baseUrl.trimEnd('/')}/v1beta/models/$bareModel:generateContent"
        }

        internal fun buildRequestBody(
            system: String,
            user: String,
            jsonMode: Boolean,
            maxTokens: Int?
        ): JSONObject {
            val generationConfig = JSONObject()
                .put("temperature", 0.2)
                .put("maxOutputTokens", maxTokens ?: DEFAULT_MAX_TOKENS)
            if (jsonMode) {
                generationConfig.put("responseMimeType", "application/json")
            }
            return JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", system)
                )))
                .put("contents", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))
                ))
                .put("generationConfig", generationConfig)
        }

        /** 回复拼 `candidates[0].content.parts[]` 里的 text；空缺按解析失败抛。 */
        internal fun extractReplyText(body: JSONObject): String {
            val text = try {
                buildString {
                    val parts = body.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                    for (i in 0 until parts.length()) {
                        append(parts.optJSONObject(i)?.optString("text").orEmpty())
                    }
                }
            } catch (error: Exception) {
                throw AiRequestException("AI 返回了无法解析的 JSON：${body.toString().take(200)}")
            }
            if (text.isBlank()) {
                throw AiRequestException("AI 返回了无法解析的 JSON：${body.toString().take(200)}")
            }
            return text
        }

        /** `responseMimeType` 被端点拒绝时的降级判定（含 REST 下划线拼写）。 */
        internal fun shouldRetryWithoutJsonMode(error: Throwable): Boolean =
            error is AiRequestException && listOf("responseMimeType", "response_mime_type")
                .any { error.message?.contains(it, ignoreCase = true) == true }
    }
}
