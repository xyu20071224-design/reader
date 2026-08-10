package com.linguareader.app.reader

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Serves one extracted book over loopback for the JavaFX WebView and relays
 * JS bridge events back to Kotlin. The root is the book's extracted directory
 * so relative EPUB asset paths resolve exactly like file:// did on Android.
 */
internal class ChapterHttpServer(
    private val root: File,
    private val onBridgeEvent: (String, JSONArray) -> Unit
) {
    private val server = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        0
    )
    private val executor = Executors.newSingleThreadExecutor()
    private val rootPath = root.canonicalPath + File.separator

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = executor
    }

    val port: Int get() = server.address.port

    fun start() {
        server.start()
    }

    fun stop() {
        runCatching { server.stop(0) }
        executor.shutdown()
    }

    fun urlFor(file: File): String {
        val relative = file.canonicalFile
            .relativeTo(root.canonicalFile)
            .invariantSeparatorsPath
        val encoded = relative.split('/').joinToString("/") { encodePathSegment(it) }
        return "http://127.0.0.1:$port/$encoded"
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val rawPath = exchange.requestURI.rawPath ?: "/"
            if (rawPath == "/__bridge" && exchange.requestMethod == "POST") {
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                val json = JSONObject(body)
                onBridgeEvent(json.getString("name"), json.getJSONArray("args"))
                exchange.sendResponseHeaders(200, -1L)
                exchange.close()
                return
            }

            val decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name())
                .removePrefix("/")
            val file = File(root, decoded).canonicalFile
            if (!file.path.startsWith(rootPath) || !file.isFile) {
                exchange.sendResponseHeaders(404, -1L)
                exchange.close()
                return
            }

            val raw = file.readBytes()
            val bytes = if (isHtml(file)) withCsp(raw) else raw
            exchange.responseHeaders.add("Content-Type", contentType(file))
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (error: Throwable) {
            runCatching { exchange.sendResponseHeaders(500, -1L) }
            exchange.close()
        }
    }

    private fun withCsp(bytes: ByteArray): ByteArray {
        val html = bytes.toString(StandardCharsets.UTF_8)
        val meta =
            "<meta http-equiv=\"Content-Security-Policy\" content=\"" +
                "default-src 'self' data:; " +
                "img-src 'self' data:; " +
                "style-src 'self' 'unsafe-inline'; " +
                "script-src 'self' 'unsafe-inline'; " +
                "connect-src 'self'; " +
                "font-src 'self' data:" +
                "\">"
        val head = Regex("(?i)<head[^>]*>").find(html)
        return if (head != null) {
            val insertion = head.range.last + 1
            (html.substring(0, insertion) + meta + html.substring(insertion))
                .toByteArray(StandardCharsets.UTF_8)
        } else {
            bytes
        }
    }

    private fun isHtml(file: File): Boolean =
        file.extension.lowercase() in setOf("html", "htm", "xhtml")

    private fun contentType(file: File): String = when (file.extension.lowercase()) {
        "html", "htm", "xhtml" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        else -> "application/octet-stream"
    }

    private fun encodePathSegment(value: String): String = buildString {
        for (char in value) {
            when {
                char.isLetterOrDigit() || char in "-._~" -> append(char)
                char == ' ' -> append("%20")
                else -> {
                    val bytes = char.toString().toByteArray(StandardCharsets.UTF_8)
                    bytes.forEach { append("%").append("%02X".format(it)) }
                }
            }
        }
    }
}
