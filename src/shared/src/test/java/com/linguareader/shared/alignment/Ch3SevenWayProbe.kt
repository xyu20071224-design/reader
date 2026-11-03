package com.linguareader.shared.alignment

import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.ln

/**
 * 七分类（重建版，2026-10-31）。
 *
 * ## 为什么重建
 *
 * 旧版 [Ch3ClassificationRefineProbe] 的 N-real 桶判定有 bug：它用「该段是否有
 * 句数差」+「真值是否非空」来分桶，而真值**永远非空**，于是所有不满足
 * 「句数不等」条件的 UNALIGNED 都被归进 N-real。实测还出现「UNALIGNED=0
 * 但 N-real=14.19%」这种自相矛盾的输出。
 *
 * ## 新判据：**只信引擎自己给出的 reasons / skipReason**
 *
 * 不猜、不反推。每个 UNALIGNED 单元都带 `skipReason` 和 `reasons`，
 * 按它们分类即可；分类正确性可以直接对着文案核实。
 *
 * ## 七类
 *
 * | 类别 | 判据 | 用户视角 |
 * | --- | --- | --- |
 * | A-quiet | ALIGNED ∧ 译文正确 | 最理想 |
 * | A-wrong | ALIGNED ∧ 译文错 | **静默错（红线）** |
 * | U-correct | UNCERTAIN ∧ 正确 | 答案对，但白给警告 |
 * | U-wrong | UNCERTAIN ∧ 错 | 有警告的错 |
 * | N-collapse | UNALIGNED ∧ reasons 含 segment/plan 相关 | 本该能给 |
 * | N-fragment | UNALIGNED ∧ 源句无字母 | 伪句子 |
 * | N-real | 其余 UNALIGNED | 真无对应 |
 */
class Ch3SevenWayProbe {

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

    @Test
    fun measure() {
        var repo: File? = File(System.getProperty("user.dir"))
        while (repo != null && !File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").isFile) {
            repo = repo.parentFile
        }
        if (repo == null) { println("[seven] SKIP"); return }

        val json = JSONObject(File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")
        val enParas = ArrayList<String>()
        val zhParas = ArrayList<String>()
        data class P(val en: String, val zh: String)
        val truth = ArrayList<P>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            enParas += (0 until en.length()).joinToString(" ") { en.getString(it) }
            zhParas += (0 until zh.length()).joinToString("") { zh.getString(it) }
            for (k in 0 until en.length()) truth += P(en.getString(k), zh.getString(k))
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

        // IDF
        val df = HashMap<String, Int>()
        for (t in truth) contentWords(t.en).distinct().forEach { w -> df[w] = (df[w] ?: 0) + 1 }
        val nDoc = truth.size.toDouble()
        fun idf(w: String) = ln((nDoc + 1.0) / ((df[w] ?: 1) + 1.0)).coerceAtLeast(0.05)

        data class Bucket(var clicks: Int = 0, var weight: Double = 0.0, var sentences: Int = 0)
        val buckets = linkedMapOf(
            "A-quiet" to Bucket(),
            "A-wrong" to Bucket(),
            "U-correct" to Bucket(),
            "U-wrong" to Bucket(),
            "N-collapse" to Bucket(),
            "N-fragment" to Bucket(),
            "N-real" to Bucket()
        )

        var missingUnit = 0
        for (t in truth) {
            val u = unitOf[norm(t.en)]
            if (u == null) { missingUnit++; continue }
            val got = norm(u.targetText)
            val correct = got.isNotEmpty() && truthSet[norm(t.en)]?.contains(got) == true
            val reasons = u.evidence.reasons.joinToString(" ")

            val key = when (u.status) {
                AlignmentStatus.ALIGNED -> if (correct) "A-quiet" else "A-wrong"
                AlignmentStatus.UNCERTAIN -> if (correct) "U-correct" else "U-wrong"
                AlignmentStatus.UNALIGNED -> when {
                    // 引擎自己说「段内方案没覆盖 / 句数不等 / 没有单调解」
                    reasons.contains("sentence count mismatch", true) ||
                        reasons.contains("plan", true) ||
                        reasons.contains("no monotone", true) ||
                        reasons.contains("contract fill", true) -> "N-collapse"
                    // 源句不含字母（纯标点/符号）
                    t.en.none { it.isLetter() } -> "N-fragment"
                    else -> "N-real"
                }
            }
            val b = buckets.getValue(key)
            b.sentences++
            for (w in contentWords(t.en)) {
                b.clicks++
                b.weight += idf(w)
            }
        }

        val totalW = buckets.values.sumOf { it.weight }
        val totalSent = truth.size
        fun pw(b: Bucket) = if (totalW <= 0) 0.0 else b.weight * 100 / totalW
        fun ps(b: Bucket) = b.sentences * 100.0 / totalSent

        println("=== 七分类（重建版；IDF 加权 vs 句级）===")
        println("语料：${totalSent} 句 / ${truth.sumOf { contentWords(it.en).size }} 次内容词点击")
        if (missingUnit > 0) println("⚠️ 无单元的真值句 = $missingUnit")
        println()
        println("%-12s %9s %9s %8s".format("类别", "点击%", "句级%", "句数"))
        println("-".repeat(44))
        for ((k, b) in buckets) {
            println("%-12s %8.2f%% %8.2f%% %8d".format(k, pw(b), ps(b), b.sentences))
        }
        println("-".repeat(44))
        println("%-12s %8.2f%% %8.2f%% %8d".format("合计", 100.0, 100.0, totalSent))

        val aq = buckets.getValue("A-quiet")
        val aw = buckets.getValue("A-wrong")
        val uc = buckets.getValue("U-correct")
        val uw = buckets.getValue("U-wrong")
        val nc = buckets.getValue("N-collapse")
        val nf = buckets.getValue("N-fragment")
        val nr = buckets.getValue("N-real")

        println()
        println("=== 用户视角汇总（IDF 加权）===")
        println("  有答案且正确  (A-quiet + U-correct) = %.2f%%".format(pw(aq) + pw(uc)))
        println("    ├ 静默正确  (A-quiet)              = %.2f%%".format(pw(aq)))
        println("    └ 告警正确  (U-correct)            = %.2f%%  ← 白给警告".format(pw(uc)))
        println("  有答案但错    (A-wrong + U-wrong)   = %.2f%%".format(pw(aw) + pw(uw)))
        println("    ├ 静默错    (A-wrong)              = %.2f%%  ← **红线**".format(pw(aw)))
        println("    └ 告警错    (U-wrong)              = %.2f%%".format(pw(uw)))
        println("  没有答案      (N-*)                  = %.2f%%".format(pw(nc) + pw(nf) + pw(nr)))
        println("    ├ 本该能给  (N-collapse)           = %.2f%%".format(pw(nc)))
        println("    ├ 伪句子    (N-fragment)           = %.2f%%".format(pw(nf)))
        println("    └ 真无对应  (N-real)               = %.2f%%".format(pw(nr)))

        println()
        println("=== 优化空间（按可回收量排序）===")
        val items = listOf(
            Triple("U-correct → ALIGNED（提升证据强度）", pw(uc), "把「对的」变成「敢确认的」，减少白给警告"),
            Triple("N-collapse → 有答案", pw(nc), "段内对齐能覆盖的句子（当前已大幅改善）"),
            Triple("U-wrong → 剔除", pw(uw), "用更强证伪信号，不能靠降门槛"),
            Triple("N-fragment → 上游分句修复", pw(nf), "需要改 SentenceSplitter，影响 TTS"),
            Triple("N-real", pw(nr), "真无对应，不可回收")
        )
        items.sortedByDescending { it.second }.forEach {
            println("  %6.2f%%  %s".format(it.second, it.first))
            println("          ${it.third}")
        }

        // 代价模型（按 Lead 裁定：c_none >= c_warnedWrong）
        println()
        println("=== 用户体验分（三种代价模型）===")
        fun score(cNone: Double, cCorrectWarned: Double, cWrongWarned: Double, cWrongSilent: Double): Double {
            val cost = (pw(nc) + pw(nf) + pw(nr)) / 100.0 * cNone +
                pw(uc) / 100.0 * cCorrectWarned +
                pw(uw) / 100.0 * cWrongWarned +
                pw(aw) / 100.0 * cWrongSilent
            return (1.0 - cost) * 100.0
        }
        println("  M1 c_none=1.0 c_warnWrong=0.7  → %.2f".format(score(1.0, 0.1, 0.7, 2.0)))
        println("  M2 c_none=c_warnWrong=1.0      → %.2f".format(score(1.0, 0.1, 1.0, 2.0)))
        println("  M3 c_none=1.2 c_warnWrong=1.0  → %.2f".format(score(1.2, 0.1, 1.0, 2.0)))
        println()
        println("  （对比：修复前 M1=82.51，M2=82.09，M3=79.46）")
    }
}
