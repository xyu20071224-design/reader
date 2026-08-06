package com.linguareader.app.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Imports a plain-text (TXT) file into the same chapter-based book model. */
class TextImporter(
    private val context: Context,
    private val booksDir: File
) {
    fun import(uri: Uri): Book {
        val source = ImportSupport.copySource(context, uri)
        val id = ImportSupport.sha256(source).take(20)
        val destination = File(booksDir, id)
        if (destination.exists()) destination.deleteRecursively()
        destination.mkdirs()
        try {
            val text = decodeTextFile(source)
            val parsed = splitChapters(text)
            val title = ImportSupport.baseName(context, uri).ifBlank { "未命名图书" }
            val chapters = parsed.mapIndexed { index, (chapterTitle, body) ->
                val file = File(destination, "chapter_%03d.xhtml".format(index + 1))
                file.writeText(textToXhtml(chapterTitle, body), Charsets.UTF_8)
                Chapter(
                    title = chapterTitle.ifBlank { "第 ${index + 1} 章" },
                    relativePath = file.relativeTo(destination).invariantSeparatorsPath
                )
            }
            require(chapters.isNotEmpty()) { "文件中没有可阅读的文本内容" }
            return Book(
                id = id,
                title = title,
                author = "未知作者",
                extractedDir = destination.absolutePath,
                coverRelativePath = null,
                chapters = chapters,
                addedAt = System.currentTimeMillis(),
                sourceFormat = "txt"
            )
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw IllegalArgumentException(
                "无法导入该文件，请确认它是可读取的文本文件：${error.message}",
                error
            )
        } finally {
            source.delete()
        }
    }
}

internal fun decodeTextFile(file: File): String {
    val bytes = file.readBytes()
    val bom = detectBom(bytes)
    if (bom != null) {
        return decodeStrict(bytes, bom.size, Charset.forName(bom.name))
            ?: bytes.toString(Charsets.UTF_8)
    }
    return decodeStrict(bytes, 0, Charsets.UTF_8)
        ?: decodeStrict(bytes, 0, Charset.forName("GBK"))
        ?: bytes.toString(Charsets.ISO_8859_1)
}

private fun detectBom(bytes: ByteArray): Bom? = when {
    bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte() -> Bom("UTF-8", 3)
    bytes.size >= 2 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xFE.toByte() -> Bom("UTF-16LE", 2)
    bytes.size >= 2 &&
        bytes[0] == 0xFE.toByte() &&
        bytes[1] == 0xFF.toByte() -> Bom("UTF-16BE", 2)
    else -> null
}

private data class Bom(val name: String, val size: Int)

private fun decodeStrict(bytes: ByteArray, offset: Int, charset: Charset): String? = runCatching {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
        .toString()
}.getOrNull()

internal fun splitChapters(text: String): List<Pair<String, String>> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val chapters = mutableListOf<Pair<String, MutableList<String>>>()
    var current: Pair<String, MutableList<String>>? = null
    for (raw in lines) {
        val line = raw.trim()
        val isTitle = line.isNotBlank() && chapterPatterns.any { it.containsMatchIn(line) }
        if (isTitle) {
            if (current != null) chapters.add(current)
            current = line to mutableListOf()
        } else {
            if (current == null) current = "" to mutableListOf()
            current!!.second.add(raw)
        }
    }
    if (current != null) chapters.add(current)
    if (chapters.isEmpty()) chapters.add("" to mutableListOf())
    return chapters.map { (title, body) -> title to body.joinToString("\n") }
}

internal fun textToXhtml(title: String, body: String): String {
    val paragraphs = body
        .split(Regex("""\n\s*\n"""))
        .map { it.replace('\n', ' ').trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n") { "<p>${escapeHtml(it)}</p>" }
    return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
<title>${escapeHtml(title)}</title>
</head><body>
${paragraphs.ifBlank { "<p></p>" }}
</body></html>"""
}

internal fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

internal val chapterPatterns = listOf(
    Regex("""^\s*(第\s*[0-9一二三四五六七八九十百千万零]+\s*[章节回卷部篇]).*$"""),
    Regex("""^\s*chapter\s+(\d+|[ivxlcdm]+)([:：.\s].*)?$""", RegexOption.IGNORE_CASE),
    Regex("""^\s*\d{1,3}[.、]\s*.+$""")
)