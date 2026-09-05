package com.linguareader.app.data

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * EPUB 导入的 Android 壳（桌面迁移 M2 刀7）。解压/OPF 解析真相已迁入
 * `com.linguareader.shared.importer.EpubImporter`（File 级 API）；
 * 本类只保留 SAF 面：拷贝临时文件。
 */
class EpubImporter(
    private val context: Context,
    private val booksDir: File
) {
    fun import(uri: Uri): Book {
        val source = ImportSupport.copySource(context, uri)
        try {
            return com.linguareader.shared.importer.EpubImporter(booksDir).import(source)
        } finally {
            source.delete()
        }
    }
}
