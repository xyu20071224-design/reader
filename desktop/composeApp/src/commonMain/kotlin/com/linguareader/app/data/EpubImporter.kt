package com.linguareader.app.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

class EpubImporter(private val booksDir: File) {
    fun import(source: PreparedSource): Book {
        val id = ImportSupport.sha256(source.file).take(20)
        val destination = File(booksDir, id)

        if (destination.exists()) destination.deleteRecursively()
        destination.mkdirs()

        try {
            extractSafely(source.file, destination)
            return parsePackage(destination, id)
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw IllegalArgumentException(
                "无法导入该文件，请确认它是未加密的 EPUB：${error.message}",
                error
            )
        } finally {
            source.file.delete()
        }
    }


    private fun extractSafely(epub: File, destination: File) {
        val rootPath = destination.canonicalPath + File.separator
        var totalBytes = 0L
        var entryCount = 0

        ZipFile(epub).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount += 1
                require(entryCount <= 10_000) { "EPUB 文件条目过多" }

                val output = File(destination, entry.name)
                require(output.canonicalPath.startsWith(rootPath)) { "检测到不安全的文件路径" }

                if (entry.isDirectory) {
                    output.mkdirs()
                    continue
                }

                totalBytes += entry.size.coerceAtLeast(0)
                require(totalBytes <= 500L * 1024 * 1024) { "解压内容超过 500MB" }
                output.parentFile?.mkdirs()
                // The zip header size is attacker-controlled, so count bytes
                // actually written and re-check the quota while decompressing.
                zip.getInputStream(entry).use { input ->
                    output.outputStream().use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            totalBytes += read
                            require(totalBytes <= 500L * 1024 * 1024) { "解压内容超过 500MB" }
                        }
                    }
                }
            }
        }
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
