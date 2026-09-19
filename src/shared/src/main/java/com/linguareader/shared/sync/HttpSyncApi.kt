package com.linguareader.shared.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
) : SyncApi, SyncBlobApi {

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

    // ---------- 书籍正文（blob）----------

    override suspend fun listBlobs(): List<BlobInfo> {
        val json = request("GET", "/api/v1/books", null)
        val array = json.optJSONArray("books") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let {
                BlobInfo(it.optString("bookId"), it.optLong("size"), it.optString("sha256"))
            }
        }
    }

    override suspend fun blobStatus(bookId: String): BlobStatus {
        val response = sendRaw("HEAD", blobPath(bookId), null, emptyMap())
        ensureSuccess(response)
        return BlobStatus(
            complete = response.headers["x-blob-complete"] == "1",
            uploaded = response.headers["x-blob-size"]?.toLongOrNull() ?: 0L
        )
    }

    override suspend fun uploadBlob(bookId: String, file: File, chunkSize: Int): BlobStatus {
        val path = blobPath(bookId)
        val digest = sha256HexOf(file)
        var status = blobStatus(bookId)
        if (status.complete) return status
        val total = file.length()
        RandomAccessFile(file, "r").use { handle ->
            handle.seek(status.uploaded)
            val buffer = ByteArray(chunkSize)
            while (status.uploaded < total) {
                val read = handle.read(buffer)
                if (read <= 0) break
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                val query = "?offset=" + status.uploaded + "&total=" + total + "&sha256=" + digest
                val response = sendRaw(
                    "PUT",
                    path + query,
                    chunk,
                    mapOf("Content-Type" to "application/octet-stream")
                )
                ensureSuccess(response)
                status = parseUploadStatus(response)
            }
        }
        return blobStatus(bookId)
    }

    override suspend fun downloadBlob(bookId: String, target: File, chunkSize: Int): Long {
        val path = blobPath(bookId)
        val remote = blobStatus(bookId)
        if (!remote.complete) throw SyncException("服务端没有完整的书籍正文：" + bookId, 404)
        target.parentFile?.mkdirs()
        var offset = if (target.isFile) target.length() else 0L
        if (offset > remote.uploaded) {
            // 本地残留比服务端还长（上次失败留下），从头重来。
            target.delete()
            offset = 0L
        }
        while (offset < remote.uploaded) {
            val response = sendRaw("GET", path, null, mapOf("Range" to "bytes=" + offset + "-"))
            ensureSuccess(response)
            if (response.body.isEmpty()) break
            target.appendBytes(response.body)
            offset += response.body.size
        }
        return offset
    }

    override suspend fun deleteBlob(bookId: String) {
        val response = sendRaw("DELETE", blobPath(bookId), null, emptyMap())
        ensureSuccess(response, setOf(204))
    }

    private fun blobPath(bookId: String): String = "/api/v1/blobs/" + URLEncoder.encode(bookId, "UTF-8")

    private class RawResponse(val status: Int, val body: ByteArray, val headers: Map<String, String>)

    private suspend fun sendRaw(
        method: String,
        path: String,
        body: ByteArray?,
        headers: Map<String, String>
    ): RawResponse = withContext(Dispatchers.IO) {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            applyPinning(connection)
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            for ((key, value) in headers) connection.setRequestProperty(key, value)
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer " + token)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val headerMap = LinkedHashMap<String, String>()
            for ((key, value) in connection.headerFields) {
                if (key != null && value.isNotEmpty()) headerMap[key.lowercase()] = value.first()
            }
            RawResponse(status, bytes, headerMap)
        } catch (error: SyncException) {
            throw error
        } catch (error: Exception) {
            throw SyncException(error.message ?: error.javaClass.simpleName, 0)
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureSuccess(response: RawResponse, allow: Set<Int> = emptySet()) {
        if (response.status in 200..299 || response.status in allow) return
        val text = response.body.toString(Charsets.UTF_8)
        val code = runCatching { JSONObject(text).optJSONObject("error")?.optString("code") }.getOrNull()
        throw SyncException(code ?: ("HTTP " + response.status), response.status)
    }

    private fun parseUploadStatus(response: RawResponse): BlobStatus {
        val json = runCatching { JSONObject(response.body.toString(Charsets.UTF_8)) }.getOrElse { JSONObject() }
        return BlobStatus(json.optBoolean("complete"), json.optLong("uploaded"))
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
            toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        /** 流式计算文件 SHA-256（书籍文件可能很大，不能整读进内存）。 */
        fun sha256HexOf(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return toHex(digest.digest())
        }

        private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            if (value < 16) "0" + value.toString(16) else value.toString(16)
        }

        /** 兼容 openssl -fingerprint 的 AA:BB:... 形式与裸 hex。 */
        fun normalizeFingerprint(raw: String): String =
            raw.replace(":", "").replace(" ", "").trim().lowercase()

        fun parseArray(text: String): JSONArray = JSONArray(text)
    }
}
