package com.linguareader.app.data

import android.content.Context
import android.net.Uri
import java.io.File

/** Dispatches an imported file to the importer matching its extension/MIME. */
class BookImporter(
    private val context: Context,
    private val booksDir: File
) {
    fun import(uri: Uri): Book {
        val name = uri.lastPathSegment?.substringAfterLast('/')?.lowercase().orEmpty()
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        return when {
            name.endsWith(".epub") || mime in EPUB_MIMES ->
                EpubImporter(context, booksDir).import(uri)
            name.endsWith(".txt") || mime == "text/plain" ->
                TextImporter(context, booksDir).import(uri)
            name.endsWith(".fb2") || mime in FB2_MIMES ->
                Fb2Importer(context, booksDir).import(uri)
            name.endsWith(".pdf") || mime == "application/pdf" ->
                PdfImporter(context, booksDir).import(uri)
            else -> throw IllegalArgumentException(
                "无法导入该文件，请确认它是未加密的 EPUB、纯文本 TXT、FB2 或带文字层的 PDF 电子书。"
            )
        }
    }

    private companion object {
        val EPUB_MIMES = setOf(
            "application/epub+zip",
            "application/zip",
            "application/octet-stream"
        )
        val FB2_MIMES = setOf(
            "application/x-fictionbook+xml",
            "application/xml",
            "text/xml"
        )
    }
}