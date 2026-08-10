package com.linguareader.app.data

import com.linguareader.app.platform.appCacheDir
import java.io.File

/** Imports a text-layer PDF into the same chapter-based book model. */
class PdfImporter(private val booksDir: File) {
    fun import(source: PreparedSource): Book {
        val id = ImportSupport.sha256(source.file).take(20)
        val destination = File(booksDir, id)
        if (destination.exists()) destination.deleteRecursively()
        destination.mkdirs()
        try {
            val parsed = extractPdf(source.file, scratchDir = appCacheDir)
            val title = parsed.title.ifBlank { ImportSupport.baseName(source).ifBlank { "未命名图书" } }
            val chapters = parsed.chapters.mapIndexed { index, chapter ->
                val file = File(destination, "chapter_%03d.xhtml".format(index + 1))
                file.writeText(textToXhtml(chapter.title, chapter.body), Charsets.UTF_8)
                Chapter(
                    title = chapter.title.ifBlank { "第 ${index + 1} 章" },
                    relativePath = file.relativeTo(destination).invariantSeparatorsPath
                )
            }
            return Book(
                id = id,
                title = title,
                author = parsed.author.ifBlank { "未知作者" },
                extractedDir = destination.absolutePath,
                coverRelativePath = null,
                chapters = chapters,
                addedAt = System.currentTimeMillis(),
                sourceFormat = "pdf"
            )
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw IllegalArgumentException(
                "无法导入该 PDF，请确认它是未加密、带文字层的 PDF：${error.message}",
                error
            )
        } finally {
            source.file.delete()
        }
    }
}
