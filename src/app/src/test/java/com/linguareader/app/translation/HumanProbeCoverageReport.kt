package com.linguareader.app.translation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.tts.TtsTextExtractor
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 如实统计人译本探针的**实际覆盖范围**。
 *
 * `HumanTranslationPolicyProbe` 按 `min(英文章数, 中文章数)` 同下标遍历。本探针回答：
 *  - 英/中各自多少章、多少段
 *  - 同下标遍历真正覆盖了多少章、多少段、多少探针句
 *  - 被跳过的是哪些章（英文侧有内容但没测到）
 *
 * 目的不是改结论，而是让报告里的「人译本句级命中率 84.29%」带上正确的分母说明。
 */
@RunWith(RobolectricTestRunner::class)
class HumanProbeCoverageReport {

    private fun findRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/lotr-book/metadata.json").isFile &&
                File(dir, "artifacts/lotr-zh/META-INF/container.xml").isFile
            ) return dir
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
            id = "lotr-zh", title = "lotr-zh", author = "",
            extractedDir = dir.absolutePath, coverRelativePath = null,
            chapters = chapters, addedAt = 0L
        )
    }

    @Test
    fun report() {
        val root = findRoot()
        assumeTrue("缺少 lotr 语料", root != null)
        val artifacts = File(root!!, "artifacts")
        val extractor = TtsTextExtractor()
        val enBook = readEnBook(File(artifacts, "lotr-book"))
        val zhBook = readZhBook(File(artifacts, "lotr-zh"))
        val enChapters = enBook.chapters.indices.map { extractor.chapter(enBook, it).blocks }
        val zhChapters = zhBook.chapters.indices.map { extractor.chapter(zhBook, it).blocks }

        println("[cov] 英文: ${enChapters.size} 章 / ${enChapters.sumOf { it.size }} 段")
        println("[cov] 中文: ${zhChapters.size} 章 / ${zhChapters.sumOf { it.size }} 段")

        val covered = minOf(enChapters.size, zhChapters.size)
        var enParasCovered = 0
        var probes = 0
        for (c in 0 until covered) {
            val enParas = enChapters[c]
            enParasCovered += enParas.size
            for (p in enParas.indices) {
                val para = enParas[p]
                if (para.isBlank()) continue
                val sents = com.linguareader.shared.tts.SentenceSplitter.split(para)
                    .filter { s -> s.any { ch -> ch.isLetterOrDigit() } }
                if (sents.isEmpty()) continue
                if (sents.isNotEmpty()) probes++
                if (sents.size > 2) probes++
                if (sents.size > 1) probes++
            }
        }
        println("[cov] 同下标遍历覆盖: $covered 章 / $enParasCovered 段 / $probes 个探针句")
        println("[cov] 英文侧未覆盖: ${enChapters.size - covered} 章")
        for (c in covered until enChapters.size) {
            val title = enBook.chapters[c].title
            println("       跳过 ch$c «$title» ${enChapters[c].size} 段")
        }
    }
}
