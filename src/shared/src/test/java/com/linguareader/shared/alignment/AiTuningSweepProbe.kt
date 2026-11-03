package com.linguareader.shared.alignment

import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.ln

/**
 * **Pareto 扫描**：把「错判」从红线改成可计价的成本后，找出
 * 「覆盖率 ↔ 错判」的权衡曲线。
 *
 * ## 背景
 *
 * 之前把 `falseAligned == 0` 当硬红线，导致任何能降低错判的规则都要牺牲
 * 40–85% 覆盖率（见 SPLIT-007 的实测表）——**红线让优化变成"二选一"**。
 *
 * 改成可计价后，问题变成：
 * > 在给定「每 1% 错判可接受」的前提下，能换来多少覆盖率？
 *
 * ## 方法
 *
 * 网格扫描 4 个阈值 × 2 章语料，对每个组合算：
 * - `coverage`（ALIGNED 占全部英文句）
 * - `silentWrong`（ALIGNED 但译文错）
 * - `usable`（ALIGNED 正确 + UNCERTAIN 正确）
 * - `A-wrong` 的 IDF 加权占比
 *
 * 然后标出 **Pareto 前沿**（不存在「覆盖率更高且错判更低」的其它点）。
 */
class AiTuningSweepProbe {

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    private val stopwords = setOf(
        "a", "an", "the", "and", "or", "but", "if", "of", "to", "in", "on", "at", "by",
        "for", "with", "from", "as", "is", "are", "was", "were", "be", "been", "being",
        "am", "do", "does", "did", "have", "has", "had", "will", "would", "shall",
        "should", "may", "might", "must", "can", "could", "i", "you", "he", "she", "it",
        "we", "they", "me", "him", "her", "us", "them", "my", "your", "his", "its",
        "our", "their", "this", "that", "these", "those", "there", "here", "not", "no",
        "so", "up", "down", "out", "off", "over", "than", "then", "now", "just", "very",
        "too", "also", "only", "all", "any", "some", "more", "most", "such", "what",
        "which", "who", "whom", "whose", "when", "where", "why", "how", "said", "say",
        "says", "into", "onto", "upon", "about", "after", "before", "again", "still"
    )

    private fun contentWords(s: String): List<String> =
        Regex("[A-Za-z][A-Za-z'’-]*").findAll(s)
            .map { it.value.lowercase().trimEnd('\'', '’', '-') }
            .filter { it.length >= 2 && it !in stopwords }
            .toList()

    private class Corpus(
        val name: String,
        val enParas: List<String>,
        val zhParas: List<String>,
        val truth: List<Pair<String, String>>
    )

    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null &&
            !(File(dir, "artifacts/ai-ch1/ch1_truth.json").isFile &&
                File(dir, "artifacts/ai-ch3/ch3_merged_partial.json").isFile)
        ) dir = dir.parentFile
        return dir
    }

    private fun loadCorpus(repo: File, name: String, rel: String): Corpus {
        val json = JSONObject(File(repo, rel).readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")
        val en = ArrayList<String>()
        val zh = ArrayList<String>()
        val truth = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val e = o.getJSONArray("en")
            val z = o.getJSONArray("zh")
            val eb = StringBuilder()
            val zb = StringBuilder()
            for (k in 0 until e.length()) {
                if (eb.isNotEmpty()) eb.append(' ')
                eb.append(e.getString(k))
                zb.append(z.getString(k))
                truth += e.getString(k) to z.getString(k)
            }
            en.add(eb.toString())
            zh.add(zb.toString())
        }
        return Corpus(name, en, zh, truth)
    }

    private class Result(
        val label: String,
        val coverage: Double,
        val silentWrong: Int,
        val silentWrongWeighted: Double,
        val usable: Double,
        val aligned: Int,
        val alignedWrong: Int
    )

    private fun evaluate(repo: File, c: Corpus, tuning: AlignerTuning, label: String): Result {
        val provider = EcdictProvider.load(repo)
        val doc = AiTranslationAligner(tuning).align(
            listOf(AlignmentChapter("c", c.enParas)),
            listOf(AlignmentChapter("c", c.zhParas)),
            provider
        )
        val truthSet = HashMap<String, MutableSet<String>>()
        for (t in c.truth) truthSet.getOrPut(norm(t.first)) { HashSet() }.add(norm(t.second))
        val unitOf = HashMap<String, AlignedUnit>()
        for (u in doc.units) unitOf[norm(u.sourceText)] = u

        val df = HashMap<String, Int>()
        for (t in c.truth) contentWords(t.first).distinct().forEach { w -> df[w] = (df[w] ?: 0) + 1 }
        val nDoc = c.truth.size.toDouble()
        fun idf(w: String) = ln((nDoc + 1.0) / ((df[w] ?: 1) + 1.0)).coerceAtLeast(0.05)

        var aligned = 0
        var alignedWrong = 0
        var usableCorrect = 0
        var allWeight = 0.0
        var wrongWeight = 0.0
        for (t in c.truth) {
            val u = unitOf[norm(t.first)] ?: continue
            val got = norm(u.targetText)
            val correct = got.isNotEmpty() && truthSet[norm(t.first)]?.contains(got) == true
            var w = 0.0
            for (cw in contentWords(t.first)) w += idf(cw)
            allWeight += w
            when (u.status) {
                AlignmentStatus.ALIGNED -> {
                    aligned++
                    if (correct) usableCorrect++ else {
                        alignedWrong++
                        wrongWeight += w
                    }
                }
                AlignmentStatus.UNCERTAIN -> if (correct) usableCorrect++
                AlignmentStatus.UNALIGNED -> Unit
            }
        }
        val total = c.truth.size
        return Result(
            label = label,
            coverage = aligned * 100.0 / total,
            silentWrong = alignedWrong,
            silentWrongWeighted = if (allWeight <= 0) 0.0 else wrongWeight * 100 / allWeight,
            usable = usableCorrect * 100.0 / total,
            aligned = aligned,
            alignedWrong = alignedWrong
        )
    }

    @Test
    fun sweep() {
        val repo = repoRoot() ?: run { println("[sweep] SKIP: corpora missing"); return }
        val ch1 = loadCorpus(repo, "ch1", "artifacts/ai-ch1/ch1_truth.json")
        val ch3 = loadCorpus(repo, "ch3", "artifacts/ai-ch3/ch3_merged_partial.json")

        val grid = ArrayList<AlignerTuning>()
        for (zl in listOf(3.0, 5.0, 8.0, 1e9)) {
            for (lex in listOf(0.15, 0.10, 0.05, 0.0)) {
                for (reqW in listOf(true, false)) {
                    grid += AlignerTuning(zLimit = zl, lexicalWitnessMin = lex, requireWitness = reqW)
                }
            }
        }

        println("=== Pareto 扫描（2 章 / ${grid.size} 组阈值）===")
        println("%-40s %8s %8s %8s %8s".format("配置", "cov%", "错判数", "错判%", "可用%"))
        println("-".repeat(80))

        val results = ArrayList<Triple<String, Result, Result>>() // label, ch1, ch3
        for (t in grid) {
            val label = "z=${fmt(t.zLimit)} lex=${t.lexicalWitnessMin} witness=${if (t.requireWitness) "on" else "OFF"}"
            val r1 = evaluate(repo, ch1, t, label)
            val r3 = evaluate(repo, ch3, t, label)
            results += Triple(label, r1, r3)
            println(
                "%-40s %7.2f%% %8d %7.2f%% %7.2f%%".format(
                    label,
                    (r1.coverage + r3.coverage) / 2,
                    r1.silentWrong + r3.silentWrong,
                    (r1.silentWrongWeighted + r3.silentWrongWeighted) / 2,
                    (r1.usable + r3.usable) / 2
                )
            )
        }

        // ── Pareto 前沿：在 (coverage 高, 错判低) 平面上不被支配的点
        println()
        println("=== Pareto 前沿（两章平均；不被其它配置同时超越覆盖率与错判）===")
        data class P(val label: String, val cov: Double, val wrong: Int, val usable: Double)
        val pts = results.map {
            P(
                it.first,
                (it.second.coverage + it.third.coverage) / 2,
                it.second.silentWrong + it.third.silentWrong,
                (it.second.usable + it.third.usable) / 2
            )
        }
        val front = pts.filter { p ->
            pts.none { q -> q !== p && q.cov >= p.cov && q.wrong <= p.wrong && (q.cov > p.cov || q.wrong < p.wrong) }
        }.sortedByDescending { it.cov }
        println("%-44s %8s %8s %8s".format("配置", "cov%", "错判", "可用%"))
        front.forEach {
            println("%-44s %7.2f%% %8d %7.2f%%".format(it.label, it.cov, it.wrong, it.usable))
        }

        // ── 关键问题：错判的边际成本
        println()
        println("=== 关键权衡：多接受 1 条错判，能换多少覆盖率？ ===")
        val sorted = pts.sortedBy { it.wrong }
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            if (b.wrong > a.wrong && b.cov > a.cov) {
                val dc = b.cov - a.cov
                val dw = b.wrong - a.wrong
                println(
                    "  %-38s 错判 +%d（%.2f%%→%.2f%%）覆盖 +%.2f 点 → 每多1条错判换 %.2f 点覆盖".format(
                        b.label, dw, a.wrong * 100.0 / (ch1.truth.size + ch3.truth.size).coerceAtLeast(1),
                        b.wrong * 100.0 / (ch1.truth.size + ch3.truth.size).coerceAtLeast(1),
                        dc, dc / dw
                    )
                )
            }
        }
    }

    private fun fmt(v: Double) = if (v > 1e8) "inf" else v.toString()
}
