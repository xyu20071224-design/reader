package com.linguareader.app.data

import android.content.Context
import android.net.Uri
import com.linguareader.shared.sync.BookFormatDetector
import java.io.File

/** 云端下载回来的文件不是可识别的电子书格式；UI 据此给本地化文案。 */
class UnsupportedBookFormatException : IllegalArgumentException("unsupported book format")

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

    /**
     * File 级入口（云端下载重导入）：格式按**内容魔数**判定 —— 服务端 blob 没有文件名/扩展名。
     * PDF 走 :app 的 pdfbox；EPUB/TXT/FB2 复用 :shared 的 File 级导入器。
     * 导入成功后留存源文件，便于换设备/再次上传。
     */
    fun importFile(source: File, displayName: String): Book {
        val format = BookFormatDetector.detect(source)
        val book = when (format) {
            BookFormatDetector.PDF -> PdfImporter(context, booksDir).importFile(source, displayName)
            BookFormatDetector.EPUB -> com.linguareader.shared.importer.EpubImporter(booksDir).import(source)
            BookFormatDetector.TXT -> com.linguareader.shared.importer.TextImporter(booksDir)
                .import(source, displayName.ifBlank { "未命名图书" })
            BookFormatDetector.FB2 -> com.linguareader.shared.importer.Fb2Importer(booksDir)
                .import(source, displayName)
            else -> throw UnsupportedBookFormatException()
        }
        ImportSupport.retainSource(book, source)
        return book
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