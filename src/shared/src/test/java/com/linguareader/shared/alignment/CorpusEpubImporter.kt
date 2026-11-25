package com.linguareader.shared.alignment

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.w3c.dom.Document
import org.w3c.dom.Element as XmlElement
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Private, offline material preparation. Never generates golden samples or translation-memory pairs. */
object CorpusEpubImporter {
    private const val LIMIT = 500L * 1024 * 1024
    private const val CHAPTER_LIMIT = 16 * 1024 * 1024
    private val blocks = setOf("p", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "td", "th", "caption", "figcaption", "pre", "div", "section", "article", "header", "footer", "dt", "dd", "address")
    private val nonBody = setOf("script", "style", "head", "title", "desc", "metadata")

    data class Inputs(val source: File, val target: File, val dictionary: File, val dictionaryMetadata: File,
        val output: File, val version: String, val ai: File? = null,
        val sourceSha256: String? = null, val targetSha256: String? = null, val aiSha256: String? = null)

    @JvmStatic fun main(args: Array<String>) {
        val allowed = setOf("source", "target", "dictionary", "dictionary-metadata", "out", "version", "ai", "source-sha256", "target-sha256", "ai-sha256")
        require(args.size % 2 == 0) { "Expected --name value arguments" }
        val options = linkedMapOf<String, String>()
        args.toList().chunked(2).forEach { (flag, value) ->
            require(flag.startsWith("--") && flag.drop(2) in allowed && value.isNotBlank() && options.put(flag.drop(2), value) == null) { "Invalid or duplicate importer option" }
        }
        fun required(key: String) = requireNotNull(options[key]) { "Missing --$key" }
        prepare(Inputs(File(required("source")), File(required("target")), File(required("dictionary")), File(required("dictionary-metadata")),
            File(required("out")), required("version"), options["ai"]?.let(::File), options["source-sha256"], options["target-sha256"], options["ai-sha256"]))
        println("CORPUS_IMPORT_READY: full ordered spines published; no golden or AI-memory approval generated")
    }

    fun prepare(input: Inputs): JSONObject {
        require(input.version.isNotBlank()) { "Material version must be nonblank" }
        check(input.output.isDirectory || input.output.mkdirs()) { "Cannot create private corpus root" }
        val root = input.output.toPath().toRealPath().toFile()
        check(!File(root, AlignmentCorpusInputs.MANIFEST).exists()) { "Existing manifest is immutable to this importer; choose a new private corpus root" }
        val versionDirectory = "materials-${UUID.randomUUID()}"
        val staging = File(root, ".import-${UUID.randomUUID()}").apply { check(mkdir()) }
        val materials = File(staging, versionDirectory).apply { check(mkdir()) }
        try {
            val source = importBook(input.source, File(materials, "books/source"), "$versionDirectory/books/source", "en", input.version, input.sourceSha256)
            val target = importBook(input.target, File(materials, "books/target"), "$versionDirectory/books/target", "zh", input.version, input.targetSha256)
            val extra = input.ai?.let { importBook(it, File(materials, "books/ai"), "$versionDirectory/books/ai", "zh", input.version, input.aiSha256) }
            val dictionary = File(materials, "dictionary").apply { check(mkdir()) }
            copySynced(input.dictionary, File(dictionary, "ecdict.tsv"))
            copySynced(input.dictionaryMetadata, File(dictionary, "metadata.json"))
            val manifest = JSONObject().put("schemaVersion", 1).put("corpora", JSONObject().put("lotr", JSONObject()
                .put("version", input.version).put("source", source).put("target", target)))
                .put("dictionary", JSONObject().put("path", "$versionDirectory/dictionary/ecdict.tsv").put("sha256", sha(File(dictionary, "ecdict.tsv")))
                    .put("metadata", JSONObject().put("path", "$versionDirectory/dictionary/metadata.json").put("sha256", sha(File(dictionary, "metadata.json")))))
            if (extra != null) manifest.put("additionalBooks", JSONObject().put("unreviewed-ai-edition", extra))
            write(File(staging, AlignmentCorpusInputs.MANIFEST), manifest.toString(2).toByteArray(Charsets.UTF_8))
            // Loader-only validation, not a whole-book aligner run or approval of any truth.
            AlignmentCorpusInputs.load(staging, "F12", report = {})
            AlignmentCorpusInputs.verifyDictionary(staging)
            syncDirectory(materials)
            Files.move(materials.toPath(), File(root, versionDirectory).toPath(), StandardCopyOption.ATOMIC_MOVE)
            Files.move(File(staging, AlignmentCorpusInputs.MANIFEST).toPath(), File(root, AlignmentCorpusInputs.MANIFEST).toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(root)
            return manifest
        } finally { staging.deleteRecursively() }
    }

    /** All spine occurrences are preserved, including repeated, image-only and linear=no entries. */
    internal fun importBook(source: File, destination: File, prefix: String, language: String, version: String, expected: String? = null): JSONObject {
        require(source.isFile && source.length() in 1..LIMIT) { "Missing or oversized input EPUB" }
        val sourceSha = sha(source)
        require(expected == null || (expected.matches(Regex("[0-9a-f]{64}")) && expected == sourceSha)) { "Input EPUB does not match the expected SHA-256" }
        check(destination.mkdirs()) { "Book destination already exists" }
        val original = File(destination, "original.epub")
        copySynced(source, original)
        check(sha(original) == sourceSha) { "Input changed while copying" }
        val root = destination.toPath().toRealPath().toFile()
        val chapters = mutableListOf<AlignmentChapter>()
        val declarations = JSONArray()
        val spineAudit = JSONArray()
        val provenance = JSONObject().put("schemaVersion", 1).put("extractionVersion", "epub-full-spine-v1")
            .put("sourcePath", source.canonicalPath).put("sourceBytes", source.length()).put("sourceSha256", sourceSha)
            .put("originalCopy", "$prefix/original.epub").put("languageHint", language)
            .put("directoryFsyncSupported", !isWindows())
        ZipFile(original).use { zip ->
            val entries = zip.entries().asSequence().toList()
            check(entries.size <= 10000 && entries.map { it.name }.distinct().size == entries.size) { "Too many or duplicate ZIP entries" }
            var total = 0L
            val inventory = JSONArray()
            for (entry in entries) {
                check(!entry.name.startsWith('/') && '\\' !in entry.name && ':' !in entry.name && entry.name.none { it.code < 32 } &&
                    entry.name.trimEnd('/').split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe ZIP entry" }
                if (entry.isDirectory) continue
                val digest = MessageDigest.getInstance("SHA-256"); val crc = CRC32(); var count = 0L
                zip.getInputStream(entry).use { stream ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = stream.read(buffer); if (n < 0) break
                        count += n; total += n; check(total <= LIMIT) { "EPUB uncompressed byte limit exceeded" }
                        digest.update(buffer, 0, n); crc.update(buffer, 0, n)
                    }
                }
                check(entry.size == count && entry.crc == crc.value) { "ZIP size or CRC mismatch" }
                inventory.put(JSONObject().put("path", entry.name).put("bytes", count).put("sha256", hex(digest.digest())))
            }
            provenance.put("zipFiles", inventory).put("zipEntryCount", entries.size).put("uncompressedBytes", total)
            fun bytes(path: String): ByteArray {
                val entry = zip.getEntry(path) ?: error("Declared EPUB item is missing")
                check(!entry.isDirectory && entry.size in 0..CHAPTER_LIMIT) { "EPUB metadata/chapter exceeds limit" }
                return zip.getInputStream(entry).use { it.readBytes() }.also { check(it.size <= CHAPTER_LIMIT) }
            }
            val container = xml(bytes("META-INF/container.xml"))
            val rootfiles = container.getElementsByTagNameNS("*", "rootfile")
            check(rootfiles.length == 1) { "Expected exactly one EPUB package rootfile" }
            val packagePath = resolve("", (rootfiles.item(0) as XmlElement).getAttribute("full-path"))
            val opf = xml(bytes(packagePath)); val packageElement = opf.documentElement
            val metadata = child(packageElement, "metadata")
            provenance.put("opfPath", packagePath).put("metadata", JSONArray(elements(metadata).map { e ->
                JSONObject().put("namespace", e.namespaceURI ?: "").put("name", e.localName ?: e.tagName).put("text", e.textContent)
            }))
            val items = elements(child(packageElement, "manifest"))
            check(items.all { it.localName == "item" } && items.all { it.getAttribute("id").isNotBlank() } &&
                items.map { it.getAttribute("id") }.distinct().size == items.size) { "Invalid or duplicate manifest identity" }
            val byId = items.associateBy { it.getAttribute("id") }
            val opfDirectory = packagePath.substringBeforeLast('/', "")
            val navTitles = linkedMapOf<String, String>()
            items.firstOrNull { "nav" in it.getAttribute("properties").split(Regex("\\s+")) }?.let { item ->
                val navPath = resolve(opfDirectory, item.getAttribute("href"))
                val nav = Jsoup.parse(bytes(navPath).toString(Charsets.UTF_8))
                for (link in nav.select("nav a[href]")) {
                    val path = resolve(navPath.substringBeforeLast('/', ""), link.attr("href"))
                    if (link.text().isNotBlank()) navTitles.putIfAbsent(path, link.text())
                }
            }
            val spine = child(packageElement, "spine")
            (byId[spine.getAttribute("toc")] ?: items.firstOrNull { it.getAttribute("media-type") == "application/x-dtbncx+xml" })?.let { item ->
                val ncxPath = resolve(opfDirectory, item.getAttribute("href")); val ncx = xml(bytes(ncxPath))
                val points = ncx.getElementsByTagNameNS("*", "navPoint")
                for (i in 0 until points.length) {
                    val point = points.item(i) as XmlElement
                    val content = elements(point).firstOrNull { it.localName == "content" } ?: continue
                    val label = elements(point).firstOrNull { it.localName == "navLabel" }?.textContent?.trim().orEmpty()
                    if (label.isNotBlank()) navTitles.putIfAbsent(resolve(ncxPath.substringBeforeLast('/', ""), content.getAttribute("src")), label)
                }
            }
            val readingOrder = elements(spine)
            check(readingOrder.isNotEmpty() && readingOrder.all { it.localName == "itemref" }) { "Invalid or empty OPF spine" }
            provenance.put("spineCount", readingOrder.size)
            for ((index, reference) in readingOrder.withIndex()) {
                val item = requireNotNull(byId[reference.getAttribute("idref")]) { "Spine references a missing manifest id" }
                val path = resolve(opfDirectory, item.getAttribute("href")); val raw = bytes(path)
                val media = item.getAttribute("media-type")
                val html = if (media in setOf("application/xhtml+xml", "text/html", "image/svg+xml")) Jsoup.parse(raw.inputStream(), null, "")
                    else { check(media.startsWith("image/")) { "Unsupported spine content type" }; null }
                val title = navTitles[path] ?: html?.selectFirst("h1,h2,h3,h4,h5,h6")?.text()?.takeIf { it.isNotBlank() }
                    ?: html?.title().orEmpty()
                val paragraphs = html?.body()?.let(::paragraphs).orEmpty()
                val alignment = AlignmentChapter(title, paragraphs); chapters += alignment
                val name = "spine-${index.toString().padStart(4, '0')}"
                val paragraphBytes = JSONObject().put("paragraphs", JSONArray(paragraphs)).toString(2).toByteArray(Charsets.UTF_8)
                val chapterFile = File(root, "$name.json"); write(chapterFile, paragraphBytes)
                write(File(root, "$name.original"), raw)
                val count = AlignmentCorpusInputs.sentenceCount(listOf(alignment))
                declarations.put(JSONObject().put("id", "$language-$name").put("title", title).put("path", "$prefix/$name.json")
                    .put("sha256", sha(chapterFile)).put("format", "paragraphs-json").put("paragraphCount", paragraphs.size).put("sentenceCount", count))
                val images = JSONArray()
                html?.select("img[src],image[href],image[xlink:href]")?.forEach { image ->
                    val href = sequenceOf(image.attr("src"), image.attr("href"), image.attr("xlink:href")).first { it.isNotEmpty() }
                    if (href.startsWith("data:") || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(href)) {
                        images.put(JSONObject().put("externalOrInline", true).put("referenceSha256", hex(MessageDigest.getInstance("SHA-256").digest(href.toByteArray(Charsets.UTF_8)))))
                    } else {
                        val imagePath = resolve(path.substringBeforeLast('/', ""), href)
                        check(zip.getEntry(imagePath)?.isDirectory == false) { "Missing local image reference" }
                        images.put(JSONObject().put("path", imagePath).put("alt", image.attr("alt")))
                    }
                }
                spineAudit.put(JSONObject().put("index", index).put("idref", reference.getAttribute("idref")).put("linear", reference.getAttribute("linear").ifEmpty { "yes" })
                    .put("sourcePath", path).put("sourceSha256", hex(MessageDigest.getInstance("SHA-256").digest(raw))).put("mediaType", media)
                    .put("title", title).put("paragraphFile", "$prefix/$name.json").put("paragraphCount", paragraphs.size).put("sentenceCount", count)
                    .put("textCharacters", paragraphs.sumOf { it.length }).put("images", images))
            }
            provenance.put("spine", spineAudit)
            val embedded = entries.filter { !it.isDirectory && it.name.lowercase().let { name -> listOf("memory", "sentence-lines", "alignment").any(name::contains) } }
            provenance.put("possibleEmbeddedRecords", JSONArray(embedded.map { JSONObject().put("path", it.name).put("bytes", it.size) }))
        }
        check(sha(source) == sourceSha) { "Original EPUB changed during import" }
        provenance.put("sourceUnchangedAfterImport", true).put("textSha256", AlignmentCorpusInputs.textDigest(chapters))
            .put("paragraphCount", chapters.sumOf { it.paragraphs.size }).put("sentenceCount", AlignmentCorpusInputs.sentenceCount(chapters))
        write(File(root, "provenance.json"), provenance.toString(2).toByteArray(Charsets.UTF_8))
        syncDirectory(root)
        return JSONObject().put("language", language).put("version", "$version/$language/${sourceSha.take(16)}")
            .put("paragraphCount", chapters.sumOf { it.paragraphs.size }).put("sentenceCount", AlignmentCorpusInputs.sentenceCount(chapters))
            .put("textSha256", AlignmentCorpusInputs.textDigest(chapters)).put("chapters", declarations)
            .put("provenance", JSONObject().put("path", "$prefix/provenance.json").put("sha256", sha(File(root, "provenance.json"))))
    }

    /** Text-node traversal preserves mixed parent text and inline word joins; it never samples chapters. */
    private fun paragraphs(body: Element): List<String> {
        val result = mutableListOf<String>(); val buffer = StringBuilder(); val expected = StringBuilder()
        fun normalize(text: String) = text.replace(Regex("[\\s\\u00a0]+"), " ").trim()
        fun flush() { normalize(buffer.toString()).takeIf(String::isNotBlank)?.let(result::add); buffer.setLength(0) }
        fun walk(node: Node) {
            if (node is TextNode) { buffer.append(node.wholeText); expected.append(node.wholeText); return }
            val tag = (node as? Element)?.tagName()?.substringAfter(':')?.lowercase()
            if (tag in nonBody) return
            if (tag in blocks) flush()
            if (tag == "br") { buffer.append(' '); expected.append(' ') }
            node.childNodes().forEach(::walk)
            if (tag in blocks) flush()
        }
        walk(body); flush()
        fun compact(text: String) = text.filterNot { it.isWhitespace() || Character.isSpaceChar(it) }
        check(compact(expected.toString()) == compact(result.joinToString(""))) { "Extraction lost or reordered body characters" }
        return result
    }

    private fun xml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; isXIncludeAware = false; isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        check(!bytes.toString(Charsets.UTF_8).contains(Regex("<!ENTITY", RegexOption.IGNORE_CASE))) { "Inline entity declarations are not permitted" }
        return factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }.parse(ByteArrayInputStream(bytes))
    }
    private fun elements(parent: org.w3c.dom.Node) = (0 until parent.childNodes.length).mapNotNull { parent.childNodes.item(it) as? XmlElement }
    private fun child(parent: XmlElement, name: String) = elements(parent).single { it.localName == name }
    private fun resolve(directory: String, href: String): String {
        val decoded = URLDecoder.decode(href.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8")
        check(decoded.isNotEmpty() && !decoded.startsWith('/') && ':' !in decoded && '\\' !in decoded && decoded.none { it.code < 32 }) { "Unsafe EPUB reference" }
        val parts = mutableListOf<String>()
        for (part in (if (directory.isEmpty()) decoded else "$directory/$decoded").split('/')) when (part) {
            "", "." -> Unit
            ".." -> { check(parts.isNotEmpty()) { "EPUB reference escapes archive" }; parts.removeAt(parts.lastIndex) }
            else -> parts += part
        }
        return parts.joinToString("/").also { check(it.isNotEmpty()) }
    }
    private fun write(file: File, bytes: ByteArray) { file.parentFile.mkdirs(); FileOutputStream(file).use { it.write(bytes); it.fd.sync() } }
    private fun copySynced(source: File, destination: File) { FileOutputStream(destination).use { output -> source.inputStream().use { it.copyTo(output) }; output.fd.sync() } }
    private fun sha(file: File) = AlignmentCorpusInputs.sha256(file)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    // Windows does not expose directory handles through FileChannel. All files are still fsynced;
    // provenance explicitly prevents claiming POSIX-equivalent power-loss durability there.
    private fun isWindows() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private fun syncDirectory(directory: File) {
        if (!isWindows()) FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}
