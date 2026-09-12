package com.linguareader.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

/** Shared helpers for every book-format importer. */
object ImportSupport {
    private const val MAX_SOURCE_BYTES = 500L * 1024 * 1024

    /**
     * 把选中的书源拷进 cacheDir。
     *
     * 审查 4-10：原实现是「整份 `copyTo` 到临时文件后**才**检查 500MB」——误选大文件时
     * 会先把存储占满再报错，且中途没有护栏。现在改成三段：
     * 1. **读前预检**：用 [OpenableColumns.SIZE] 的元数据先问体积，超限直接拒、不建临时文件；
     * 2. **读中护栏**：逐块累计，超过上限立即中止（provider 不报 SIZE 时的兜底）；
     * 3. 读后仍校验非空（保持原有语义）。
     */
    fun copySource(context: Context, uri: Uri): File {
        declaredSize(context, uri)?.let { declared ->
            require(declared <= MAX_SOURCE_BYTES) {
                "文件超过 500MB 限制：${declared / 1024 / 1024}MB"
            }
        }
        val temp = File.createTempFile("import-", ".tmp", context.cacheDir)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取所选文件")
        input.use { source ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var written = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    written += read
                    require(written <= MAX_SOURCE_BYTES) { "文件超过 500MB 限制" }
                    output.write(buffer, 0, read)
                }
            }
        }
        require(temp.length() > 0) { "文件内容为空" }
        return temp
    }

    /** SAF 文档声明的字节数；provider 不提供时返回 null（调用方需自行兜底）。 */
    private fun declaredSize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                } else null
            }
    }.getOrNull()



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