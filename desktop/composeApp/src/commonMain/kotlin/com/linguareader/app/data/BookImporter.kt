package com.linguareader.app.data

import java.io.File

/** Dispatches an imported file to the importer matching its extension. */
class BookImporter(private val booksDir: File) {
    fun import(source: PreparedSource): Book {
        val name = source.displayName.lowercase()
        return when {
            name.endsWith(".epub") -> EpubImporter(booksDir).import(source)
            name.endsWith(".txt") -> TextImporter(booksDir).import(source)
            name.endsWith(".fb2") -> Fb2Importer(booksDir).import(source)
            name.endsWith(".pdf") -> PdfImporter(booksDir).import(source)
            else -> throw IllegalArgumentException(
                "无法导入该文件，请确认它是未加密的 EPUB、纯文本 TXT、FB2 或带文字层的 PDF 电子书。"
            )
        }
    }
}
