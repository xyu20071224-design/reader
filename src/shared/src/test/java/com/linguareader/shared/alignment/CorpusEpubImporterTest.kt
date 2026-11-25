package com.linguareader.shared.alignment

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CorpusEpubImporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun epub(name: String, language: String = "en", missing: Boolean = false, escape: Boolean = false, entity: Boolean = false): File {
        val file = File(temporary.root, name)
        val text = if (language == "en") "before<div>outer<p>app<em>le</em> fruit.</p>after</div>end" else "开头<div>外层<p>原创中文。</p>后段</div>结尾"
        val container = if (entity) """<!DOCTYPE container [<!ENTITY x SYSTEM "file:///unread-private-marker">]><container><rootfiles><rootfile full-path="&x;"/></rootfiles></container>"""
            else """<container><rootfiles><rootfile full-path="${if (escape) "../" else ""}content.opf"/></rootfiles></container>"""
        val opf = """<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Original fixture</dc:title><dc:language>$language</dc:language></metadata>
            <manifest><item id="a" href="Text/a+b%20c.xhtml" media-type="application/xhtml+xml"/><item id="z" href="Text/z.xhtml" media-type="application/xhtml+xml"/>
            <item id="cover" href="Text/cover.xhtml" media-type="application/xhtml+xml"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <item id="image" href="Text/cover.png" media-type="image/png"/></manifest><spine toc="ncx"><itemref idref="z"/><itemref idref="cover"/><itemref idref="a" linear="no"/></spine></package>"""
        val ncx = """<!DOCTYPE ncx SYSTEM "https://unused.invalid/ncx.dtd"><ncx><navMap><navPoint><navLabel><text>Opening label</text></navLabel><content src="Text/z.xhtml"/></navPoint>
            <navPoint><navLabel><text>Closing label</text></navLabel><content src="Text/a+b%20c.xhtml#tail"/></navPoint></navMap></ncx>"""
        val entries = linkedMapOf("META-INF/container.xml" to container, "content.opf" to opf, "toc.ncx" to ncx,
            "Text/cover.xhtml" to "<html><body><img src='cover.png'/></body></html>", "Text/cover.png" to "original image fixture",
            "Text/a+b c.xhtml" to "<html><body><p>${if(language == "en") "Final leaf." else "末尾原创。"}</p></body></html>")
        if (!missing) entries["Text/z.xhtml"] = "<html><body>$text</body></html>"
        ZipOutputStream(file.outputStream()).use { zip -> for ((path, content) in entries) {
            zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        } }
        return file
    }
    private fun import(file: File, suffix: String = "book", expected: String? = null): JSONObject =
        CorpusEpubImporter.importBook(file, File(temporary.root, suffix), "books/$suffix", "en", "fixture-v1", expected)

    @Test fun fullSpineOrderEmptyImageChapterAndMixedBodyTextArePreserved() {
        val file = epub("source.epub"); val before = file.readBytes(); val book = import(file)
        val chapters = book.getJSONArray("chapters")
        assertEquals(3, chapters.length()); assertEquals("Opening label", chapters.getJSONObject(0).getString("title"))
        assertEquals("", chapters.getJSONObject(1).getString("title")); assertEquals(0, chapters.getJSONObject(1).getInt("paragraphCount"))
        assertEquals("Closing label", chapters.getJSONObject(2).getString("title"))
        val paragraphs = JSONObject(File(temporary.root, "book/spine-0000.json").readText()).getJSONArray("paragraphs")
        assertEquals(listOf("before", "outer", "apple fruit.", "after", "end"), (0 until paragraphs.length()).map(paragraphs::getString))
        val provenance = JSONObject(File(temporary.root, "book/provenance.json").readText())
        assertEquals(3, provenance.getInt("spineCount")); assertEquals("no", provenance.getJSONArray("spine").getJSONObject(2).getString("linear"))
        assertEquals(1, provenance.getJSONArray("spine").getJSONObject(1).getJSONArray("images").length())
        assertArrayEquals(before, file.readBytes()); assertArrayEquals(before, File(temporary.root, "book/original.epub").readBytes())
        assertEquals(AlignmentCorpusInputs.textDigest(listOf(AlignmentChapter("", listOf("before", "outer", "apple fruit.", "after", "end")), AlignmentChapter("", emptyList()), AlignmentChapter("", listOf("Final leaf.")))), book.getString("textSha256"))
    }

    @Test fun aMissingDeclaredSpineEntryFailsRatherThanSkippingTheChapter() {
        assertTrue(runCatching { import(epub("missing.epub", missing = true)) }.isFailure)
        assertFalse(File(temporary.root, "book/provenance.json").exists())
    }

    @Test fun rootTraversalAndExternalEntityDeclarationsAreRejectedOffline() {
        assertTrue(runCatching { import(epub("traversal.epub", escape = true), "traversal") }.isFailure)
        assertTrue(runCatching { import(epub("entity.epub", entity = true), "entity") }.isFailure)
        assertFalse(File(temporary.root, "content.opf").exists())
    }

    @Test fun explicitExpectedShaRejectsTheWrongEditionBeforeCopying() {
        val file = epub("wrong-version.epub")
        assertTrue(runCatching { import(file, expected = "0".repeat(64)) }.isFailure)
        assertFalse(File(temporary.root, "book").exists())
    }

    @Test fun publishedManifestPassesLoaderPreflightWithoutInventingTruthOrMemory() {
        val source = epub("original-source.epub"); val target = epub("original-target.epub", "zh"); val ai = epub("original-ai.epub", "zh")
        val tsv = File(temporary.root, "dictionary.tsv").apply { writeText("apple\t苹果\n>apples\tapple\n") }
        val metadata = File(temporary.root, "dictionary.json").apply { writeText(JSONObject().put("schemaVersion", 1)
            .put("exportVersion", "ecdict-alignment-v1").put("sourceSha256", AlignmentCorpusInputs.sha256(source))
            .put("sha256", AlignmentCorpusInputs.sha256(tsv)).put("entries", 1).put("forms", 1).toString()) }
        val output = File(temporary.root, "private-output")
        val args = CorpusEpubImporter.Inputs(source, target, tsv, metadata, output, "original-fixture-v1", ai,
            AlignmentCorpusInputs.sha256(source), AlignmentCorpusInputs.sha256(target), AlignmentCorpusInputs.sha256(ai))
        val manifest = CorpusEpubImporter.prepare(args)
        assertFalse(manifest.getJSONObject("corpora").getJSONObject("lotr").has("golden"))
        assertFalse(manifest.getJSONObject("corpora").has("ai-translation"))
        assertTrue(manifest.getJSONObject("additionalBooks").has("unreviewed-ai-edition"))
        assertEquals(3, AlignmentCorpusInputs.load(output, "F12", report = {}).english.size)
        assertEquals(1, AlignmentCorpusInputs.load(output, "F01", report = {}).requireDictionary().entryCount)
        assertTrue(runCatching { AlignmentCorpusInputs.load(output, "F02", report = {}) }.isFailure)
        val published = File(output, AlignmentCorpusInputs.MANIFEST).readBytes()
        assertTrue(runCatching { CorpusEpubImporter.prepare(args) }.isFailure)
        assertArrayEquals(published, File(output, AlignmentCorpusInputs.MANIFEST).readBytes())
    }
}
