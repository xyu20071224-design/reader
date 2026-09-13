package com.linguareader.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.update.GitHubReleaseParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Q2-c03 下载层：资源包资产下载到应用专属目录。
 *
 * 用本地 HTTP 服务器验，不依赖真实 GitHub Release（远端目前没有 `.lrpack` 资产）。
 * 覆盖两件事：成功时字节与进度都对；HTTP 错误时失败且**不留半截文件**。
 */
@RunWith(RobolectricTestRunner::class)
class AppUpdateRepositoryDownloadTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * 起一个「只应答一次」的最小 HTTP 服务器。
     *
     * 用 [ServerSocket] 手写响应，而不是 `com.sun.net.httpserver`：后者不在 Android 的
     * 编译 classpath 上（android.jar 没有 `com.sun.*`），测试编译就会失败。
     */
    private fun serveOnce(status: String, body: ByteArray): Pair<ServerSocket, Int> {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    // 把请求头读完（读到空行就跳出，不能只跳过那一行——否则会一直等下一行，
                    // 客户端读响应超时，测试拿到的是超时而不是我们要验的状态码）。
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val header = "HTTP/1.1 $status\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Connection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(header.toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        return server to server.localPort
    }

    @Test
    fun `downloadPack writes bytes and reports progress`() = runBlocking {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val (server, port) = serveOnce("200 OK", payload)
        try {
            val url = "http://127.0.0.1:$port/pack.lrpack"
            val repo = AppUpdateRepository(context)
            var lastReported = 0L

            val file = repo.downloadPack(
                GitHubReleaseParser.PackAsset("test-pack.lrpack", url, payload.size.toLong())
            ) { written, _ -> lastReported = written }.getOrThrow()

            assertEquals(payload.size.toLong(), file.length())
            assertEquals(payload.size.toLong(), lastReported)
            assertArrayEquals(payload, file.readBytes())
            // 落在应用专属目录（免存储权限），不是别处
            assertEquals("packs-download", file.parentFile?.name)
        } finally {
            server.close()
        }
    }

    @Test
    fun `downloadPack fails on http error and leaves no partial file`() = runBlocking {
        val (server, port) = serveOnce("404 Not Found", ByteArray(0))
        try {
            val url = "http://127.0.0.1:$port/missing.lrpack"
            val repo = AppUpdateRepository(context)

            val result = repo.downloadPack(
                GitHubReleaseParser.PackAsset("missing.lrpack", url, 0L)
            ) { _, _ -> }

            assertTrue("HTTP 404 必须失败：$result", result.isFailure)
            val leftovers = context.getExternalFilesDir("packs-download")
                ?.listFiles()
                .orEmpty()
                .map { it.name }
            assertTrue("失败不得留下半截文件：$leftovers", leftovers.none { it == "missing.lrpack" })
        } finally {
            server.close()
        }
    }
}
