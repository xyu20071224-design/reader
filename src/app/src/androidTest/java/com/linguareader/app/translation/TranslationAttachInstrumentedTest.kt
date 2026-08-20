package com.linguareader.app.translation

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.app.data.BookImporter
import com.linguareader.app.data.WordLookup
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * F-128 真机链路测试：导入中文译本 → 三级对齐 → 落盘 → 点词查对照 → 移除。
 *
 * 单测覆盖不到的部分都在这里：真实的 `BookImporter` 解压与清洗、Android 上的
 * Jsoup 叶级段落抽取（`TtsTextExtractor`）、真实文件 IO 与 JSON 落盘、以及
 * 词级对齐要用的只读 ECDICT SQLite。
 */
@RunWith(AndroidJUnit4::class)
class TranslationAttachInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val application = context.applicationContext as Application

    private lateinit var workDir: File
    private lateinit var repository: TranslationMemoryRepository

    @Before
    fun setUp() {
        workDir = File(context.cacheDir, "translation-test-${System.nanoTime()}").apply { mkdirs() }
        repository = TranslationMemoryRepository(application)
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun attachesAlignsPersistsAndLooksUpTheTranslation() = runBlocking {
        val englishEpub = File(workDir, "english.epub").also { writeEnglishEpub(it) }
        val chineseEpub = File(workDir, "chinese.epub").also { writeChineseEpub(it) }
        val sourceBook = BookImporter(context, File(workDir, "books"))
            .import(Uri.fromFile(englishEpub))
        assertEquals(2, sourceBook.chapters.size)

        // --- attach：导入译本 + 对齐 + 落盘 ---
        val attached = repository.attach(sourceBook, Uri.fromFile(chineseEpub))

        assertTrue("对齐结果不应为空", attached.memory.pairs.isNotEmpty())
        assertEquals(sourceBook.id, attached.memory.sourceBookId)
        assertEquals(attached.translationBook.id, attached.memory.translationBookId)
        assertTrue("terms v1 恒空", attached.memory.terms.isEmpty())

        // 译本落在 files/translations/ 下，且不进书架目录 files/books/
        val translationDir = File(application.filesDir, "translations/${attached.translationBook.id}")
        assertTrue("译本目录应存在: $translationDir", translationDir.isDirectory)
        assertFalse(
            "译本不得出现在书架目录",
            File(application.filesDir, "books/${attached.translationBook.id}").exists()
        )

        // 档案是 v2 格式：有段落表，句对只存下标
        val archive = File(application.filesDir, "translation-memory/${sourceBook.id}.json")
        assertTrue("对齐档案应落盘: $archive", archive.isFile)
        val json = JSONObject(archive.readText())
        assertEquals(TranslationMemory.FORMAT_VERSION, json.getInt("version"))
        assertTrue(json.getJSONArray("enParagraphs").length() > 0)
        val firstPair = json.getJSONArray("pairs").getJSONObject(0)
        assertTrue("句对应只存段落下标", firstPair.has("ep") && firstPair.has("zp"))
        assertFalse("句对不应内联段落全文", firstPair.has("enParagraph"))

        // --- lookup：点词查对照（锚点词级对齐） ---
        val bookWithTranslation = sourceBook.copy(
            translationBookId = attached.translationBook.id,
            translationTitle = attached.translationBook.title,
            translationAlignedAt = attached.memory.alignedAt
        )
        val sentence = "Frodo left the Shire in 1420."
        val paragraph = "Frodo left the Shire in 1420. He carried the ring to the sea."
        val anchorHit = repository.lookup(
            bookWithTranslation,
            chapterIndex = 0,
            lookup = WordLookup("1420", sentence, paragraph, sentence.indexOf("1420"), 0f, 0f)
        )

        assertNotNull("应命中译本对照", anchorHit)
        assertEquals(TranslationMatchLevel.SENTENCE, anchorHit!!.matchLevel)
        assertTrue("中文句应含 1420：${anchorHit.chinese}", anchorHit.chinese.contains("1420"))
        assertEquals(attached.translationBook.title, anchorHit.translationTitle)
        val alignment = anchorHit.wordAlignment
        assertNotNull("数字应走锚点词级对齐", alignment)
        assertEquals(WordAlignmentSource.ANCHOR, alignment!!.source)
        assertEquals("1420", alignment.word)
        assertEquals(
            "1420",
            anchorHit.chinese.substring(alignment.start, alignment.endExclusive)
        )

        // 普通词即使词级定位失败，也必须保住句级对照（宁可不高亮，不可错标）
        val plainHit = repository.lookup(
            bookWithTranslation,
            chapterIndex = 0,
            lookup = WordLookup("left", sentence, paragraph, sentence.indexOf("left"), 0f, 0f)
        )
        assertEquals(TranslationMatchLevel.SENTENCE, plainHit?.matchLevel)

        // 未配译本的章节/文本不应误报
        assertEquals(
            null,
            repository.lookup(
                bookWithTranslation,
                chapterIndex = 7,
                lookup = WordLookup("left", sentence, paragraph, 0, 0f, 0f)
            )
        )

        // --- remove：档案与译本一起清掉 ---
        repository.remove(bookWithTranslation)

        assertFalse("档案应被删除", archive.exists())
        assertFalse("译本目录应被删除", translationDir.exists())
        assertFalse(repository.hasMemory(sourceBook.id))
    }

    // --- 测试用 EPUB ---------------------------------------------------------

    private fun writeEnglishEpub(target: File) = writeEpub(
        target = target,
        title = "The Journey",
        author = "Test Author",
        chapters = listOf(
            "Chapter One" to
                "<p>Frodo left the Shire in 1420. He carried the ring to the sea.</p>",
            "Chapter Two" to
                "<p>The road went ever on. Rain fell for three days.</p>"
        )
    )

    private fun writeChineseEpub(target: File) = writeEpub(
        target = target,
        title = "旅程",
        author = "測試譯者",
        chapters = listOf(
            "第一章" to
                "<p>佛羅多在1420年離開了夏爾。他帶著魔戒前往大海。</p>",
            "第二章" to
                "<p>道路綿延不絕。大雨下了三天。</p>"
        )
    )

    private fun writeEpub(
        target: File,
        title: String,
        author: String,
        chapters: List<Pair<String, String>>
    ) {
        ZipOutputStream(target.outputStream()).use { zip ->
            fun add(path: String, contents: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(contents.trimIndent().toByteArray())
                zip.closeEntry()
            }

            add("mimetype", "application/epub+zip")
            add(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OPS/content.opf"
                      media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """
            )
            val manifest = chapters.indices.joinToString("\n") {
                """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>"""
            }
            val spine = chapters.indices.joinToString("\n") { """<itemref idref="c$it"/>""" }
            add(
                "OPS/content.opf",
                """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>$title</dc:title>
                    <dc:creator>$author</dc:creator>
                  </metadata>
                  <manifest>
                $manifest
                  </manifest>
                  <spine>
                $spine
                  </spine>
                </package>
                """
            )
            chapters.forEachIndexed { index, (chapterTitle, body) ->
                add(
                    "OPS/c$index.xhtml",
                    """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <head><title>$chapterTitle</title></head>
                      <body>$body</body>
                    </html>
                    """
                )
            }
        }
    }
}
