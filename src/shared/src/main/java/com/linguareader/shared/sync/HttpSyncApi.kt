package com.linguareader.shared.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * [SyncApi] 的 HTTP 实现：java.net.HttpURLConnection + org.json，**不引入任何新依赖**
 * （与 GitHubUpdateChecker 同款做法）。
 *
 * D3：服务端只有 IPv4、无域名、自签证书 —— 因此支持**证书指纹固定**
 * （[pinnedCertSha256] 非空时启用）。指纹匹配时不依赖主机名校验，这是自签场景的必要让步；
 * 指纹不符直接拒绝连接。
 */
class HttpSyncApi(
    baseUrl: String,
    private var token: String = "",
    private val pinnedCertSha256: String = "",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000
) : SyncApi {

    private val base = baseUrl.trim().trimEnd('/')

    fun currentToken(): String = token

    override fun setToken(token: String) {
        this.token = token
    }

    override suspend fun login(username: String, password: String): String {
        val body = JSONObject().put("username", username).put("password", password)
        val json = request("POST", "/api/v1/auth/login", body)
        token = json.optString("token")
        if (token.isBlank()) throw SyncException("登录响应缺少 token", 0)
        return token
    }

    override suspend fun pull(since: Long, limit: Int): PullPage {
        val json = request("GET", "/api/v1/changes?since=$since&limit=$limit", null)
        return parsePage(json)
    }

    override suspend fun push(records: List<SyncRecord>): PushResult {
        val body = JSONObject()
            .put("baseSeq", 0)
            .put("records", SyncRecord.listToJson(records))
        val json = request("POST", "/api/v1/changes", body)
        return PushResult(
            applied = SyncRecord.listFromJson(json.optJSONArray("applied")),
            conflicts = SyncRecord.listFromJson(json.optJSONArray("conflicts")),
            nextSeq = json.optLong("nextSeq")
        )
    }

    override suspend fun fullState(): PullPage {
        val json = request("GET", "/api/v1/state", null)
        return parsePage(json)
    }

    private fun parsePage(json: JSONObject): PullPage = PullPage(
        records = SyncRecord.listFromJson(json.optJSONArray("records")),
        nextSeq = json.optLong("nextSeq"),
        hasMore = json.optBoolean("hasMore")
    )

    private suspend fun request(method: String, path: String, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = (URL(base + path).openConnection() as HttpURLConnection)
            try {
                applyPinning(connection)
                connection.requestMethod = method
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer " + token)
                }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val code = runCatching { JSONObject(text).optJSONObject("error")?.optString("code") }.getOrNull()
                    throw SyncException(code ?: "HTTP $status", status)
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (error: SyncException) {
                throw error
            } catch (error: Exception) {
                // 网络不可达/超时/证书不符都归为本地失败，outbox 保留，等下次重试。
                throw SyncException(error.message ?: error.javaClass.simpleName, 0)
            } finally {
                connection.disconnect()
            }
        }

    private fun applyPinning(connection: HttpURLConnection) {
        if (connection !is HttpsURLConnection || pinnedCertSha256.isBlank()) return
        val expected = normalizeFingerprint(pinnedCertSha256)
        connection.sslSocketFactory = pinnedContext(expected).socketFactory
        // 指纹已固定，主机名（这里是 IP）不再作为信任依据。
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }

    private fun pinnedContext(expectedHex: String): SSLContext {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
                val actual = sha256Hex(leaf.encoded)
                if (!actual.equals(expectedHex, ignoreCase = true)) {
                    throw CertificateException("server certificate fingerprint mismatch: $actual")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trust), SecureRandom())
        return context
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
                val value = byte.toInt() and 0xFF
                if (value < 16) "0" + value.toString(16) else value.toString(16)
            }

        /** 兼容 openssl -fingerprint 的 AA:BB:... 形式与裸 hex。 */
        fun normalizeFingerprint(raw: String): String =
            raw.replace(":", "").replace(" ", "").trim().lowercase()

        fun parseArray(text: String): JSONArray = JSONArray(text)
    }
}
