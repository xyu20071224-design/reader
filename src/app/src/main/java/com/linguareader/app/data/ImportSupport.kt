package com.linguareader.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

/** Shared helpers for every book-format importer. */
object ImportSupport {
    private const val MAX_SOURCE_BYTES = 500L * 1024 * 1024

    fun copySource(context: Context, uri: Uri): File {
        val temp = File.createTempFile("import-", ".tmp", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("无法读取所选文件")
        require(temp.length() > 0) { "文件内容为空" }
        require(temp.length() <= MAX_SOURCE_BYTES) { "文件超过 500MB 限制" }
        return temp
    }



    /** Best-effort original file name shown by the system picker. */
    fun displayName(context: Context, uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
                } else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            .orEmpty()
    }

    /** Original file name without its extension, for title fallbacks. */
    fun baseName(context: Context, uri: Uri): String {
        val name = displayName(context, uri)
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