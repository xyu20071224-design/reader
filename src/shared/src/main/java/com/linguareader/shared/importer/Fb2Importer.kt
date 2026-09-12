package com.linguareader.shared.importer

import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.util.Base64

/** Imports a FictionBook (FB2) XML file into the same chapter-based book model. */
class Fb2Importer(private val booksDir: File) {
    /** [source] 由调用方负责生命周期。 */
    fun import(source: File, fallbackTitle: String): Book {
        require(source.isFile && source.length() > 0) { "文件内容为空" }
        val id = ImportSupport.sha256(source).take(20)
        val destination = File(booksDir, id)
        if (destination.exists()) destination.deleteRecursively()
        destination.mkdirs()
        try {
            val parsed = parseFb2(source.readText(Charsets.UTF_8), fallbackTitle)
            var coverRelativePath: String? = null
            parsed.cover?.let { cover ->
                val ext = coverExtension(cover.contentType)
                if (ext != null && cover.base64.isNotBlank()) {
                    val file = File(destination, "cover.$ext")
                    runCatching {
                        file.writeBytes(Base64.getMimeDecoder().decode(cover.base64))
                    }.onSuccess {
                        coverRelativePath = file.relativeTo(destination).invariantSeparatorsPath
                    }
                }
            }
            val chapters = parsed.chapters.mapIndexed { index, chapter ->
                val file = File(destination, "chapter_%03d.xhtml".format(index + 1))
                file.writeText(fb2Xhtml(chapter.title, chapter.bodyHtml), Charsets.UTF_8)
                Chapter(
                    title = chapter.title.ifBlank { "第 ${index + 1} 章" },
                    relativePath = file.relativeTo(destination).invariantSeparatorsPath
                )
            }
            require(chapters.isNotEmpty()) { "FB2 中没有可阅读的章节" }
            return Book(
                id = id,
                title = parsed.title.ifBlank { "未命名图书" },
                author = parsed.author.ifBlank { "未知作者" },
                extractedDir = destination.absolutePath,
                coverRelativePath = coverRelativePath,
                chapters = chapters,
                addedAt = System.currentTimeMillis(),
                sourceFormat = "fb2"
            )
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw IllegalArgumentException(
                "无法导入该文件，请确认它是有效的 FB2 电子书：${error.message}",
                error
            )
        }
    }

    private fun coverExtension(contentType: String): String? = when {
        contentType.contains("jpeg") || contentType.contains("jpg") -> "jpg"
        contentType.contains("png") -> "png"
        contentType.contains("gif") -> "gif"
        contentType.contains("webp") -> "webp"
        else -> null
    }
}

data class Fb2Cover(val base64: String, val contentType: String)

data class Fb2Chapter(val title: String, val bodyHtml: String)

data class ParsedFb2(
    val title: String,
    val author: String,
    val cover: Fb2Cover?,
    val chapters: List<Fb2Chapter>
)

fun parseFb2(xml: String, fallbackTitle: String): ParsedFb2 {
    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
    val titleInfo = doc.selectFirst("title-info")
    val bookTitle = titleInfo?.selectFirst("book-title")?.text()?.trim().orEmpty()
    val author = titleInfo?.selectFirst("author")?.let { element ->
        val first = element.selectFirst("first-name")?.text()?.trim().orEmpty()
        val last = element.selectFirst("last-name")?.text()?.trim().orEmpty()
        listOf(first, last).filter(String::isNotBlank).joinToString(" ")
    }.orEmpty()
    val coverHref = titleInfo?.selectFirst("coverpage image")?.let { image ->
        image.attr("href").ifBlank { image.attr("l:href") }
    }
    val cover = coverHref
        ?.removePrefix("#")
        ?.let { id ->
            doc.selectFirst("binary[id=\"$id\"]")?.let { binary ->
                Fb2Cover(
                    base64 = binary.text().replace(Regex("\\s"), ""),
                    contentType = binary.attr("content-type").substringBefore(';').trim()
                )
            }
        }
    val mainBody = doc.selectFirst("body")
        ?: throw IllegalArgumentException("缺少 body")
    val sections = mainBody.children().filter { it.tagName().equals("section", ignoreCase = true) }
    val chapters = if (sections.isNotEmpty()) {
        sections.map { section ->
            val title = section.children()
                .firstOrNull { it.tagName().equals("title", ignoreCase = true) }
                ?.text()?.trim().orEmpty()
            Fb2Chapter(title, flattenFb2Element(section, 0).joinToString("\n"))
        }
    } else {
        listOf(Fb2Chapter("", flattenFb2Element(mainBody, 0).joinToString("\n")))
    }
    return ParsedFb2(
        title = bookTitle.ifBlank { fallbackTitle },
        author = author,
        cover = cover,
        chapters = chapters.filter { it.bodyHtml.isNotBlank() || it.title.isNotBlank() }
    )
}

fun flattenFb2Element(element: Element, depth: Int): List<String> {
    val out = mutableListOf<String>()
    for (child in element.children()) {
        when (child.tagName().lowercase()) {
            "title" -> child.text().trim().takeIf(String::isNotBlank)?.let { text ->
                val level = (depth + 1).coerceAtMost(4)
                out.add("<h$level>${escapeHtml(text)}</h$level>")
            }
            "p", "subtitle" -> child.text().trim().takeIf(String::isNotBlank)?.let {
                // 用 inlineHtml 而非纯 text()：保住 <emphasis>/<strong> 等行内格式
                // （审查 4-4：此前 em/strong 全丢，富文本 FB2 塌成纯文本）。
                val html = inlineHtml(child)
                if (html.isNotBlank()) out.add("<p>$html</p>")
            }
            "section" -> out.addAll(flattenFb2Element(child, depth + 1))
            "table" -> tableHtml(child).takeIf(String::isNotBlank)?.let(out::add)
            "poem" -> {
                val lines = child.select("v").map { it.text().trim() }.filter(String::isNotBlank)
                if (lines.isNotEmpty()) {
                    out.add("<p>${lines.joinToString("<br/>") { escapeHtml(it) }}</p>")
                }
            }
            "image", "empty-line" -> Unit
            else -> child.text().trim().takeIf(String::isNotBlank)?.let { text ->
                out.add("<p>${escapeHtml(text)}</p>")
            }
        }
    }
    return out
}

/**
 * 把 FB2 行内标记转成 XHTML 片段。
 *
 * **有意不新增叶块**：FB2 的 `emphasis`/`strong`/`strikethrough` 是行内元素，只改变
 * 字符与排版、不改变句号，所以修 4-4 不需要再动 `TtsPipelineContract.VERSION`
 * （该版本已在同批次因分句器改动升到 2）。`BLOCK_SELECTOR` 只认 `p`/`td`/`li` 等块级
 * 元素，行内标签进不去。
 */
private fun inlineHtml(element: Element): String {
    val out = StringBuilder()
    fun walk(node: Element) {
        if (node.textNodes().isNotEmpty()) {
            out.append(escapeHtml(node.textNodes().joinToString("") { textNode -> textNode.text() }))
        }
        for (child in node.children()) {
            val mapped = when (child.tagName().lowercase()) {
                "emphasis" -> "em"
                "strong" -> "strong"
                "strikethrough" -> "s"
                "code" -> "code"
                "sup" -> "sup"
                "sub" -> "sub"
                "a" -> "a"
                else -> null
            }
            if (mapped == null) {
                walk(child)
            } else {
                out.append("<").append(mapped).append(">")
                walk(child)
                out.append("</").append(mapped).append(">")
            }
        }
    }
    walk(element)
    return out.toString().trim()
}

/** FB2 `<table>` → XHTML 表格（审查 4-4：此前拍平成"表格单元甲表格单元乙"，无分隔）。 */
private fun tableHtml(table: Element): String {
    val body = table.children().firstOrNull { it.tagName().equals("tbody", ignoreCase = true) } ?: table
    val rows = body.children().filter { it.tagName().equals("tr", ignoreCase = true) }
    if (rows.isEmpty()) return ""
    val cells = rows.joinToString("") { row ->
        val tds = row.children().filter {
            it.tagName().equals("td", ignoreCase = true) || it.tagName().equals("th", ignoreCase = true)
        }
        "<tr>" + tds.joinToString("") { cell ->
            val tag = if (cell.tagName().equals("th", ignoreCase = true)) "th" else "td"
            "<$tag>${inlineHtml(cell).ifBlank { "&nbsp;" }}</$tag>"
        } + "</tr>"
    }
    return "<table>$cells</table>"
}

fun fb2Xhtml(title: String, bodyHtml: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
<title>${escapeHtml(title)}</title>
</head><body>
${bodyHtml.ifBlank { "<p></p>" }}
</body></html>"""