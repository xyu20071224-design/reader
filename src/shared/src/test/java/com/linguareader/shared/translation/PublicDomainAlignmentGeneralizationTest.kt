package com.linguareader.shared.translation

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * **公有领域泛化集**：用 KJV（英王钦定本）与和合本（ChiUn）做第二、第三评测集。
 *
 * 为什么是圣经：两版都是公有领域，且**逐节对齐是天然的免费真值**——同一节号在
 * 两侧一一对应，不需要人工判定就能算出「配对的英文节和中文节是不是同一节」。
 * 它和魔戒金标准集互补：魔戒是现代小说、人工判定句级质量；这里是古典经文、
 * 机械判定节级正确率，且文体（叙事 / 箴言 / 书信）差别很大，能暴露单书过拟合。
 *
 * 素材（gitignored，本地）：
 * ```
 * artifacts/generalization/KJV.json    # https://raw.githubusercontent.com/scrollmapper/bible_databases/master/formats/json/KJV.json
 * artifacts/generalization/ChiUn.json  # …/formats/json/ChiUn.json（和合本，繁体）
 * ```
 * 一键下载见 `src/tools/alignment-eval/fetch-generalization-corpus.sh`。
 * **素材缺失时自动跳过**（CI 上没有这批下载物，所以这条是本地护栏）。
 *
 * 对齐用 `meaning = null`（`:shared` 没有 ECDICT）；量的是结构/长度锚点路径在
 * 完全不同文体上的表现，指标口径见下方 [Metrics]。
 */
class PublicDomainAlignmentGeneralizationTest {

    private val books = listOf("John", "Genesis", "Proverbs")

    /**
     * 各书的节级指标下限（**V6 自适应长度归一化**后实测值留约 5 个点余量）。
     *
     * 2026-09-08 V5（全局 1.7）实测只有 John 0.649 / Genesis 0.599 / Proverbs 0.465——
     * 和合本用词远比现代译本紧凑，单一常量把 DP 拽向更长的中文节，整节漂移（Δ±1 为主）。
     * V6 改成两级自适应密度（段级=章字/词、句级=平均句长比）后升到 0.86 / 0.85 / 0.77，
     * 覆盖率 0.78 / 0.74 / 0.73。下限按 V6 值设，**掉回 V5 水平即红**。
     */
    private val minimumExact = mapOf("John" to 0.81, "Genesis" to 0.79, "Proverbs" to 0.72)
    private val minimumWithin1 = mapOf("John" to 0.91, "Genesis" to 0.91, "Proverbs" to 0.89)
    private val minimumCoverage = mapOf("John" to 0.73, "Genesis" to 0.69, "Proverbs" to 0.68)

    @Test
    fun alignsPublicDomainParallelBooks() {
        val dir = findCorpusDir()
        assumeTrue(
            "缺少公版中英对照语料（artifacts/generalization），跳过；见 fetch-generalization-corpus.sh",
            dir != null
        )
        val kjv = loadBook(File(dir, "KJV.json"), books)
        val cuv = loadBook(File(dir, "ChiUn.json"), books)
        assertTrue("KJV.json 里没找到 ${books}，文件可能损坏", kjv.keys.containsAll(books))
        assertTrue("ChiUn.json 里没找到 ${books}，文件可能损坏", cuv.keys.containsAll(books))

        for (name in books) {
            val en = kjv.getValue(name)
            val zh = cuv.getValue(name)
            val metrics = evaluate(name, en, zh)
            metrics.report()
            assertTrue(
                "$name 同节精度 ${"%.3f".format(metrics.precision)} < 下限 ${minimumExact[name]}",
                metrics.precision >= minimumExact.getValue(name)
            )
            assertTrue(
                "$name ±1 节精度 ${"%.3f".format(metrics.withinOneVerse)} < 下限 ${minimumWithin1[name]}",
                metrics.withinOneVerse >= minimumWithin1.getValue(name)
            )
            assertTrue(
                "$name 节级覆盖率 ${"%.3f".format(metrics.coverage)} < 下限 ${minimumCoverage[name]}",
                metrics.coverage >= minimumCoverage.getValue(name)
            )
        }
    }

    // ---- 数据 ----

    private class BibleBook(val name: String, val chapters: List<List<String>>)

    private fun loadBook(file: File, wanted: List<String>): Map<String, BibleBook> {
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("books")
        val result = LinkedHashMap<String, BibleBook>()
        for (i in 0 until array.length()) {
            val book = array.getJSONObject(i)
            val name = book.optString("name")
            if (name !in wanted) continue
            val chaptersJson = book.getJSONArray("chapters")
            val chapters = (0 until chaptersJson.length()).map { c ->
                val verses = chaptersJson.getJSONObject(c).getJSONArray("verses")
                (0 until verses.length()).map { v ->
                    verses.getJSONObject(v).getString("text").trim()
                }
            }
            result[name] = BibleBook(name, chapters)
        }
        return result
    }

    // ---- 评测 ----

    private class Metrics(
        val name: String,
        val chapters: Int,
        val verses: Int,
        val pairs: Int,
        val located: Int,
        val correct: Int,
        val coveredVerses: Int,
        val mergedOrUnlocated: Int,
        val elapsedMs: Long,
        val meanConfidence: Double,
        val deltas: Map<Int, Int>,
        val samples: List<String>
    ) {
        val precision: Double get() = if (located == 0) 0.0 else correct.toDouble() / located
        val coverage: Double get() = if (verses == 0) 0.0 else coveredVerses.toDouble() / verses

        /** 同节或相邻节（|Δ| ≤ 1）的占比：结构没崩的判据。 */
        val withinOneVerse: Double
            get() = if (located == 0) 0.0
            else (deltas.filterKeys { it in -1..1 }.values.sum()).toDouble() / located

        fun report() {
            println(
                (
                    "[generalization] %-8s 章=%d 节=%d 句对=%d 定位=%d 同节=%d precision=%.3f within±1=%.3f" +
                        " coverage=%.3f 合并/未定位=%d 耗时=%dms 平均置信度=%.2f"
                    ).format(
                    name, chapters, verses, pairs, located, correct,
                    precision, withinOneVerse, coverage, mergedOrUnlocated, elapsedMs, meanConfidence
                )
            )
            val top = deltas.entries.sortedByDescending { it.value }.take(5)
                .joinToString(" ") { (d, n) -> "Δ$d:$n" }
            println("[generalization] %-8s 节差分布(前5) %s".format(name, top))
            samples.forEach { println("[generalization] %-8s 错位样例 %s".format(name, it)) }
        }
    }

    /**
     * 真值口径：一个句对被判「正确」= 英文句所在的节号与中文句所在的节号相同。
     * 句对的两端都定位不到节（句级合并跨越了节边界）时不计入精度分母。
     */
    private fun evaluate(name: String, en: BibleBook, zh: BibleBook): Metrics {
        val enVerses = en.chapters.flatten()
        val zhVerses = zh.chapters.flatten()
        val enNorm = enVerses.map { normalize(it) }
        val zhNorm = zhVerses.map { normalize(it) }

        val started = System.currentTimeMillis()
        val pairs = TranslationAligner.align(en.chapters, zh.chapters)
        val elapsed = System.currentTimeMillis() - started

        var located = 0
        var correct = 0
        var skipped = 0
        val covered = mutableSetOf<Int>()
        val deltas = HashMap<Int, Int>()
        val samples = mutableListOf<String>()
        var enCursor = 0
        var zhCursor = 0
        for (pair in pairs) {
            if (pair.enSentence.isBlank()) continue
            val enIndex = locate(enNorm, pair.enSentence, enCursor)
            val zhIndex = locate(zhNorm, pair.zhSentence, zhCursor)
            if (enIndex == null || zhIndex == null) {
                skipped++
                continue
            }
            enCursor = enIndex
            zhCursor = zhIndex
            located++
            val delta = zhIndex - enIndex
            deltas[delta] = (deltas[delta] ?: 0) + 1
            if (enIndex == zhIndex) {
                correct++
                covered += enIndex
            } else if (samples.size < 5) {
                samples += "节差${delta}: EN[$enIndex]«${enVerses[enIndex].take(50)}» → ZH[$zhIndex]«${zhVerses[zhIndex].take(30)}»"
            }
        }
        val confidences = pairs.filter { it.enSentence.isNotBlank() }.map { it.confidence.toDouble() }
        return Metrics(
            name = name,
            chapters = en.chapters.size,
            verses = enVerses.size,
            pairs = pairs.size,
            located = located,
            correct = correct,
            coveredVerses = covered.size,
            mergedOrUnlocated = skipped,
            elapsedMs = elapsed,
            meanConfidence = if (confidences.isEmpty()) 0.0 else confidences.average(),
            deltas = deltas,
            samples = samples
        )
    }

    /** 在归一化后的节文本里定位句子的所属节；先沿游标前扫，再兜底全表。 */
    private fun locate(normalizedVerses: List<String>, sentence: String, cursor: Int): Int? {
        val target = normalize(sentence)
        if (target.isBlank()) return null
        for (i in cursor until normalizedVerses.size) {
            if (normalizedVerses[i].contains(target)) return i
        }
        for (i in 0 until cursor) {
            if (normalizedVerses[i].contains(target)) return i
        }
        return null
    }

    private fun normalize(text: String): String =
        Regex("\\s+").replace(text, " ").trim().lowercase(Locale.ROOT)

    private fun findCorpusDir(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "artifacts/generalization")
            if (File(candidate, "KJV.json").isFile && File(candidate, "ChiUn.json").isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
