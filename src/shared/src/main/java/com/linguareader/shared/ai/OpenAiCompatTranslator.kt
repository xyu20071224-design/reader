package com.linguareader.shared.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容 chat/completions 线路：DeepSeek 官方 API 与几乎所有聚合商
 * （SiliconFlow/OpenRouter/Groq…）、本地网关（Ollama/LM Studio/vLLM）共用
 * 的形状。原 DeepSeekTranslator 的 HTTP 层；业务逻辑在 [JsonChatTranslator]。
 */
class OpenAiCompatTranslator(
    baseUrl: String,
    apiKey: String,
    model: String,
    displayName: String
) : JsonChatTranslator(baseUrl, apiKey, model, displayName) {

    override val id = "deepseek"

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
        val url = URL("${endpointBaseUrl.trimEnd('/')}/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Authorization", "Bearer $endpointApiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val body = buildRequestBody(endpointModel, system, user, jsonMode, maxTokens)
            connection.outputStream.use { stream ->
                stream.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val payload = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = payload?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw AiRequestException("AI 接口返回 HTTP $code：${text.take(300)}")
            }
            val content = extractReplyContent(JSONObject(text))
            return parseJsonObject(content)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /**
         * Some OpenAI-compatible endpoints (and some DeepSeek models) reject
         * `response_format={"type":"json_object"}` with a 400/422. Retrying
         * without it keeps the feature working; the prompt and parser still
         * recover a JSON object from the reply.
         */
        internal fun shouldRetryWithoutJsonMode(error: Throwable): Boolean =
            error is AiRequestException &&
                error.message?.contains("response_format", ignoreCase = true) == true

        internal fun buildRequestBody(
            model: String,
            system: String,
            user: String,
            jsonMode: Boolean,
            maxTokens: Int?
        ): JSONObject {
            val body = JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
                .put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                })
            if (maxTokens != null) {
                body.put("max_tokens", maxTokens)
            }
            if (jsonMode) {
                body.put("response_format", JSONObject().put("type", "json_object"))
            }
            return body
        }

        internal fun extractReplyContent(body: JSONObject): String = try {
            body.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (error: Exception) {
            throw AiRequestException("AI 返回了无法解析的 JSON：${body.toString().take(200)}")
        }
    }
}
