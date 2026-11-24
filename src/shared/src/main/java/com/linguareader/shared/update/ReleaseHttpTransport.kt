package com.linguareader.shared.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class ReleaseHttpResponse(val body: String, val headers: Map<String, List<String>>, val finalUrl: String)

interface ReleaseHttpTransport {
    suspend fun text(url: String, policy: ReleaseUrlPolicy, maxBytes: Long): ReleaseHttpResponse
    suspend fun download(
        asset: ReleaseAsset,
        directory: File,
        policy: ReleaseUrlPolicy,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File
}

/** Bounded, cancellable transfers. A failed validation never exposes a completed asset file. */
class HttpReleaseTransport(
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) : ReleaseHttpTransport {
    override suspend fun text(url: String, policy: ReleaseUrlPolicy, maxBytes: Long): ReleaseHttpResponse =
        withContext(Dispatchers.IO) {
            require(maxBytes in 1..MAX_METADATA_BYTES)
            val connection = open(url, policy)
            try {
                val declared = connection.contentLengthLong.takeIf { it >= 0 }
                check(declared == null || declared <= maxBytes) { "Release response is too large" }
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size().toLong() + count <= maxBytes) { "Release response is too large" }
                        output.write(buffer, 0, count)
                    }
                }
                check(declared == null || output.size().toLong() == declared) { "Truncated release response" }
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                ReleaseHttpResponse(decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString(),
                    connection.headerFields.filterKeys { it != null }, connection.url.toString())
            } finally { connection.disconnect() }
        }

    override suspend fun download(
        asset: ReleaseAsset,
        directory: File,
        policy: ReleaseUrlPolicy,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        require(maxBytes > 0)
        require(asset.bytes == null || asset.bytes in 1..maxBytes) { "Asset size exceeds the allowed limit" }
        require(ReleaseUrls.canonicalAsset(asset.canonicalUrl)) { "Invalid canonical release asset" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create download directory" }
        val suffix = asset.name.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) } ?: "bin"
        val target = File.createTempFile("release-", ".$suffix", directory)
        try {
            val connection = open(asset.transportUrl, policy)
            try {
                val declared = connection.contentLengthLong.takeIf { it >= 0 }
                check(declared == null || declared <= maxBytes) { "Asset exceeds download limit" }
                check(asset.bytes == null || declared == null || declared == asset.bytes) { "Asset size mismatch" }
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                connection.inputStream.use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        check(written <= maxBytes && (asset.bytes == null || written <= asset.bytes)) { "Asset exceeds declared size" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress(written, asset.bytes ?: declared ?: -1L)
                    }
                } }
                coroutineContext.ensureActive()
                check(written > 0 && (asset.bytes == null || written == asset.bytes) &&
                    (declared == null || written == declared)) { "Asset size mismatch" }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(asset.sha256 == null || actual == asset.sha256) { "Asset SHA-256 mismatch" }
                target
            } finally { connection.disconnect() }
        } catch (t: Throwable) { target.delete(); throw t }
    }

    private suspend fun open(initialUrl: String, policy: ReleaseUrlPolicy): HttpURLConnection {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirects ->
            coroutineContext.ensureActive()
            require(policy.allows(url)) { "Download URL is outside the selected source" }
            val connection = connectionFactory(url)
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "LinguaReader")
                connection.setRequestProperty("Accept", "application/vnd.github+json, application/json, application/octet-stream")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    check(redirects < MAX_REDIRECTS) { "Too many release redirects" }
                    val location = requireNotNull(connection.getHeaderField("Location")) { "Release redirect has no location" }
                    url = URL(URL(url), location).toString()
                    connection.disconnect()
                } else {
                    check(status == HttpURLConnection.HTTP_OK) { "Release request failed (HTTP $status)" }
                    check(policy.allows(connection.url.toString())) { "Unexpected release response source" }
                    return connection
                }
            } catch (t: Throwable) { connection.disconnect(); throw t }
        }
        error("Too many release redirects")
    }

    companion object {
        const val MAX_METADATA_BYTES = 4L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
    }
}
