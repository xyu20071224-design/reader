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
 * **人译本 vs AI 译本的 `；` 口径冲突量化**。
 *
 * ## 为什么需要这个探针
 *
 * V9 依据 **AI 译本**把中文 `；` 从句界里去掉了（中英分号地位对齐），AI 译本上
 * 静默错从 13.08%/16.34% 降到 6.94%/5.87%。但金标准回归（朱學恆**人译本**）随即
 * 有 13 条展示变化，其中 s9 明确变差：
 *   EN `That's a dark bad place, if half the tales be true.'`
 *   V8 批准 `SENTENCE/«如果傳說故事是真的，老林可是個受詛咒的不祥之地。」`（精确正确）
 *   V9 现在 `PARAGRAPH/整段`（退到段级）
 *
 * 假设：**人译者把 `；` 当句界用（用于分隔两个英文句），AI 译者把它留在英文句内部**。
 *
 * ## 本探针做什么
 *
 * 对同一本书，分别用两种口径切中文段，统计「中文句数 == 英文句数」的段落比例。
 * 这是后续按段自适应选口径的基础信号，且**不需要任何真值标注**（用英文句数当参照）。
 *
 * - `；boundary`（V8 口径）：中文 `；` 算句界
 * - `；no-boundary`（V9 口径）：中文 `；` 不算句界
 * - `adaptive`：逐段取两者中与英文句数更接近的一个
 */
@RunWith(RobolectricTestRunner::class)
class SemicolonPolicyProbe {

    private class Split(
        val noSemicolon: Regex = Regex("(?<=[。！？!?])|(?<=\\n)"),
        val withSemicolon: Regex = Regex("(?<=[。！？；!?;])|(?<=\\n)")
    )

    private val closingLeads = setOf(
        '」', '』', '”', '’', '"', '\'', '）', '】', '］', ')', ']'
    )

    /** 与生产 `TranslationAligner.splitChinese` 同构。 */
    private fun splitChinese(text: String, terminator: Regex): List<String> {
        val raw = text.split(terminator).map { it.trim() }.filter { it.isNotBlank() }
        val merged = mutableListOf<String>()
        for (seg in raw) {
            val punctuationOnly = seg.none { it.isLetterOrDigit() }
            val leadsWithClosing = seg.firstOrNull() in closingLeads
            if (merged.isNotEmpty() && (punctuationOnly || leadsWithClosing)) {
                merged[merged.lastIndex] = merged.last() + seg
            } else {
                merged += seg
            }
        }
        return merged.filter { it.isNotBlank() && it.any { c -> c.isLetterOrDigit() } }
    }

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
            id = meta.optString("id", "lotr-en"),
            title = meta.optString("title", "lotr-en"),
            author = meta.optString("author", ""),
            extractedDir = dir.absolutePath,
            coverRelativePath = null,
            chapters = chapters,
            addedAt = 0L
        )
    }

    private fun readZhBook(dir: File): Book {
        val container = File(dir, "META-INF/container.xml").readText()
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)
            ?: error("no rootfile")
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

    @Test
    fun measure() {
        val root = findRoot()
        assumeTrue("缺少 lotr 语料（本地产物，gitignore）", root != null)
        val artifacts = File(root!!, "artifacts")

        val extractor = TtsTextExtractor()
        val enBook = readEnBook(File(artifacts, "lotr-book"))
        val zhBook = readZhBook(File(artifacts, "lotr-zh"))
        val enChapters = enBook.chapters.indices.map { extractor.chapter(enBook, it).blocks }
        val zhChapters = zhBook.chapters.indices.map { extractor.chapter(zhBook, it).blocks }
        println("[semi] 英 ${enChapters.size} 章 / 中 ${zhChapters.size} 章")

        var withParity = 0
        var noParity = 0
        var adaptiveParity = 0
        var pairs = 0
        var withCloser = 0
        var noCloser = 0
        // 只统计「含 ；」的段，冲突主要出在这里
        var semiParas = 0
        var semiWithParity = 0
        var semiNoParity = 0
        // 统计：含 ； 的段里，两种口径句数是否真的不同
        var semiDiffers = 0

        val chapterCount = minOf(enChapters.size, zhChapters.size)
        for (c in 0 until chapterCount) {
            val en = enChapters[c]
            val zh = zhChapters[c]
            val n = minOf(en.size, zh.size)
            for (p in 0 until n) {
                val enText = en[p]
                val zhText = zh[p]
                if (enText.isBlank() || zhText.isBlank()) continue
                val enN = SentenceSplitterBridge.count(enText)
                val withSemi = splitChinese(zhText, Split().withSemicolon)
                val noSemi = splitChinese(zhText, Split().noSemicolon)
                pairs++
                val hasSemi = zhText.contains('；') || zhText.contains(';')
                if (hasSemi) semiParas++
                if (withSemi.size != noSemi.size) {
                    if (hasSemi) semiDiffers++
                }
                val withOk = withSemi.size == enN
                val noOk = noSemi.size == enN
                if (withOk) withParity++
                if (noOk) noParity++
                if (withOk || noOk) adaptiveParity++
                if (hasSemi) {
                    if (withOk) semiWithParity++
                    if (noOk) semiNoParity++
                }
                val withDist = kotlin.math.abs(withSemi.size - enN)
                val noDist = kotlin.math.abs(noSemi.size - enN)
                if (withDist < noDist) withCloser++ else if (noDist < withDist) noCloser++
            }
        }

        fun pct(a: Int, b: Int) = if (b == 0) 0.0 else a * 100.0 / b
        println("[semi] 可比段落=$pairs")
        println("[semi] --- 全书 ---")
        println("  含 ； 的段 = $semiParas (${"%.2f".format(pct(semiParas, pairs))}%)，" +
            "其中两种口径句数不同 = $semiDiffers")
        println("  ；算句界（V8）  : 句数相等 = $withParity (${"%.2f".format(pct(withParity, pairs))}%)")
        println("  ；不算（V9）    : 句数相等 = $noParity (${"%.2f".format(pct(noParity, pairs))}%)")
        println("  逐段自适应      : 句数相等 = $adaptiveParity (${"%.2f".format(pct(adaptiveParity, pairs))}%)")
        println("  距离更近者：算={} 不算={}".format(withCloser, noCloser))
        println("[semi] --- 只含 ； 的段 ---")
        println("  ；算句界（V8）  : 相等 = $semiWithParity (${"%.2f".format(pct(semiWithParity, semiParas))}%)")
        println("  ；不算（V9）    : 相等 = $semiNoParity (${"%.2f".format(pct(semiNoParity, semiParas))}%)")

        println()
        println("[semi] 判读：若人译本上「；算句界」的句数相等率明显更高 →")
        println("[semi]   人译者用 ； 分隔英文句（V8 对），AI 译者用 ； 连接同一句内部（V9 对）→")
        println("[semi]   **必须按段自适应**，不能全局二选一。")
    }
}

/** 只暴露 SentenceSplitter.count，避免探针直接依赖分句器内部。 */
private object SentenceSplitterBridge {
    fun count(text: String): Int = com.linguareader.shared.tts.SentenceSplitter.count(text)
}
