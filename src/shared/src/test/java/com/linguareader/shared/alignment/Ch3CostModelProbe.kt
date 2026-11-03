package com.linguareader.shared.alignment

import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.ln

/**
 * 代价模型的敏感性分析。
 *
 * ## 起点（Lead 2026-10-31 裁定）
 *
 * 无答案与告警错的损失**至少相同**，且无答案可能更差——因为告警错至少有对的
 * 可能。实测支撑：UNCERTAIN 的 318 条候选里 298 条正确（**93.7%**），
 * 所以「告警答案」绝大多数情况下是对的。
 *
 * 因此代价系数应满足 `c_noAnswer >= c_warnedWrong`，而不是我先前设的 0.3 vs 0.7。
 *
 * ## 本探针要回答的三件事
 *
 * 1. 在 `c_noAnswer >= c_warnedWrong` 下，三种产品策略怎么排？
 * 2. 段落兜底是否真的「更差」？——注意它**不是错**，是**不精确**：
 *    展示的段落里一定包含正确那句译文，只是用户要自己找。必须单列一类。
 * 3. 排序对系数有多敏感？找出翻转点。
 */
class Ch3CostModelProbe {

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

    /** 一个策略下的加权分布（全部对 wAll 归一）。 */
    private class Dist {
        var none = 0.0          // 什么都没给
        var correctQuiet = 0.0  // 给对且无警告（ALIGNED）
        var correctWarned = 0.0 // 给对但有警告（UNCERTAIN 正确）
        var imprecise = 0.0     // 给了但只是段落级（含正确句在内）
        var wrongWarned = 0.0   // 给错且有警告
        var wrongSilent = 0.0   // 给错且无警告
        var all = 0.0
        fun pct(v: Double) = if (all <= 0) 0.0 else v * 100.0 / all
    }

    @Test
    fun measure() {
        var repo: File? = File(System.getProperty("user.dir"))
        while (repo != null && !File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").isFile) {
            repo = repo.parentFile
        }
        if (repo == null) { println("[cost] SKIP"); return }

        val json = JSONObject(File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")
        val enParas = ArrayList<String>()
        val zhParas = ArrayList<String>()
        data class P(val en: String, val zh: String, val para: Int)
        val truth = ArrayList<P>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            enParas += (0 until en.length()).joinToString(" ") { en.getString(it) }
            zhParas += (0 until zh.length()).joinToString("") { zh.getString(it) }
            for (k in 0 until en.length()) truth += P(en.getString(k), zh.getString(k), i)
        }

        val provider = EcdictProvider.load(repo)
        val doc = AiTranslationAligner().align(
            listOf(AlignmentChapter("c", enParas)),
            listOf(AlignmentChapter("c", zhParas)),
            provider
        )
        val truthSet = HashMap<String, MutableSet<String>>()
        for (t in truth) truthSet.getOrPut(norm(t.en)) { HashSet() }.add(norm(t.zh))
        val unitOf = HashMap<String, AlignedUnit>()
        for (u in doc.units) unitOf[norm(u.sourceText)] = u

        val df = HashMap<String, Int>()
        for (t in truth) contentWords(t.en).distinct().forEach { w -> df[w] = (df[w] ?: 0) + 1 }
        val nDoc = truth.size.toDouble()
        fun idf(w: String) = ln((nDoc + 1.0) / ((df[w] ?: 1) + 1.0)).coerceAtLeast(0.05)

        fun eval(useUncertain: Boolean, paraFallback: Boolean): Dist {
            val d = Dist()
            for (t in truth) {
                val u = unitOf[norm(t.en)]
                val status = u?.status
                val shown = norm(u?.targetText ?: "")
                val isCorrect = shown.isNotEmpty() && truthSet[norm(t.en)]?.contains(shown) == true
                // 段落兜底是否包含正确句（按构造必然包含，但用实际字符串校验）
                val paraZh = if (t.para in zhParas.indices) zhParas[t.para] else ""
                val paraContainsTruth = norm(paraZh).contains(norm(t.zh))

                var bucket: String
                if (!useUncertain && status == AlignmentStatus.UNCERTAIN) {
                    bucket = if (paraFallback) "imprecise" else "none"
                } else if (shown.isEmpty()) {
                    bucket = if (paraFallback) "imprecise" else "none"
                } else {
                    bucket = when {
                        isCorrect && status == AlignmentStatus.ALIGNED -> "correctQuiet"
                        isCorrect -> "correctWarned"
                        status == AlignmentStatus.ALIGNED -> "wrongSilent"
                        else -> "wrongWarned"
                    }
                }
                // 段落兜底时，额外确认「段落里确实有正确那句」
                if (bucket == "imprecise" && !paraContainsTruth) bucket = "none"

                for (w in contentWords(t.en)) {
                    val wt = idf(w)
                    d.all += wt
                    when (bucket) {
                        "none" -> d.none += wt
                        "correctQuiet" -> d.correctQuiet += wt
                        "correctWarned" -> d.correctWarned += wt
                        "imprecise" -> d.imprecise += wt
                        "wrongWarned" -> d.wrongWarned += wt
                        "wrongSilent" -> d.wrongSilent += wt
                    }
                }
            }
            return d
        }

        val strategies = listOf(
            Triple("A 严格：UNCERTAIN/UNALIGNED 都不给", false, false),
            Triple("B 现状：UNCERTAIN 给译文+标注", true, false),
            Triple("C 段落兜底：UNALIGNED 给整段", true, true)
        )
        val dists = strategies.map { (label, unc, fb) -> label to eval(unc, fb) }

        println("=== 各策略的加权分布（IDF 加权，4500+ 次点击）===")
        println("%-38s %8s %8s %8s %8s %8s %8s".format(
            "策略", "无答案", "对(静)", "对(告警)", "不精确", "错(告警)", "错(静默)"))
        println("-".repeat(104))
        for ((label, d) in dists) {
            println("%-38s %7.2f%% %7.2f%% %8.2f%% %7.2f%% %8.2f%% %8.2f%%".format(
                label, d.pct(d.none), d.pct(d.correctQuiet), d.pct(d.correctWarned),
                d.pct(d.imprecise), d.pct(d.wrongWarned), d.pct(d.wrongSilent)))
        }

        // ── 代价模型
        // 按 Lead 裁定：c_none >= c_warnWrong。
        // c_silentWrong 必须最重（学错且无提示）。
        // c_correctWarned：正确答案带多余警告，轻微损失（用户会怀疑但能确认）。
        // c_imprecise：给了相关上下文但要自己找。
        data class Costs(
            val none: Double, val correctWarned: Double, val imprecise: Double,
            val wrongWarned: Double, val wrongSilent: Double, val label: String
        )
        val models = listOf(
            Costs(1.0, 0.1, 0.5, 0.7, 2.0, "M1 c_none=1.0 c_warn=0.7（Lead 修正）"),
            Costs(1.0, 0.1, 0.5, 1.0, 2.0, "M2 c_none=c_warn=1.0（完全同价）"),
            Costs(1.2, 0.1, 0.5, 1.0, 2.0, "M3 c_none=1.2 > c_warn=1.0（无答案更差）"),
            Costs(0.3, 0.1, 0.5, 0.7, 2.0, "M0 旧模型 c_none=0.3（已废弃，仅对照）")
        )

        fun score(d: Dist, c: Costs): Double {
            val cost = (d.none * c.none + d.correctWarned * c.correctWarned +
                d.imprecise * c.imprecise + d.wrongWarned * c.wrongWarned +
                d.wrongSilent * c.wrongSilent) / d.all
            return (1.0 - cost) * 100.0
        }

        println()
        println("=== 用户体验分（= 100 × (1 − 期望代价)）===")
        println("%-40s %10s %10s %10s   %s".format("代价模型", "A", "B", "C", "最优"))
        println("-".repeat(92))
        for (c in models) {
            val sa = score(dists[0].second, c)
            val sb = score(dists[1].second, c)
            val sc = score(dists[2].second, c)
            val best = listOf("A" to sa, "B" to sb, "C" to sc).maxBy { it.second }
            println("%-40s %9.2f %9.2f %9.2f   %s".format(c.label, sa, sb, sc, best.first))
        }

        // ── 翻转点分析：固定 c_none=1.0, c_warn 扫
        println()
        println("=== 翻转点：c_none=1.0 固定，c_warnedWrong 从 0.4 扫到 1.3 ===")
        println("%8s %10s %10s %10s   %s".format("c_warn", "A", "B", "C", "最优"))
        var v = 0.4
        while (v <= 1.301) {
            val c = Costs(1.0, 0.1, 0.5, v, 2.0, "")
            val sa = score(dists[0].second, c)
            val sb = score(dists[1].second, c)
            val sc = score(dists[2].second, c)
            val best = listOf("A" to sa, "B" to sb, "C" to sc).maxBy { it.second }
            println("%8.2f %9.2f %9.2f %9.2f   %s".format(v, sa, sb, sc, best.first))
            v += 0.1
        }

        println()
        println("=== 读法 ===")
        println("  · 若 c_none 与 c_warn 同价，B 与 C 的差异只取决于「不精确 vs 无答案」的取舍。")
        println("  · C（段落兜底）把「无答案」全转成「不精确」，代价系数是 c_imprecise。")
        println("    它的优劣完全由 c_imprecise 决定——而这恰恰是最需要产品拍板的一项：")
        println("    用户看到一整段中文、要自己找那一句，这个体验值多少代价？")
    }
}
