package com.linguareader.app.data

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * TXT 导入的 Android 壳（桌面迁移 M2 刀7）。解析真相已迁入
 * `com.linguareader.shared.importer.TextImporter`（File 级 API）；
 * 本类只保留 SAF 面：拷贝临时文件、查询显示名。
 */
class TextImporter(
    private val context: Context,
    private val booksDir: File
) {
    fun import(uri: Uri): Book {
        val source = ImportSupport.copySource(context, uri)
        try {
            return com.linguareader.shared.importer.TextImporter(booksDir)
                .import(source, ImportSupport.baseName(context, uri).ifBlank { "未命名图书" })
        } finally {
            source.delete()
        }
    }
}
