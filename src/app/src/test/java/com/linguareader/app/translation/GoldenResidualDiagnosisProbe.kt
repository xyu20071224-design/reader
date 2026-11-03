package com.linguareader.app.translation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.tts.TtsTextExtractor
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import com.linguareader.shared.tts.SentenceSplitter
import com.linguareader.shared.translation.TranslationAligner
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * **定位金标准残留回归的具体成因**（s20 / s86 / s75）。
 *
 * 打印「英文句 → 中文分句结果 → 对齐器实际落盘的句对」三份视图，
 * 让「为什么这句掉到段级 / 为什么配错」一眼可见。
 */
@RunWith(RobolectricTestRunner::class)
class GoldenResidualDiagnosisProbe {

    private fun findRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/lotr-book/metadata.json").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun readEnBook(dir: File): Book {
        val meta = JSONObject(File(dir, "metadata.json").readText())
        val chaptersJson = meta.getJSONArray("chapters")
        val chapters = (0 until chaptersJson.length()).map { i ->
            val c = chaptersJson.getJSONObject(i)
            Chapter(c.getString("title"), c.getString("relativePath"))
        }
        return Book(
            id = "lotr-en", title = "lotr-en", author = "",
            extractedDir = dir.absolutePath, coverRelativePath = null,
            chapters = chapters, addedAt = 0L
        )
    }

    private fun readZhBook(dir: File): Book {
        val container = File(dir, "META-INF/container.xml").readText()
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)!!.groupValues[1]
        val opf = Jsoup.parse(File(dir, opfPath).readText())
        val manifest = opf.select("manifest item").associate { it.attr("id") to it.attr("href") }
        val spine = opf.select("spine itemref").mapNotNull { manifest[it.attr("idref")] }
        val baseDir = opfPath.substringBeforeLast('/', "")
        val chapters = spine.map { href ->
            Chapter(href.substringAfterLast('/'), if (baseDir.isEmpty()) href else "$baseDir/$href")
        }
        return Book(
            id = "lotr-zh", title = "译本", author = "",
            extractedDir = dir.absolutePath, coverRelativePath = null,
            chapters = chapters, addedAt = 0L
        )
    }

    private val closingLeads = setOf(
        '」', '』', '”', '’', '"', '\'', '）', '】', '］', ')', ']'
    )

    private fun splitZh(text: String, withSemi: Boolean): List<String> {
        val re = if (withSemi) {
            Regex("(?<=[。！？；!?;])|(?<=\\n)")
        } else {
            Regex("(?<=[。！？!?])|(?<=\\n)")
        }
        val raw = text.split(re).map { it.trim() }.filter { it.isNotBlank() }
        val merged = mutableListOf<String>()
        for (seg in raw) {
            val punctOnly = seg.none { it.isLetterOrDigit() }
            if (merged.isNotEmpty() && (punctOnly || seg.firstOrNull() in closingLeads)) {
                merged[merged.lastIndex] = merged.last() + seg
            } else merged += seg
        }
        return merged.filter { it.isNotBlank() && it.any { c -> c.isLetterOrDigit() } }
    }

    @Test
    fun measure() {
        val root = findRoot()
        assumeTrue("缺少 lotr 语料", root != null)
        val artifacts = File(root!!, "artifacts")
        val app = ApplicationProvider.getApplicationContext<Application>()

        val extractor = TtsTextExtractor()
        val enBook = readEnBook(File(artifacts, "lotr-book"))
        val zhBook = readZhBook(File(artifacts, "lotr-zh"))
        val enChapters = enBook.chapters.indices.map { extractor.chapter(enBook, it).blocks }
        val zhChapters = zhBook.chapters.indices.map { extractor.chapter(zhBook, it).blocks }

        val pairs = TranslationAligner.align(
            enChapters, zhChapters, EcdictMeaningIndex(app),
            TranslationAligner.ZhSplitPolicy.forAiTranslation(isAiTranslation = false)
        )

        val cases = listOf(
            "s20" to ("Frodo opened his mouth and shut it again." to 16),
            "s86" to ("Don’t pinch, lad!" to 34),
            "s75" to ("They were washed and in the middle of good deep mugs of beer" to 20)
        )

        for ((id, spec) in cases) {
            val (enNeedle, chapterHint) = spec
            val target = enNeedle.lowercase()
            var found = false
            for (c in enChapters.indices) {
                val gi = enChapters[c].indexOfFirst { it.lowercase().contains(target) }
                if (gi < 0) continue
                found = true
                val enPara = enChapters[c][gi]
                println("=== $id  章 $c（样本提示章 $chapterHint） 段 $gi ===")
                val enSents = SentenceSplitter.split(enPara).filter { s -> s.any { it.isLetterOrDigit() } }
                println("  英文段: ${enPara.take(200)}")
                println("  英文句数=${enSents.size}")
                enSents.forEachIndexed { i, s -> println("    EN[$i] ${s.take(90)}") }

                // 找中文段：对齐器落盘的句对里，enParagraph 等于这段的中文对
                val related = pairs.filter { it.enParagraph == enPara }
                if (related.isNotEmpty()) {
                    val zhPara = related.first().zhParagraph
                    println("  中文段: ${zhPara.take(200)}")
                    for (withSemi in listOf(true, false)) {
                        val zs = splitZh(zhPara, withSemi)
                        println("  中文分句（${if (withSemi) "含；" else "不含；"}）句数=${zs.size}")
                        zs.forEachIndexed { i, s -> println("    ZH[$i] ${s.take(70)}") }
                    }
                    println("  落盘句对 ${related.size} 条：")
                    related.forEach {
                        println("    EN «${it.enSentence.take(45)}» → ZH «${it.zhSentence.take(45)}» conf=${"%.2f".format(it.confidence)}")
                    }
                } else {
                    println("  ** 该英文段没有落盘句对（走段级兜底） **")
                }
                println()
            }
            if (!found) println("=== $id 未找到样本英文（$enNeedle）===")
        }
    }
}
