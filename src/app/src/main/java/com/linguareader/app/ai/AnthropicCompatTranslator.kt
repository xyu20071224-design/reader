package com.linguareader.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Anthropic 官方 /v1/messages 线路（Claude）。与 OpenAI 兼容形状的差异：
 * 鉴权走 `x-api-key` + `anthropic-version` 头、system 是顶层字段、
 * max_tokens 必填、回复在 `content[].text`。没有 wire 级 JSON 约束，
 * jsonMode 只体现在 prompt 里；解析失败重问一次（BUG-013 同因）。
 */
class AnthropicCompatTranslator(
    baseUrl: String,
    apiKey: String,
    model: String,
    displayName: String
) : JsonChatTranslator(baseUrl, apiKey, model, displayName) {

    override val id = "anthropic"

    override suspend fun chat(
        system: String,
        user: String,
        jsonMode: Boolean,
        readTimeoutMs: Int,
        maxTokens: Int?
    ): JSONObject = withContext(Dispatchers.IO) {
        val first = runCatching { request(system, user, readTimeoutMs, maxTokens) }
        first.getOrElse { error ->
            if (!jsonMode || !JsonChatTranslator.shouldRetryKeepingJsonMode(error)) throw error
            request(system, user, readTimeoutMs, maxTokens)
        }
    }

    private fun request(
        system: String,
        user: String,
        readTimeoutMs: Int,
        maxTokens: Int?
    ): JSONObject {
        val url = URL(buildEndpointUrl(endpointBaseUrl))
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("x-api-key", endpointApiKey)
            connection.setRequestProperty("anthropic-version", API_VERSION)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val body = buildRequestBody(endpointModel, system, user, maxTokens)
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
        const val API_VERSION = "2023-06-01"
        /** Anthropic 强制要求 max_tokens；常规小请求给 4k 足够。 */
        const val DEFAULT_MAX_TOKENS = 4_096

        /** 官方端点形如 https://api.anthropic.com，路径带 /v1 前缀。 */
        internal fun buildEndpointUrl(baseUrl: String): String =
            "${baseUrl.trimEnd('/')}/v1/messages"

        internal fun buildRequestBody(
            model: String,
            system: String,
            user: String,
            maxTokens: Int?
        ): JSONObject = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens ?: DEFAULT_MAX_TOKENS)
            .put("temperature", 0.2)
            .put("system", system)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", user)
            ))

        /** 回复拼 `content[]` 里的 text 块；形状不符按解析失败抛（可触发重试）。 */
        internal fun extractReplyText(body: JSONObject): String {
            val text = buildString {
                val blocks = body.optJSONArray("content") ?: JSONArray()
                for (i in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text") {
                        append(block.optString("text"))
                    }
                }
            }
            if (text.isBlank()) {
                throw AiRequestException("AI 返回了无法解析的 JSON：${body.toString().take(200)}")
            }
            return text
        }
    }
}
