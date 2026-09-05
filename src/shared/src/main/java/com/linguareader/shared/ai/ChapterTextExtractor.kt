package com.linguareader.shared.ai

import com.linguareader.shared.data.Book
import org.jsoup.Jsoup
import java.io.File

/** Extracts plain chapter text from the same XHTML files the reader pages. */
class ChapterTextExtractor {
    fun extract(book: Book): List<ChapterText> = book.chapters.mapIndexed { index, chapter ->
        val file = File(book.extractedDir, chapter.relativePath)
        val text = runCatching {
            Jsoup.parse(file, "UTF-8")
                .text()
                .replace(Regex("\\s+"), " ")
                .trim()
        }.getOrElse { "" }
        ChapterText(index = index, title = chapter.title, text = text)
    }.filter { it.text.isNotBlank() }
}
