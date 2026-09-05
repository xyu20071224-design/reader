package com.linguareader.shared.importer

import java.io.File
import java.security.MessageDigest

/** 导入器共享辅助函数（桌面迁移 M2 刀7）。Android 侧的拷贝/文件名查询留在 `:app`。 */
object ImportSupport {
    const val MAX_SOURCE_BYTES = 500L * 1024 * 1024

    /** Original file name without its extension, for title fallbacks. */
    fun baseName(name: String): String = name.substringBeforeLast('.').trim().ifBlank { name.trim() }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
