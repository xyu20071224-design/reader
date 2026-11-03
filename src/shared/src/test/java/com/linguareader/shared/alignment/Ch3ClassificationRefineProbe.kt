package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.ln

/**
 * 分类优化：把现有三态**按「用户视角的后果」重新拆解**，为后续决定 B/C 提供依据。
 *
 * ## 现状分类的混淆
 *
 * 1. `UNCERTAIN` 里混着两类：
 *    - U-correct：候选正确但证据弱（实测 298/318 = 93.7%）
 *    - U-wrong：候选就是错的（20/318 = 6.3%）
 *    对用户而言前者是「白给警告」，后者是「有警告的错误」。
 *
 * 2. `UNALIGNED` 里混着三类：
 *    - N-real：真没有对应（译文缺失/纯标点/结构性）
 *    - N-collapse：**有译文**，但因段内句数不等整段放弃（实测 103 条全是此类）
 *    - N-fragment：分句碎片造成的伪句子
 *    前两类对用户完全不同：一个是「本来就没有」，一个是「我们能给但没给」。
 *
 * 3. 还有一类被忽略：`ALIGNED` 但译文只是**段落的一部分**（不可能，但需校验）。
 *
 * ## 本探针产出
 *
 * 对 4520 次点击，给出**七分类**（含每类的实际正确率），并给出两个上界：
 * - 修掉 N-collapse 后 B 策略的分布
 * - 若段内 DP 还能救回 U-wrong 的一部分，分布会怎样
 */
class Ch3ClassificationRefineProbe {

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
        if (repo == null) { println("[refine] SKIP"); return }

        val json = JSONObject(File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")
        val enParas = ArrayList<String>()
        val zhParas = ArrayList<String>()
        data class P(val en: String, val zh: String, val para: Int, val idxInPara: Int)
        val truth = ArrayList<P>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            enParas += (0 until en.length()).joinToString(" ") { en.getString(it) }
            zhParas += (0 until zh.length()).joinToString("") { zh.getString(it) }
            for (k in 0 until en.length()) truth += P(en.getString(k), zh.getString(k), i, k)
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

        // ── 七分类
        class Bucket(val name: String, val desc: String) {
            var w = 0.0
            var n = 0
        }
        val buckets = linkedMapOf(
            "A-quiet" to Bucket("A-quiet", "ALIGNED 且正确（最理想）"),
            "A-wrong" to Bucket("A-wrong", "ALIGNED 但错（**静默错，红线**）"),
            "U-correct" to Bucket("U-correct", "UNCERTAIN 但答案正确（白给警告）"),
            "U-wrong" to Bucket("U-wrong", "UNCERTAIN 且答案错（有警告的错）"),
            "N-collapse" to Bucket("N-collapse", "UNALIGNED 但**有译文**（段内句数不等，整段放弃）"),
            "N-fragment" to Bucket("N-fragment", "UNALIGNED 且是分句碎片（伪句子）"),
            "N-real" to Bucket("N-real", "UNALIGNED 且真无对应（漏译/结构性）")
        )

        // 段落内英文/中文重新分句后的句数（判断 collapse）
        val enSplit = enParas.map { SentenceSplitter.split(it) }
        val zhSplit = zhParas.map { SentenceSplitter.split(it) }

        for (t in truth) {
            val u = unitOf[norm(t.en)]
            val status = u?.status
            val shown = norm(u?.targetText ?: "")
            val isCorrect = shown.isNotEmpty() && truthSet[norm(t.en)]?.contains(shown) == true

            val bucketKey = when (status) {
                AlignmentStatus.ALIGNED -> if (isCorrect) "A-quiet" else "A-wrong"
                AlignmentStatus.UNCERTAIN -> if (isCorrect) "U-correct" else "U-wrong"
                AlignmentStatus.UNALIGNED -> {
                    // **修正分类口径（2026-10-31）**：不能只看「该段是否句数不等」，
                    // 必须看引擎实际给出的 skipReason 与 reason 文案。
                    // 之前的实现把所有不满足 countMismatch 的 UNALIGNED 都归到 N-real，
                    // 而 N-real 的语义是「真无对应」——两者不是一回事。
                    val reasons = u.evidence.reasons.joinToString(" ")
                    when {
                        // 引擎自己说「段内句数不等」→ 这是本可对齐却放弃
                        reasons.contains("sentence count mismatch") -> "N-collapse"
                        // 引擎说「跳过该句」→ 该句确实没被分配到中文
                        reasons.contains("skipped this sentence") -> "N-real"
                        // 原句本身不是语言单位（纯标点）
                        u.sourceText.none { it.isLetter() } -> "N-fragment"
                        else -> "N-real"
                    }
                }
                null -> "N-real"
            }
            val b = buckets.getValue(bucketKey)
            for (w in contentWords(t.en)) {
                val wt = idf(w)
                b.w += wt
                b.n++
            }
        }

        val totalW = buckets.values.sumOf { it.w }
        val totalN = buckets.values.sumOf { it.n }

        println("=== 七分类（IDF 加权，$totalN 次点击）===")
        println("%-12s %8s %8s  %s".format("类别", "点击%", "句数", "含义"))
        println("-".repeat(92))
        for (b in buckets.values) {
            println("%-12s %7.2f%% %8d  %s".format(
                b.name, if (totalW <= 0) 0.0 else b.w * 100 / totalW, b.n, b.desc))
        }

        val quiet = buckets.getValue("A-quiet")
        val aWrong = buckets.getValue("A-wrong")
        val uCorrect = buckets.getValue("U-correct")
        val uWrong = buckets.getValue("U-wrong")
        val nCollapse = buckets.getValue("N-collapse")
        val nFrag = buckets.getValue("N-fragment")
        val nReal = buckets.getValue("N-real")

        fun pct(b: Bucket) = if (totalW <= 0) 0.0 else b.w * 100 / totalW

        println()
        println("=== 关键量 ===")
        println("  可立即展示且正确（A-quiet + U-correct）: %.2f%%".format(pct(quiet) + pct(uCorrect)))
        println("  其中带多余警告的（U-correct）           : %.2f%%  ← 白给警告".format(pct(uCorrect)))
        println("  静默错（A-wrong）                      : %.2f%%  ← 红线".format(pct(aWrong)))
        println("  有警告的错（U-wrong）                   : %.2f%%".format(pct(uWrong)))
        println("  有答案但没给（N-collapse）              : %.2f%%  ← **最大可回收项**".format(pct(nCollapse)))
        println("  伪句子（N-fragment）                    : %.2f%%".format(pct(nFrag)))
        println("  真无对应（N-real）                      : %.2f%%".format(pct(nReal)))

        // ── 上界 1：修掉 N-collapse（段内 1:0 跳过）
        println()
        println("=== 修复情景（估算，未实测）===")
        // 保守假设：collapse 里 80% 能救回，其中 85% 正确
        val rescueRate = 0.80
        val rescueAccuracy = 0.85
        val rescued = nCollapse.w * rescueRate
        val rescuedCorrect = rescued * rescueAccuracy
        val rescuedWrong = rescued - rescuedCorrect
        println("  情景 1：修「段内句数不等」（救回 80%%，其中 85%% 正确）")
        println("    无答案     : %.2f%% → %.2f%%".format(
            pct(nCollapse) + pct(nFrag) + pct(nReal),
            pct(nCollapse) * (1 - rescueRate) + pct(nFrag) + pct(nReal)))
        println("    可展示正确 : %.2f%% → %.2f%%".format(
            pct(quiet) + pct(uCorrect),
            pct(quiet) + pct(uCorrect) + rescuedCorrect * 100 / totalW))
        println("    告警错新增 : %.2f%% → %.2f%%".format(
            pct(uWrong), pct(uWrong) + rescuedWrong * 100 / totalW))
        println("    静默错     : %.2f%%（不变，必须保持 0）".format(pct(aWrong)))

        println()
        println("  情景 2：再把 U-correct 升为 ALIGNED（门槛放宽到刚好不引入静默错）")
        println("    告警占比   : %.2f%% → %.2f%%".format(
            pct(uCorrect), 0.0))
        println("    ⚠️ 但如果放宽过头，U-wrong 会变成 A-wrong（静默错），红线破。")
        println("       实测 U-wrong = %.2f%% 是这条线的**硬约束**。".format(pct(uWrong)))

        // ── 分类的可优化点清单
        println()
        println("=== 分类层面的可优化点（按用户收益排序）===")
        println("  1. N-collapse(%.2f%%) → 修段内 DP。收益最大且风险低（不碰判定门槛）。".format(pct(nCollapse)))
        println("  2. U-correct(%.2f%%) → 提升证据强度（锚点/词典），把「对的」变成「敢确认的」。".format(pct(uCorrect)))
        println("  3. U-wrong(%.2f%%) → 靠更强的证伪信号剔除，而不是降低门槛。".format(pct(uWrong)))
        println("  4. N-fragment(%.2f%%) → 分句修复，属上游问题。".format(pct(nFrag)))
        println("  5. N-real(%.2f%%) → 真没有对应，不可回收。".format(pct(nReal)))
    }
}
