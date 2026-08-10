package com.linguareader.app.data

import java.io.File

internal data class PdfChapter(val title: String, val body: String)

internal data class ParsedPdf(
    val title: String,
    val author: String,
    val chapters: List<PdfChapter>
)

internal const val MAX_PDF_PAGES = 1000
private const val HEURISTIC_CHAPTER_PAGES = 10
internal const val MIN_MEANINGFUL_TEXT = 20

internal val pdfTitlePatterns = listOf(
    Regex("""^\s*(part|book|prologue|epilogue|appendix|introduction|contents?|preface)\b.*$""", RegexOption.IGNORE_CASE),
    Regex("""^\s*(卷一|卷二|序章|尾声|前言|附录|目录|楔子)\b.*$""")
)

/**
 * Platform PDF text extraction. Android uses pdfbox-android; the desktop
 * target uses Apache PDFBox directly. Shared chapter heuristics stay here.
 */
internal expect fun extractPdf(
    file: File,
    scratchDir: File? = null,
    maxPages: Int = MAX_PDF_PAGES
): ParsedPdf

internal fun splitPdfPagesByHeuristics(pageTexts: List<String>): List<PdfChapter> {
    val titles = pageTexts.map { detectPdfChapterTitle(it) }
    if (titles.none { it != null }) {
        return pageTexts
            .chunked(HEURISTIC_CHAPTER_PAGES)
            .mapIndexed { index, pages ->
                PdfChapter("第 ${index + 1} 部分", pages.joinToString("\n\n"))
            }
            .filter { it.body.isNotBlank() }
    }

    val chapters = mutableListOf<PdfChapter>()
    var title = ""
    val body = StringBuilder()
    for ((index, pageText) in pageTexts.withIndex()) {
        val detected = titles[index]
        if (detected != null) {
            if (title.isNotBlank() || body.isNotBlank()) {
                chapters.add(PdfChapter(title, body.toString().trim()))
                body.clear()
            }
            title = detected
            body.append(
                pageText.lineSequence()
                    .filter { it.trim() != detected }
                    .joinToString("\n")
            )
        } else {
            body.append(pageText)
        }
        body.append("\n\n")
    }
    if (title.isNotBlank() || body.isNotBlank()) {
        chapters.add(PdfChapter(title, body.toString().trim()))
    }
    return chapters.filter { it.body.isNotBlank() }
}

internal fun detectPdfChapterTitle(pageText: String): String? {
    val lines = pageText.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
    for (line in lines.take(4)) {
        val looksLikeTitle = line.length in 1..80 &&
            (chapterPatterns.any { it.containsMatchIn(line) } ||
                pdfTitlePatterns.any { it.containsMatchIn(line) })
        if (looksLikeTitle) return line
    }
    return null
}
