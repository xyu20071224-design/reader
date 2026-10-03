package com.linguareader.shared.sync

import java.io.File

/**
 * 下载回来的云端 blob **没有文件名/扩展名**——服务端 [BlobInfo] 只有 bookId/size/sha256，
 * 而导入器按扩展名分发。因此按内容魔数判格式（纯逻辑、无平台依赖，可直接单测）：
 * `%PDF` → PDF；zip 头 → EPUB；含 `<FictionBook` → FB2；其余像文本 → TXT；否则 UNKNOWN。
 */
object BookFormatDetector {

    const val EPUB = "epub"
    const val PDF = "pdf"
    const val FB2 = "fb2"
    const val TXT = "txt"
    const val UNKNOWN = "unknown"

    private const val SNIFF_BYTES = 8192

    fun detect(file: File): String {
        if (!file.isFile || file.length() == 0L) return UNKNOWN
        val buffer = ByteArray(SNIFF_BYTES)
        val read = file.inputStream().use { input ->
            var total = 0
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count <= 0) break
                total += count
            }
            total
        }
        return detect(if (read == buffer.size) buffer else buffer.copyOf(read))
    }

    fun detect(head: ByteArray): String {
        if (head.isEmpty()) return UNKNOWN
        if (matches(head, "%PDF")) return PDF
        if (isZip(head)) return EPUB
        if (String(head, Charsets.UTF_8).contains("<FictionBook", ignoreCase = true)) return FB2
        return if (looksLikeText(head)) TXT else UNKNOWN
    }

    private fun matches(bytes: ByteArray, text: String): Boolean {
        if (bytes.size < text.length) return false
        for (index in text.indices) {
            if (bytes[index].toInt() and 0xFF != text[index].code) return false
        }
        return true
    }

    private fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 &&
        bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
        (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

    /** 无 NUL 且控制字符占比 ≤5% → 视为纯文本。 */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        var control = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value == 0) return false
            if (value < 0x09 || (value > 0x0D && value < 0x20)) control++
        }
        return control * 100 <= bytes.size * 5
    }
}
