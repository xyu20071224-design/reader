package com.linguareader.shared.importer

import com.linguareader.shared.data.Book
import java.io.File

/**
 * 按扩展名分发的导入入口（File 级，桌面迁移 M2 刀7）。
 * Android 的 `:app` 侧还有一个同名分发器走 SAF + MIME；桌面只有扩展名可用，
 * PDF（pdfbox-android 与标准 PDFBox 包名不同族）暂不在桌面闭环内（方案 M2 验收线只要求 TXT/EPUB）。
 */
class BookImporter(private val booksDir: File) {
    fun import(source: File, displayName: String): Book {
        val name = displayName.lowercase()
        return when {
            name.endsWith(".epub") -> EpubImporter(booksDir).import(source)
            name.endsWith(".txt") -> TextImporter(booksDir).import(source, ImportSupport.baseName(displayName).ifBlank { "未命名图书" })
            name.endsWith(".fb2") -> Fb2Importer(booksDir).import(source, ImportSupport.baseName(displayName))
            else -> throw IllegalArgumentException(
                "无法导入该文件，请确认它是未加密的 EPUB、纯文本 TXT 或 FB2 电子书。"
            )
        }
    }
}
