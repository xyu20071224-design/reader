package com.linguareader.shared.importer

import com.linguareader.shared.data.Book
import com.linguareader.shared.packs.SafeZip
import com.linguareader.shared.data.Chapter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class EpubImporter(private val booksDir: File) {
    /** [source] 由调用方负责生命周期（Android facade 传临时拷贝并自删；桌面传原文件）。 */
    fun import(source: File): Book {
        require(source.isFile && source.length() > 0) { "文件内容为空" }
        val id = ImportSupport.sha256(source).take(20)
        val destination = File(booksDir, id)

        if (destination.exists()) destination.deleteRecursively()
        destination.mkdirs()

        try {
            extractSafely(source, destination)
            return parsePackage(destination, id)
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw IllegalArgumentException(
                "无法导入该文件，请确认它是未加密的 EPUB：${error.message}",
                error
            )
        }
    }


    private fun extractSafely(epub: File, destination: File) {
        // 护栏的唯一实现在 SafeZip（资源包安装共用同一份）：路径穿越、条目数、
        // 解压总量。这里只给 EPUB 自己的阈值与消息前缀。
        SafeZip.extract(
            zip = epub,
            destination = destination,
            maxEntries = 10_000,
            maxBytes = 500L * 1024 * 1024,
            label = "EPUB"
        )
    }

    private fun parsePackage(inputRoot: File, id: String): Book {
        // Android exposes the same sandbox through /data/user/0 and /data/data.
        // Normalize once so relative chapter paths never contain ".." segments.
        val root = inputRoot.canonicalFile
        val container = File(root, "META-INF/container.xml")
        require(container.exists()) { "缺少 META-INF/container.xml" }
        val containerDoc = Jsoup.parse(container, "UTF-8", "", Parser.xmlParser())
        val packagePath = containerDoc.selectFirst("rootfile")?.attr("full-path")
            ?.takeIf { it.isNotBlank() }
            ?: error("找不到 EPUB package document")
        val packageFile = File(root, packagePath).canonicalFile
        require(packageFile.exists()) { "找不到 OPF 文件" }
        require(packageFile.path.startsWith(root.canonicalPath + File.separator)) { "OPF 路径不安全" }

        val opf = Jsoup.parse(packageFile, "UTF-8", "", Parser.xmlParser())
        val title = textOf(opf, "dc|title", "title").ifBlank { "未命名图书" }
        val author = textOf(opf, "dc|creator", "creator").ifBlank { "未知作者" }
        val opfDir = packageFile.parentFile ?: root

        val manifest = opf.select("manifest > item, item").associateBy { it.attr("id") }
        val spineIds = opf.select("spine > itemref, itemref").map { it.attr("idref") }
        require(spineIds.isNotEmpty()) { "EPUB 没有可阅读章节" }

        val navTitles = readNavigationTitles(opfDir, manifest.values.firstOrNull {
            it.attr("properties").split(" ").contains("nav")
        }?.attr("href"))

        val chapters = spineIds.mapNotNull { idRef ->
            val item = manifest[idRef] ?: return@mapNotNull null
            val href = decodeHref(item.attr("href")).substringBefore('#')
            val file = File(opfDir, href).canonicalFile
            if (!file.exists() || !file.path.startsWith(root.canonicalPath + File.separator)) {
                return@mapNotNull null
            }
            sanitizeHtml(file)
            val relative = file.relativeTo(root).invariantSeparatorsPath
            val titleFromNav = navTitles[relative.substringAfter(packageFile.parentFile?.relativeTo(root)?.invariantSeparatorsPath.orEmpty()).trimStart('/')]
                ?: navTitles[href]
            Chapter(
                title = titleFromNav ?: chapterTitle(file) ?: "第 ${spineIds.indexOf(idRef) + 1} 章",
                relativePath = relative
            )
        }
        require(chapters.isNotEmpty()) { "没有找到可渲染的 XHTML 章节" }

        val coverItem = manifest.values.firstOrNull {
            it.attr("properties").split(" ").contains("cover-image")
        } ?: opf.selectFirst("meta[name=cover]")?.attr("content")?.let { manifest[it] }
        val coverPath = coverItem?.attr("href")?.let(::decodeHref)?.let {
            File(opfDir, it).canonicalFile
        }?.takeIf { it.exists() && it.path.startsWith(root.canonicalPath + File.separator) }
            ?.relativeTo(root)?.invariantSeparatorsPath

        return Book(
            id = id,
            title = title.trim(),
            author = author.trim(),
            extractedDir = root.absolutePath,
            coverRelativePath = coverPath,
            chapters = chapters,
            addedAt = System.currentTimeMillis()
        )
    }

    private fun sanitizeHtml(file: File) {
        val doc = Jsoup.parse(file, "UTF-8")
        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
            .prettyPrint(false)
        doc.select("script, iframe, object, embed").remove()
        doc.select("meta[name=viewport]").remove()
        doc.head().prependElement("meta")
            .attr("name", "viewport")
            .attr(
                "content",
                "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
            )
        doc.allElements.forEach { element ->
            element.attributes().asList()
                .filter { it.key.startsWith("on", ignoreCase = true) }
                .forEach { element.removeAttr(it.key) }
        }
        file.writeText(doc.outerHtml())
    }

    private fun chapterTitle(file: File): String? {
        val doc = Jsoup.parse(file, "UTF-8")
        return sequenceOf(
            doc.selectFirst("h1")?.text(),
            doc.selectFirst("h2")?.text(),
            doc.title()
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun readNavigationTitles(opfDir: File, navHref: String?): Map<String, String> {
        if (navHref.isNullOrBlank()) return emptyMap()
        val navFile = File(opfDir, decodeHref(navHref).substringBefore('#'))
        if (!navFile.exists()) return emptyMap()
        val doc = Jsoup.parse(navFile, "UTF-8")
        return doc.select("nav a[href], a[href]").mapNotNull { link ->
            val href = decodeHref(link.attr("href")).substringBefore('#')
            val label = link.text().trim()
            if (href.isBlank() || label.isBlank()) null else href to label
        }.toMap()
    }

    private fun textOf(doc: Document, vararg selectors: String): String {
        selectors.forEach { selector ->
            runCatching { doc.selectFirst(selector)?.text() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return ""
    }

    private fun decodeHref(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

}
