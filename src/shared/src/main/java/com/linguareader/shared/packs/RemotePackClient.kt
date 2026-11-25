package com.linguareader.shared.packs

import com.linguareader.shared.update.ReleaseContentLength
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Explicit HTTPS catalog/download actions. TLS trust remains the platform default. */
class RemotePackClient(
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) {
    suspend fun fetchCatalog(appVersion: Int, url: String = RemotePackCatalog.DEFAULT_URL): Result<List<RemotePack>> =
        withContext(Dispatchers.IO) {
            result {
                val connection = open(url)
                try {
                    check(connection.responseCode == 200) { "获取资源目录失败（HTTP ${connection.responseCode}）" }
                    requireIdentityEncoding(connection)
                    val declared = ReleaseContentLength.read(connection)
                    check(declared <= RemotePackCatalog.MAX_CATALOG_BYTES) { "资源目录过大" }
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size().toLong() + count <= RemotePackCatalog.MAX_CATALOG_BYTES) { "资源目录过大" }
                            output.write(buffer, 0, count)
                        }
                    }
                    coroutineContext.ensureActive()
                    check(declared < 0 || output.size().toLong() == declared) { "资源目录响应未完整下载" }
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    RemotePackCatalog.parse(decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString(), appVersion)
                } finally { connection.disconnect() }
            }
        }

    suspend fun download(pack: RemotePack, directory: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<File> =
        withContext(Dispatchers.IO) {
            result {
                RemotePackCatalog.validate(pack)
                check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) { "无法创建资源下载目录" }
                val root = directory.canonicalFile
                // The digest binds the partial to exact content, independent of selected mirror/name.
                val key = pack.sha256 ?: hash("${pack.downloadUrl}\n${pack.name}\n${pack.bytes}".toByteArray())
                withTransferLock(root, key) {
                    val target = child(root, "$key-${pack.name}")
                    if (pack.sha256 != null && validFile(target, pack)) return@withTransferLock target
                    // Without a digest there is no partial/cache reuse or cross-mirror append.
                    val partial = if (pack.sha256 != null) child(root, "$key.part")
                        else File.createTempFile("remote-", ".part", root)
                    try {
                        var lastError: Exception? = null
                        for (url in listOf(pack.downloadUrl) + pack.mirrors) {
                            coroutineContext.ensureActive()
                            try {
                                downloadFrom(pack, url, partial, onProgress)
                                if (!validFile(partial, pack)) throw InvalidDownload("资源包大小或 SHA-256 校验失败")
                                coroutineContext.ensureActive()
                                // Same-directory rename publishes only a complete, verified file.
                                check(partial.renameTo(target)) { "无法保存下载的资源包" }
                                return@withTransferLock target
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                if (error is InvalidDownload || pack.sha256 == null) partial.delete()
                                lastError = error
                            }
                        }
                        throw lastError ?: IOException("资源包没有可用下载地址")
                    } finally {
                        if (pack.sha256 == null) partial.delete()
                    }
                }
            }
        }

    private suspend fun downloadFrom(pack: RemotePack, url: String, partial: File, progress: (Long, Long) -> Unit) {
        if (pack.sha256 == null || partial.length() > pack.bytes) check(!partial.exists() || partial.delete()) { "无法重置下载断点" }
        var offset = if (pack.sha256 != null) partial.length() else 0L
        if (offset == pack.bytes) {
            if (validFile(partial, pack)) return
            check(partial.delete()) { "无法重置无效下载断点" }
            offset = 0
        }
        val connection = open(url, offset)
        try {
            val code = connection.responseCode
            if (code != 200 && code != 206) throw IOException("下载失败（HTTP $code）")
            requireIdentityEncoding(connection)
            if (code == 206) {
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(connection.getHeaderField("Content-Range") ?: "")
                    ?: throw InvalidDownload("服务器返回了无效的下载范围")
                val values = range.groupValues.drop(1).map { it.toLongOrNull() ?: throw InvalidDownload("下载范围溢出") }
                val (start, end, total) = values
                // Range is open-ended; require the entire requested remainder.
                if (start != offset || end != pack.bytes - 1 || total != pack.bytes || start > end)
                    throw InvalidDownload("服务器返回的下载范围与资源包不符")
            } else offset = 0 // Ignored Range replaces, never appends, a complete body.
            val expected = pack.bytes - offset
            val declared = ReleaseContentLength.read(connection)
            if (declared >= 0 && declared != expected) throw InvalidDownload("服务器声明的资源包大小不符")
            var written = offset
            connection.inputStream.use { input ->
                FileOutputStream(partial, offset > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count.toLong() > pack.bytes - written) throw InvalidDownload("资源包超过声明大小")
                        output.write(buffer, 0, count)
                        written += count
                        progress(written, pack.bytes)
                    }
                    coroutineContext.ensureActive()
                    if (written != pack.bytes) throw IOException("资源包未下载完整，可点击重试继续下载")
                    output.fd.sync()
                }
            }
        } finally { connection.disconnect() }
    }

    private suspend fun open(initialUrl: String, offset: Long = 0): HttpURLConnection {
        var url = RemotePackCatalog.validateUrl(initialUrl)
        val initialOrigin = origin(url)
        repeat(6) { redirects ->
            coroutineContext.ensureActive()
            val connection = connectionFactory(url)
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "LinguaReader")
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                if (connection.responseCode !in listOf(301, 302, 303, 307, 308)) return connection
                check(redirects < 5) { "资源地址重定向过多" }
                val location = connection.getHeaderField("Location") ?: throw IOException("下载重定向缺少地址")
                val next = RemotePackCatalog.validateUrl(URL(URL(url), location).toString())
                check(origin(next) == initialOrigin) { "资源地址重定向超出当前源；请在目录中声明镜像" }
                url = next
            } catch (error: Throwable) { connection.disconnect(); throw error }
            connection.disconnect()
        }
        error("资源地址重定向过多")
    }

    private fun origin(url: String): Pair<String, Int> = URI(url).let { it.host.lowercase(Locale.ROOT) to if (it.port == -1) 443 else it.port }

    private fun requireIdentityEncoding(connection: HttpURLConnection) {
        val encoding = connection.getHeaderField("Content-Encoding")
        if (encoding != null && !encoding.trim().equals("identity", true)) throw InvalidDownload("资源下载不支持动态压缩")
    }

    /** API1 locks coordinate clients/processes; persistent lock inodes must never be unlinked. */
    private suspend fun <T> withTransferLock(root: File, key: String, block: suspend () -> T): T {
        val locks = child(root, ".remote-pack-locks")
        check(locks.isDirectory || locks.mkdir() || locks.isDirectory) { "无法创建资源下载锁目录" }
        RandomAccessFile(child(locks, "$key.lock"), "rw").use { file ->
            val channel = file.channel
            while (true) {
                coroutineContext.ensureActive()
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock != null) {
                    try { return block() } finally { lock.release() }
                }
                delay(25)
            }
        }
    }

    private fun child(root: File, name: String): File = File(root, name).also {
        check(it.canonicalFile == it.absoluteFile) { "资源下载路径包含符号链接" }
    }

    private suspend fun validFile(file: File, pack: RemotePack): Boolean {
        if (!file.isFile || file.length() != pack.bytes) return false
        val expected = pack.sha256 ?: return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex() == expected
    }

    private suspend fun <T> result(block: suspend () -> T): Result<T> = try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private class InvalidDownload(message: String) : IOException(message)
}
