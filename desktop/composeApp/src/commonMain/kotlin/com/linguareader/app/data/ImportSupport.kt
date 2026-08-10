package com.linguareader.app.data

import java.io.File
import java.security.MessageDigest

/** A picked source file plus its original display name (for extension/title detection). */
data class PreparedSource(val file: File, val displayName: String)

/** Shared helpers for every book-format importer. */
object ImportSupport {
    private const val MAX_SOURCE_BYTES = 500L * 1024 * 1024

    /**
     * Copies the picked file to a temp file that importers may delete freely.
     * The user's original file is never touched.
     */
    fun prepare(source: File, displayName: String? = null): PreparedSource {
        val name = displayName ?: source.name
        val temp = File.createTempFile("import-", ".tmp")
        source.inputStream().use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        require(temp.length() > 0) { "文件内容为空" }
        require(temp.length() <= MAX_SOURCE_BYTES) { "文件超过 500MB 限制" }
        return PreparedSource(temp, name)
    }

    /** Original file name without its extension, for title fallbacks. */
    fun baseName(source: PreparedSource): String {
        val name = source.displayName
        return name.substringBeforeLast('.').trim().ifBlank { name.trim() }
    }

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
