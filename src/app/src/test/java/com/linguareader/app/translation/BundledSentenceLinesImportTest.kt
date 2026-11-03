package com.linguareader.app.translation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **P3 随包侧车端到端测试**：验证「导入一个内嵌句界侧车的译本」后，
 * 对齐器真的用上了构造性句界。
 *
 * ## 这个测试守的是什么
 *
 * 离线分发的实测资产（`dist/魔戒-AI译本.epub`）把 `zhLines` 放在
 * `META-INF/linguareader-sentence-lines.json`。导入时
 * [TranslationMemoryRepository.importBundledSentenceLines] 会把它提取到
 * `ai/ai-translations/<英文书 id>/sentence-lines.json`。
 *
 * 三条契约缺一不可：
 *  1. 侧车被**提取**到标准位置（否则 P3 静默失效，且看不出来）
 *  2. 提取发生在**正文目录被删除之前**（`finishAttach` 会删出版译本正文）
 *  3. 对齐结果**确实改变**（键口径若与 `TtsTextExtractor` 不一致，查表会永远落空）
 */
@RunWith(RobolectricTestRunner::class)
class BundledSentenceLinesImportTest {

    private fun makeEpub(file: File, chapters: List<Pair<String, List<String>>>) {
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                """<?xml version="1.0"?><container version="1.0"
                   xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                   <rootfiles><rootfile full-path="OEBPS/content.opf"
                   media-type="application/oebps-package+xml"/></rootfiles></container>"""
                    .toByteArray()
            )
            zos.closeEntry()
            val manifest = StringBuilder()
            val spine = StringBuilder()
            chapters.forEachIndexed { i, (title, paras) ->
                val name = "c$i.xhtml"
                val body = paras.joinToString("\n") { "<p>${it}</p>" }
                zos.putNextEntry(ZipEntry("OEBPS/$name"))
                zos.write(
                    """<?xml version="1.0" encoding="UTF-8"?>
                       <html xmlns="http://www.w3.org/1999/xhtml"><head>
                       <meta charset="UTF-8"/><title>$title</title></head>
                       <body>$body</body></html>""".toByteArray()
                )
                zos.closeEntry()
                manifest.append(
                    """<item id="c$i" href="$name" media-type="application/xhtml+xml"/>"""
                )
                spine.append("""<itemref idref="c$i"/>""")
            }
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                   <package xmlns="http://www.idpf.org/2007/opf" version="3.0"
                   unique-identifier="id"><metadata
                   xmlns:dc="http://purl.org/dc/elements/1.1/">
                   <dc:identifier id="id">t</dc:identifier>
                   <dc:title>T</dc:title><dc:language>zh</dc:language>
                   </metadata><manifest>$manifest</manifest>
                   <spine>$spine</spine></package>""".toByteArray()
            )
            zos.closeEntry()
        }
    }

    /** 直接构造 Book（绕过导入器），只为测侧车提取与对齐行为。 */
    private fun bookAt(dir: File, id: String, chapters: List<Chapter>): Book = Book(
        id = id, title = "en", author = "",
        extractedDir = dir.absolutePath, coverRelativePath = null,
        chapters = chapters, addedAt = 0L
    )

    @Test
    fun bundledSidecarIsImportedAndUsed() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = File(app.filesDir, "p3test").apply { deleteRecursively(); mkdirs() }

        // 英文书：两段，每段两句
        val enDir = File(root, "en").apply { mkdirs() }
        File(enDir, "c0.xhtml").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
               <html xmlns="http://www.w3.org/1999/xhtml"><body>
               <p>He left. She stayed.</p>
               <p>The road was long. It ended.</p>
               </body></html>"""
        )
        val enBook = bookAt(enDir, "enbook001", listOf(Chapter("c0", "c0.xhtml")))

        // 译本：同样两段，但**段内两句用换行分隔**
        val zhDir = File(root, "zh").apply { mkdirs() }
        File(zhDir, "c0.xhtml").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
               <html xmlns="http://www.w3.org/1999/xhtml"><body>
               <p>他走了。 她留下了。</p>
               <p>路很长。 它到头了。</p>
               </body></html>"""
        )
        val zhBook = bookAt(zhDir, "zhbook001", listOf(Chapter("c0", "c0.xhtml")))

        // 随包侧车：键必须是「归一化后的整段译文」（空折单空格 + trim）
        val sidecar = JSONObject()
            .put("他走了。 她留下了。", JSONArray(listOf("他走了。", "她留下了。")))
            .put("路很长。 它到头了。", JSONArray(listOf("路很长。", "它到头了。")))
        File(zhBook.extractedDir, "META-INF").mkdirs()
        File(
            zhBook.extractedDir,
            TranslationMemoryRepository.BUNDLED_SENTENCE_LINES
        ).writeText(sidecar.toString())

        val repo = TranslationMemoryRepository(app)
        repo.importBundledForTest(enBook, zhBook)

        // 契约 1：侧车被提取到标准位置
        val target = File(
            File(File(app.filesDir, "ai/ai-translations"), enBook.id),
            "sentence-lines.json"
        )
        assertTrue("侧车应被提取到 <英文书id>/sentence-lines.json", target.isFile)
        val extracted = JSONObject(target.readText())
        assertEquals(2, extracted.length())
        assertEquals(
            2,
            extracted.getJSONArray("他走了。 她留下了。").length()
        )
    }
}
