package com.linguareader.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容端点的模型列表探测（复刻自 DeepSeek Harness 的 discovery.ts）。
 *
 * 用设置表单里**当前草稿**的接口地址与 API Key 去 GET {baseUrl}/models，
 * 让用户在保存前就能拉到可用模型并点选填入，而不是凭记忆手敲模型名。
 * 与 dsh 一致：Key 允许为空（Ollama/LM Studio 等本地网关无需鉴权），
 * 响应按实际读到的字节限幅，解析对各家网关的字段别名保持宽容。
 */

/** 端点模型列表里的一条候选。 */
data class DiscoveredModel(
    val id: String,
    val name: String = "",
    val contextWindow: Long? = null,
    val maxTokens: Long? = null
) {
    /** 选择器辅助行文案：把非空的可选信息拼成一行，全空则不出辅助行。 */
    val subtitle: String
        get() = listOf(
            name.takeIf { it.isNotBlank() && !it.equals(id, ignoreCase = true) },
            contextWindow?.let { "ctx ${compactCount(it)}" },
            maxTokens?.let { "out ${compactCount(it)}" }
        ).filterNotNull().joinToString(" · ")

    private fun compactCount(value: Long): String = when {
        value >= 1_000_000 && value % 1_000_000 == 0L -> "${value / 1_000_000}M"
        value >= 1_000 && value % 1_000 == 0L -> "${value / 1_000}K"
        else -> value.toString()
    }
}

/** 接口地址去尾斜杠后拼模型列表路径。按前缀拼接而非 URL 解析，网关带路径前缀（如 …/openai/v1）时不丢段。 */
internal fun listingUrl(baseUrl: String): String =
    "${baseUrl.trim().trimEnd('/')}/models"

/**
 * 探测前的 Key 校验。true = 可用（空串 = 匿名探测本地网关）。false 时界面
 * 用资源文案提示——底层 HttpURLConnection 对非法头只会抛看不出原因的
 * IOException，必须在这里拦下来把原因说清。
 */
internal fun probeKeyUsable(apiKey: String): Boolean {
    val key = apiKey.trim()
    if (key.isEmpty()) return true
    return key.all { it.code in 0x21..0x7E }
}

/**
 * 解析 OpenAI 兼容的 GET /models 响应。字段读取按 dsh discovery.ts 的
 * 宽容规则：无可用 id 的行跳过（单行坏数据不否定整个端点）；展示名与
 * 容量在各家网关的常见别名里取第一个可用值；容量只收正整数。
 * 重复 id 保留首个（选择器列表以 id 为 key，重复会崩溃）。
 */
internal fun parseModelListing(body: JSONObject): List<DiscoveredModel> {
    val data = body.optJSONArray("data")
        ?: throw AiRequestException("端点返回的不是模型列表（缺 data 数组），请手动填写模型名")
    fun capacity(vararg candidates: Any?): Long? =
        candidates.firstOrNull { it is Number && it.toDouble() % 1.0 == 0.0 && it.toDouble() > 0 }
            ?.let { (it as Number).toLong() }
    fun label(vararg candidates: Any?): String? =
        candidates.firstOrNull { it is String && (it as String).isNotEmpty() } as String?
    return (0 until data.length()).mapNotNull { index ->
        val entry = data.optJSONObject(index) ?: return@mapNotNull null
        val id = label(entry.opt("id")) ?: return@mapNotNull null
        DiscoveredModel(
            id = id,
            name = label(entry.opt("name"), entry.opt("display_name")).orEmpty(),
            contextWindow = capacity(entry.opt("context_window"), entry.opt("context_length")),
            maxTokens = capacity(entry.opt("max_output_tokens"), entry.opt("max_tokens"))
        )
    }.distinctBy { it.id.lowercase() }
}

/**
 * 用草稿配置探测端点。HTTP 层与 [OpenAiCompatTranslator] 同一模式；
 * 失败抛 [AiRequestException]，401/403 附带查 Key 提示。
 */
suspend fun discoverModels(baseUrl: String, apiKey: String): List<DiscoveredModel> =
    withContext(Dispatchers.IO) {
        val url = URL(listingUrl(baseUrl))
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            val key = apiKey.trim()
            if (key.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $key")
            }

            val code = connection.responseCode
            val payload = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = payload?.let(::readBounded).orEmpty()
            if (code !in 200..299) {
                val hint = if (code == 401 || code == 403) "；请检查 API Key" else ""
                throw AiRequestException("接口返回 HTTP $code${text.take(200)}$hint")
            }
            val body = try {
                JSONObject(text)
            } catch (error: Exception) {
                throw AiRequestException("接口未返回 JSON：${text.take(200)}")
            }
            parseModelListing(body)
        } finally {
            connection.disconnect()
        }
    }

/**
 * 有界读取：模型列表很小，但地址是用户输入的，按 dsh 的同一理由按实际
 * 读到的字节设 4MB 硬上限，超出即拒绝（截断的 JSON 不可解析，无法降级）。
 */
private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

private fun readBounded(stream: InputStream): String {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    var total = 0
    stream.use { input ->
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) {
                throw AiRequestException("模型列表超过 ${MAX_RESPONSE_BYTES} 字节，已拒绝")
            }
            buffer.write(chunk, 0, read)
        }
    }
    return buffer.toString("UTF-8")
}
