package com.linguareader.app.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.linguareader.shared.importer.chapterPatterns
import com.linguareader.shared.importer.escapeHtml
import com.linguareader.shared.importer.textToXhtml
import java.io.File

/** Imports a text-layer PDF into the same chapter-based book model. */
class PdfImporter(
    private val context: Context,
    private val booksDir: File
) {
    fun import(uri: Uri): Book {
        PDFBoxResourceLoader.init(context)
        val source = ImportSupport.copySource(context, uri)
        val id = ImportSupport.sha256(source).take(20)
        val destination = File(booksDir, id)
        if (destination.exists()) destination.deleteRecursively()
        destination.mkdirs()
        try {
            val parsed = extractPdf(source, scratchDir = context.cacheDir)
            val title = parsed.title.ifBlank { ImportSupport.baseName(context, uri).ifBlank { "未命名图书" } }
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
            source.delete()
        }
    }
}

internal data class PdfChapter(val title: String, val body: String)

internal data class ParsedPdf(
    val title: String,
    val author: String,
    val chapters: List<PdfChapter>
)

private data class OutlineEntry(
    val title: String,
    val pageIndex: Int?,
    val depth: Int
)

internal const val MAX_PDF_PAGES = 1000
private const val HEURISTIC_CHAPTER_PAGES = 10

/**
 * Extracts a text-layer PDF into chapters.
 *
 * Chapters come from PDF bookmarks when available; otherwise a lightweight
 * heuristic splits on title-like lines, falling back to fixed page chunks.
 * Scanned/image-only PDFs are rejected because they contain no extractable text.
 */
internal fun extractPdf(
    file: File,
    scratchDir: File? = null,
    maxPages: Int = MAX_PDF_PAGES
): ParsedPdf {
    val memorySetting = MemoryUsageSetting.setupTempFileOnly()
        .let { if (scratchDir != null) it.setTempDir(scratchDir) else it }
    PDDocument.load(file, memorySetting).use { document ->
        val pageCount = document.numberOfPages
        require(pageCount > 0) { "PDF 中没有页面" }
        require(pageCount <= maxPages) { "PDF 页数超过 $maxPages 页上限" }

        val entries = collectOutlineEntries(document)
        val pageTexts = extractPageTexts(document, pageCount)
        require(pageTexts.any { it.trim().length >= MIN_MEANINGFUL_TEXT }) {
            "该 PDF 没有可提取的文字层，可能是扫描版或图片型 PDF"
        }

        val chapters = if (entries.isNotEmpty()) {
            splitByOutline(
                pageCount = pageCount,
                pageTexts = pageTexts,
                entries = fillMissingPageIndices(entries, pageCount - 1)
            )
        } else {
            splitPdfPagesByHeuristics(pageTexts)
        }
        require(chapters.isNotEmpty()) { "PDF 中没有可阅读的文字内容" }

        val info = document.documentInformation
        return ParsedPdf(
            title = info.title?.trim().orEmpty(),
            author = info.author?.trim().orEmpty(),
            chapters = chapters
        )
    }
}

private const val MIN_MEANINGFUL_TEXT = 20

private const val PAGE_MARKER = "\u0000LINGUAREADER_PAGE\u0000"

private fun extractPageTexts(document: PDDocument, pageCount: Int): List<String> {
    val stripper = PDFTextStripper()
    stripper.sortByPosition = true
    stripper.pageStart = PAGE_MARKER
    val pages = stripper.getText(document).orEmpty().split(PAGE_MARKER).drop(1)
    return if (pages.size == pageCount) {
        pages
    } else {
        (1..pageCount).map { extractPageText(document, it) }
    }
}

private fun extractPageText(document: PDDocument, pageNumber: Int): String {
    val stripper = PDFTextStripper()
    stripper.startPage = pageNumber
    stripper.endPage = pageNumber
    stripper.sortByPosition = true
    return stripper.getText(document).orEmpty()
}

private fun collectOutlineEntries(document: PDDocument): List<OutlineEntry> {
    val outline = document.documentCatalog.documentOutline ?: return emptyList()
    val entries = mutableListOf<OutlineEntry>()
    collectOutline(outline, document, 0, entries)
    return entries
}

private fun collectOutline(
    node: PDOutlineNode,
    document: PDDocument,
    depth: Int,
    out: MutableList<OutlineEntry>
) {
    var child = node.firstChild
    while (child != null) {
        out.add(
            OutlineEntry(
                title = child.title?.trim().orEmpty(),
                pageIndex = resolveOutlinePage(child, document),
                depth = depth
            )
        )
        collectOutline(child, document, depth + 1, out)
        child = child.nextSibling
    }
}

private fun resolveOutlinePage(item: PDOutlineItem, document: PDDocument): Int? = runCatching {
    item.findDestinationPage(document)?.let { page -> document.pages.indexOf(page) }
}.getOrNull()

private fun fillMissingPageIndices(
    entries: List<OutlineEntry>,
    lastPage: Int
): List<OutlineEntry> {
    val result = entries.toMutableList()
    var lastKnown = lastPage
    for (index in result.indices.reversed()) {
        val entry = result[index]
        if (entry.pageIndex == null) {
            result[index] = entry.copy(pageIndex = lastKnown)
        } else {
            lastKnown = entry.pageIndex
        }
    }
    var previous = 0
    return result.map { entry ->
        val page = (entry.pageIndex ?: 0).coerceIn(previous, lastPage)
        previous = page
        entry.copy(pageIndex = page)
    }
}

private fun splitByOutline(
    pageCount: Int,
    pageTexts: List<String>,
    entries: List<OutlineEntry>
): List<PdfChapter> {
    val topLevel = entries.filter { it.depth == 0 }
    if (topLevel.isEmpty()) return splitPdfPagesByHeuristics(pageTexts)

    val chapters = mutableListOf<PdfChapter>()
    for ((index, top) in topLevel.withIndex()) {
        val startPage = (top.pageIndex ?: 0).coerceIn(0, pageCount - 1)
        val endPage = if (index + 1 < topLevel.size) {
            ((topLevel[index + 1].pageIndex ?: pageCount - 1) - 1)
                .coerceIn(startPage, pageCount - 1)
        } else {
            pageCount - 1
        }
        val nested = entries.filter {
            it.depth > 0 && (it.pageIndex ?: 0) in startPage..endPage
        }
        val body = buildString {
            for (page in startPage..endPage) {
                nested.filter { (it.pageIndex ?: 0) == page }
                    .forEach { heading ->
                        append("<h${(heading.depth + 1).coerceAtMost(4)}>")
                        append(escapeHtml(heading.title))
                        append("</h${(heading.depth + 1).coerceAtMost(4)}>\n")
                    }
                append(pageTexts[page])
                append("\n\n")
            }
        }
        chapters.add(PdfChapter(top.title, body.trim()))
    }
    return chapters.filter { it.body.isNotBlank() }
}

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

private val pdfTitlePatterns = listOf(
    Regex("""^\s*(part|book|prologue|epilogue|appendix|introduction|contents?|preface)\b.*$""", RegexOption.IGNORE_CASE),
    Regex("""^\s*(卷一|卷二|序章|尾声|前言|附录|目录|楔子)\b.*$""")
)
