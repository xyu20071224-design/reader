package com.linguareader.app.translation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.tts.TtsTextExtractor
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import com.linguareader.shared.translation.TranslationAligner
import com.linguareader.shared.translation.TranslationMatchLevel
import com.linguareader.shared.translation.TranslationMemory
import com.linguareader.shared.translation.TranslationMemoryIndex
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * **人译本（朱學恆《魔戒》）端到端代价评估**：V8 口径 vs V9 口径。
 *
 * ## 为什么必须单独做这一份
 *
 * V9 的两处修正（`；` 不再是中文句界、弯引号进闭合引号集）是依据 **AI 译本**选定并
 * 验证的（静默错 13.08%/16.34% → 6.94%/5.87%）。但生产里还有**人译本**这条线，
 * 而金标准回归（`TranslationGoldenReplayTest`）用的正是人译本——V9 让其中 13 条
 * 「已批准正确」的展示发生变化，s9/s20 明确变差（精确句 → 整段）。
 *
 * 本探针用**与用户完全相同的查询链路**（对齐 → 落盘模型 → `TranslationMemoryIndex.lookup`）
 * 在整本人译本上比较两种口径，给出可判定的代价差。
 *
 * ## 口径说明（没有真值，用代理指标）
 *
 * - **句级命中率**：在每个英文段落的首句上模拟点词，落在 SENTENCE 级别的比例。
 *   这是「用户点词能否拿到本句译文」的直接代理（段级兜底 = 用户看到整段）。
 * - **平均展示长度**：段级兜底时展示整段，句级展示单句。展示长度越短越精确。
 * - **短英文句陷阱**：短句（≤6 词）若返回的长译文超过自身长度 3 倍，说明拿回了
 *   邻句/整段译文 —— 抽样人工可读的「疑似静默错」代理。
 */
@RunWith(RobolectricTestRunner::class)
class HumanTranslationPolicyProbe {

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

    private class Result(
        val label: String,
        val sentenceHits: Int,
        val paragraphHits: Int,
        val misses: Int,
        val avgZhLen: Double,
        val phoneHits: Int,
        val total: Int,
        val elapsedMs: Long,
        val pairCount: Int
    )

    @Test
    fun measure() {
        val root = findRoot()
        assumeTrue("缺少 lotr 语料（本地产物，gitignore）", root != null)
        val artifacts = File(root!!, "artifacts")
        val app = ApplicationProvider.getApplicationContext<Application>()

        val extractor = TtsTextExtractor()
        val enBook = readEnBook(File(artifacts, "lotr-book"))
        val zhBook = readZhBook(File(artifacts, "lotr-zh"))
        val enChapters = enBook.chapters.indices.map { extractor.chapter(enBook, it).blocks }
        val zhChapters = zhBook.chapters.indices.map { extractor.chapter(zhBook, it).blocks }
        println("[human] 英 ${enChapters.size} 章 / ${enChapters.sumOf { it.size }} 段，" +
            "中 ${zhChapters.size} 章 / ${zhChapters.sumOf { it.size }} 段")

        val policies = listOf(
            "V8 含；句界" to TranslationAligner.ZhSplitPolicy.fixed(true),
            "V9 不含；" to TranslationAligner.ZhSplitPolicy.fixed(false)
        )

        val results = ArrayList<Result>()
        for ((label, policy) in policies) {
            val started = System.currentTimeMillis()
            val pairs = TranslationAligner.align(
                enChapters, zhChapters, EcdictMeaningIndex(app),
                sentencePositionPrior = 0.5,
                paragraphPositionPrior = 0.5,
                zhSplit = policy
            )
            val elapsed = System.currentTimeMillis() - started

            val index = TranslationMemoryIndex(
                TranslationMemory(
                    sourceBookId = enBook.id,
                    sourceTitle = enBook.title,
                    translationBookId = zhBook.id,
                    translationTitle = zhBook.title,
                    alignedAt = 0L,
                    pairs = pairs,
                    alignerVersion = TranslationAligner.VERSION
                )
            )

            // 每个英文段落的首句 + 中间句 + 末句 三个探针位置（覆盖容易漂移的两端）
            var sentenceHits = 0
            var paragraphHits = 0
            var misses = 0
            var total = 0
            var zhLenSum = 0.0
            var zhLenCount = 0
            // 「短英文句返回超长译文」= 疑似拿回邻句/整段的代理指标
            var phoneHits = 0

            val chapters = minOf(enChapters.size, zhChapters.size)
            for (c in 0 until chapters) {
                val enParas = enChapters[c]
                for (p in enParas.indices) {
                    val para = enParas[p]
                    if (para.isBlank()) continue
                    val sentences = com.linguareader.shared.tts.SentenceSplitter.split(para)
                        .filter { it.any { ch -> ch.isLetterOrDigit() } }
                    if (sentences.isEmpty()) continue
                    val picks = linkedSetOf<Int>()
                    picks += 0
                    if (sentences.size > 2) picks += sentences.size / 2
                    if (sentences.size > 1) picks += sentences.lastIndex
                    for (k in picks) {
                        val en = sentences[k]
                        total++
                        val r = index.lookup(c, en, para)
                        when (r?.matchLevel) {
                            TranslationMatchLevel.SENTENCE -> {
                                sentenceHits++
                                zhLenSum += r.chinese.length
                                zhLenCount++
                                // 短英文句 → 超长译文，说明粒度不对
                                if (en.count { it == ' ' } + 1 <= 6 &&
                                    r.chinese.length > en.length * 3 + 20
                                ) {
                                    phoneHits++
                                }
                            }
                            TranslationMatchLevel.PARAGRAPH -> paragraphHits++
                            null -> misses++
                            else -> paragraphHits++
                        }
                    }
                }
            }

            results += Result(
                label, sentenceHits, paragraphHits, misses,
                if (zhLenCount == 0) 0.0 else zhLenSum / zhLenCount,
                phoneHits, total, elapsed, pairs.size
            )
        }

        println()
        println(
            "  ${"口径".padEnd(14)} ${"句级命中".padEnd(16)} ${"段级兜底".padEnd(16)} " +
                "${"未命中".padEnd(12)} ${"均句译文长".padEnd(10)} ${"短句超长".padEnd(10)} 句对/耗时"
        )
        for (r in results) {
            fun pct(v: Int) = "%.2f%% (%d)".format(v * 100.0 / r.total, v)
            println(
                "  ${r.label.padEnd(14)} " +
                    "${pct(r.sentenceHits).padEnd(16)} " +
                    "${pct(r.paragraphHits).padEnd(16)} " +
                    "${pct(r.misses).padEnd(12)} " +
                    "${"%.1f".format(r.avgZhLen).padEnd(10)} " +
                    "${pct(r.phoneHits).padEnd(10)} " +
                    "${r.pairCount} / ${r.elapsedMs}ms"
            )
        }

        println()
        println("  探针点总数 = ${results.firstOrNull()?.total}（每段取首/中/末句）")
        println("  判读：句级命中率越高、段级兜底越低 = 用户点词越常拿到本句译文；")
        println("  「短句超长」越低越好（代理静默错：短英文句却返回大段译文）。")
    }
}
