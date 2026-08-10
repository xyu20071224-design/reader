package com.linguareader.app.data

import com.linguareader.app.platform.androidAppContext
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

private data class OutlineEntry(
    val title: String,
    val pageIndex: Int?,
    val depth: Int
)

private const val PAGE_MARKER = "\u0000LINGUAREADER_PAGE\u0000"

internal actual fun extractPdf(
    file: File,
    scratchDir: File?,
    maxPages: Int
): ParsedPdf {
    PDFBoxResourceLoader.init(androidAppContext)
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
