package com.linguareader.app.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.importer.ImportSupport as SharedImportSupport
import com.linguareader.shared.importer.textToXhtml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 第四轮审查 审查点4「非 EPUB 导入耗时与排版」的可复现实验台。
 * 这不是项目正式测试，**不要提交**。用法见同目录 README.md。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReproImportAuditTest {

    private val work = File("/tmp/lingua-review/work")
    private lateinit var booksDir: File

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        work.mkdirs()
        booksDir = File(work, "books").apply { deleteRecursively(); mkdirs() }
    }

    // ---- 样例生成（全部合成，放临时目录） ----

    private fun makeTxt(charsetName: String, crlf: Boolean): File {
        val sb = StringBuilder()
        var n = 0
        while (sb.length < 2_700_000) {
            n++
            sb.append("第${n}章 测试章节标题\n\n")
            repeat(12) { p ->
                sb.append("\u3000\u3000这是第${n}章第${p + 1}段。它以全角空格缩进开头，")
                sb.append("并且这一行在源文件里被硬换行\n")
                sb.append("折成了两行，用来检验导入器是否把段内换行保留为间距。\n\n")
            }
            sb.append("* * *\n\n")
        }
        val text = if (crlf) sb.toString().replace("\n", "\r\n") else sb.toString()
        val name = "sample-" + charsetName.lowercase() + if (crlf) "-crlf.txt" else ".txt"
        return File(work, name).apply { writeText(text, java.nio.charset.Charset.forName(charsetName)) }
    }

    private fun makeFb2(): File {
        val b64 = java.util.Base64.getEncoder().encodeToString(ByteArray(600 * 1024) { 1 })
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" xmlns:l=\"http://www.w3.org/1999/xlink\">")
            append("<description><title-info><book-title>FB2 排版样例</book-title>")
            append("<author><first-name>Ada</first-name><last-name>Lovelace</last-name></author>")
            append("<coverpage><image l:href=\"#cover.jpg\"/></coverpage>")
            append("</title-info></description><body>")
            repeat(60) { s ->
                append("<section><title><p>第${s + 1}节 标题</p></title>")
                append("<subtitle>副标题文本</subtitle>")
                append("<p>正文段落一，包含<emphasis>强调</emphasis>与<strong>加粗</strong>。</p>")
                append("<empty-line/>")
                append("<p>正文段落二。</p>")
                append("<p>${"正文段落三，长度足以接近真实小说的一章。".repeat(8)}</p>")
                append("<poem><title><p>诗题</p></title><stanza><v>诗行一</v><v>诗行二</v></stanza></poem>")
                append("<image l:href=\"#cover.jpg\"/>")
                append("<table><tr><td>表格单元甲</td><td>表格单元乙</td></tr></table>")
                append("<section><title><p>第${s + 1}.1 嵌套小节</p></title><p>嵌套正文。</p></section>")
                append("</section>")
            }
            append("</body>")
            append("<binary id=\"cover.jpg\" content-type=\"image/jpeg\">$b64</binary>")
            append("</FictionBook>")
        }
        return File(work, "sample.fb2").apply { writeText(xml, Charsets.UTF_8) }
    }

    private fun makeEpub(): File {
        val file = File(work, "sample.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
            )
            val items = (1..30).joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" } +
                """<item id="pic" href="pic.png" media-type="image/png"/>"""
            val spine = (1..30).joinToString("") { """<itemref idref="c$it"/>""" }
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="i">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>EPUB 对照样例</dc:title><dc:creator>tester</dc:creator></metadata>
                <manifest>$items</manifest><spine>$spine</spine></package>"""
            )
            for (c in 1..30) {
                val paras = (1..80).joinToString("") {
                    "<p>第${c}章第${it}段正文，用来把 EPUB 的文本量做到与现代小说一章相当，包含<img src=\"pic.png\" alt=\"插图\"/>与<em>强调</em>。</p>"
                }
                entry(
                    "OEBPS/c$c.xhtml",
                    """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>第${c}章</title></head>
                    <body><h1>第${c}章</h1><h2>小节标题</h2>$paras<blockquote>引用块</blockquote>
                    <table><tr><td>甲</td><td>乙</td></tr></table>
                    <p><img src="pic.png" alt="图"/></p><p><a href="note.xhtml#n1">脚注1</a></p></body></html>"""
                )
            }
            entry("OEBPS/pic.png", "")
        }
        return file
    }

    private fun makePdf(pages: Int = 300): File {
        val file = File(work, "sample.pdf")
        PDDocument().use { document ->
            document.documentInformation.title = "PDF 排版样例"
            document.documentInformation.author = "tester"
            val refs = (0 until pages).map { pageIndex ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 9f)
                    stream.newLineAtOffset(72f, 800f)
                    stream.showText("THE LINGUAREADER BOOK TITLE")
                    stream.newLineAtOffset(0f, -60f)
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    if (pageIndex % 30 == 0) {
                        stream.showText("Chapter ${pageIndex / 30 + 1}")
                        stream.newLineAtOffset(0f, -20f)
                    }
                    repeat(24) { line ->
                        val body = if (line == 7) {
                            "This line ends with a hyphenated word exam-"
                        } else {
                            "Page $pageIndex line $line body text for layout audit, long enough to look real."
                        }
                        stream.showText(body)
                        stream.newLineAtOffset(0f, -14f)
                    }
                    stream.newLineAtOffset(0f, -20f)
                    stream.setFont(PDType1Font.HELVETICA, 9f)
                    stream.showText("${pageIndex + 1}")
                    stream.endText()
                }
                page
            }
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            (0 until pages step 30).forEachIndexed { i, pageIndex ->
                val item = PDOutlineItem()
                item.title = "Chapter ${i + 1}"
                item.setDestination(refs[pageIndex])
                outline.addLast(item)
            }
            document.save(file)
        }
        return file
    }

    // ---- 测量 ----

    private fun best(runs: Int = 3, body: () -> Int): Pair<Long, Int> {
        var last = 0
        var bestMs = Long.MAX_VALUE
        repeat(runs) {
            val t0 = System.nanoTime(); last = body(); val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms < bestMs) bestMs = ms
        }
        return bestMs to last
    }

    private fun dest(content: File): File = File(booksDir, SharedImportSupport.sha256(content).take(20))

    private fun countAll(dir: File, regex: Regex): Int =
        dir.walkTopDown().filter { it.isFile && it.extension == "xhtml" }.sumOf { regex.findAll(it.readText()).count() }

    private fun rawSample(dir: File, needle: String, window: Int = 90): String {
        for (f in dir.walkTopDown().filter { it.isFile && it.extension == "xhtml" }) {
            val text = f.readText()
            val i = text.indexOf(needle)
            if (i >= 0) return text.substring(i, minOf(text.length, i + window)).replace(Regex("\\s+"), " ")
        }
        return "<未找到 $needle>"
    }

    @Test
    fun auditNonEpubImportTimeAndLayout() {
        val txt = makeTxt("UTF-8", crlf = false)
        val (txtMs, txtChapters) = best { com.linguareader.shared.importer.TextImporter(booksDir).import(txt, "TXT样例").chapters.size }
        val txtDir = dest(txt)
        println("[audit] TXT(UTF-8,${txt.length()}B) 最快=${txtMs}ms 章节=$txtChapters")
        println("[audit]   U+3000全角缩进保留数=${countAll(txtDir, Regex("\u3000"))}  <p>数=${countAll(txtDir, Regex("<p[ >]"))}")
        println("[audit]   段内硬换行处理: ${rawSample(txtDir, "硬换行")}")
        println("[audit]   场景分隔符 * * * 保留数=${countAll(txtDir, Regex("\\* \\* \\*"))}")
        println("[audit]   章节标题标签数=${countAll(txtDir, Regex("<h[1-6][ >]"))}(0=标题只进 <title>，正文无标题)")

        val gbk = makeTxt("GBK", crlf = false)
        val (gbkMs, gbkCh) = best { com.linguareader.shared.importer.TextImporter(booksDir).import(gbk, "GBK样例").chapters.size }
        val gbkDir = dest(gbk)
        println("[audit] TXT(GBK,${gbk.length()}B) 最快=${gbkMs}ms 章节=$gbkCh 中文是否完好=${gbkDir.walkTopDown().any { it.extension == "xhtml" && it.readText().contains("测试章节标题") }}")

        val crlf = makeTxt("UTF-8", crlf = true)
        val (crlfMs, crlfCh) = best { com.linguareader.shared.importer.TextImporter(booksDir).import(crlf, "CRLF样例").chapters.size }
        val crlfDir = dest(crlf)
        println("[audit] TXT(UTF-8+CRLF,${crlf.length()}B) 最快=${crlfMs}ms 章节=$crlfCh 行尾\\r残留=${countAll(crlfDir, Regex("\\u000D"))}")

        val fb2 = makeFb2()
        val (fb2Ms, fb2Ch) = best { com.linguareader.shared.importer.Fb2Importer(booksDir).import(fb2, "FB2样例").chapters.size }
        val fb2Dir = dest(fb2)
        println("[audit] FB2(${fb2.length()}B) 最快=${fb2Ms}ms 章节=$fb2Ch")
        println("[audit]   <h*>数=${countAll(fb2Dir, Regex("<h[1-6][ >]"))} <p>数=${countAll(fb2Dir, Regex("<p[ >]"))} <br>=${countAll(fb2Dir, Regex("<br\\s*/?>"))}")
        println("[audit]   <em>/<strong>/<table>/<img> 数=${countAll(fb2Dir, Regex("<em[ >]"))}/${countAll(fb2Dir, Regex("<strong[ >]"))}/${countAll(fb2Dir, Regex("<table[ >]"))}/${countAll(fb2Dir, Regex("<img[ >]"))}")
        println("[audit]   嵌套小节标题: ${rawSample(fb2Dir, "1.1 嵌套小节")}")
        println("[audit]   诗歌行: ${rawSample(fb2Dir, "诗行一")}")
        println("[audit]   表格内容: ${rawSample(fb2Dir, "表格单元甲")}")
        println("[audit]   强调文本: ${rawSample(fb2Dir, "强调")}")
        println("[audit]   封面文件=${File(fb2Dir, "cover.jpg").length()}B")

        val epub = makeEpub()
        val (epubMs, epubCh) = best { com.linguareader.shared.importer.EpubImporter(booksDir).import(epub).chapters.size }
        val epubDir = dest(epub)
        println("[audit] EPUB(${epub.length()}B) 最快=${epubMs}ms 章节=$epubCh")
        println("[audit]   <h*>数=${countAll(epubDir, Regex("<h[1-6][ >]"))} <p>数=${countAll(epubDir, Regex("<p[ >]"))} <img>=${countAll(epubDir, Regex("<img[ >]"))} <em>=${countAll(epubDir, Regex("<em[ >]"))} <table>=${countAll(epubDir, Regex("<table[ >]"))} <blockquote>=${countAll(epubDir, Regex("<blockquote[ >]"))}")

        val pdf = makePdf(300)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        var parsedChapters = 0
        val (pdfMs, _) = best {
            val p = extractPdf(pdf, scratchDir = context.cacheDir)
            parsedChapters = p.chapters.size
            p.chapters.size
        }
        var extractOnly = Long.MAX_VALUE
        repeat(3) {
            val t0 = System.nanoTime(); extractPdf(pdf, scratchDir = context.cacheDir)
            val ms = (System.nanoTime() - t0) / 1_000_000; if (ms < extractOnly) extractOnly = ms
        }
        val pdfDir = dest(pdf)
        pdfDir.deleteRecursively(); pdfDir.mkdirs()
        val parsed = extractPdf(pdf, scratchDir = context.cacheDir)
        parsed.chapters.forEachIndexed { i, ch ->
            File(pdfDir, "chapter_%03d.xhtml".format(i + 1)).writeText(textToXhtml(ch.title, ch.body), Charsets.UTF_8)
        }
        println("[audit] PDF(${pdf.length()}B/300页/11书签) 抽取+写文件最快=${pdfMs}ms 纯抽取最快=${extractOnly}ms 章节=$parsedChapters")
        println("[audit]   <p>总数=${countAll(pdfDir, Regex("<p[ >]"))} 每章<p>=${countAll(pdfDir, Regex("<p[ >]")) / maxOf(1, parsedChapters)} 页数=300(页=段落的证据)")
        println("[audit]   <h*>数=${countAll(pdfDir, Regex("<h[1-6][ >]"))}(书签小节标题)")
        println("[audit]   页面文本: ${rawSample(pdfDir, "Page 0 line 0")}")
        println("[audit]   页眉污染: 重复页眉出现次数=${countAll(pdfDir, Regex("THE LINGUAREADER BOOK TITLE"))}(页数300；应为0)")
        println("[audit]   断词连字处理: ${rawSample(pdfDir, "exam-", 70)}")
        val pdfUri = File(work, "sample-uri.pdf").apply { writeBytes(pdf.readBytes()) }
        val t0 = System.nanoTime()
        val viaUri = runCatching { PdfImporter(context, booksDir).import(Uri.fromFile(pdfUri)) }
        println("[audit] PDF 走 PdfImporter.import(Uri) 完整路径: ${viaUri.fold({ "成功 章节=${it.chapters.size}" }, { "失败 ${it::class.simpleName}: ${it.message}" })} 耗时=${(System.nanoTime() - t0) / 1_000_000}ms")

        val big = File(work, "copy-probe.bin").apply { writeBytes(ByteArray(20 * 1024 * 1024) { 7 }) }
        val copies = (1..3).map {
            val s = System.nanoTime(); val f = ImportSupport.copySource(context, Uri.fromFile(big))
            val d = (System.nanoTime() - s) / 1_000_000; f.delete(); d
        }
        println("[audit] SAF copySource 20MB 三次=${copies}ms")
    }
}
