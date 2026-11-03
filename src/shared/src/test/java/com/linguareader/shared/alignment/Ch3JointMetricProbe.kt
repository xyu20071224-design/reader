package com.linguareader.shared.alignment

import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.ln
import kotlin.math.pow

/**
 * 联合用户体验指标：把 N1（有没有答案）与 N2（答案对不对）合成一个分数。
 *
 * ## 为什么不能简单相乘
 *
 * `N1 × N2` 隐含「拿不到答案」与「拿到错答案」代价相同。但用户侧代价明显不对称：
 * 没答案 = 少一次便利；错答案 = 学错意思（且会被记住）。
 *
 * ## 候选函数（逐个实测，看它们把不同产品策略排成什么顺序）
 *
 * 1. `product`      = N1 × N2                      —— 朴素，对称惩罚
 * 2. `lambda`       = (1-λ)·N1 + λ·N2               —— 线性加权，λ=0.7
 * 3. `kv`           = N1 · N2^κ  (κ=2)              —— 正确率指数惩罚（「错一次抵多次没答案」）
 * 4. `expectation`  = 1 - [p_noAns·c1 + p_wrongWarned·c2 + p_wrongSilent·c3]
 *                     c1=0.3（没答案，轻）
 *                     c2=0.7（有告警的错，中）
 *                     c3=1.0（静默错，最重）
 *    —— 这是**期望代价**模型，最贴近「用户实际感受」的物理含义。
 *
 * ## 同时比较三种产品策略
 *
 * A. 严格句级（现状）：UNCERTAIN 与 UNALIGNED 都不给译文
 * B. UNCERTAIN 给译文（带「把握不足」标注）：现状已如此
 * C. 段落兜底：UNALIGNED 时展示所在段落译文（标「段级」）
 *
 * 这样能看出：**哪个联合函数会把 C 排在 A/B 前面**——如果排前面，说明该函数
 * 忽视了「用户要的是句级」这个需求。
 */
class Ch3JointMetricProbe {

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
        if (repo == null) { println("[joint] SKIP"); return }

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

        // IDF
        val df = HashMap<String, Int>()
        for (t in truth) contentWords(t.en).distinct().forEach { w -> df[w] = (df[w] ?: 0) + 1 }
        val nDoc = truth.size.toDouble()
        fun idf(w: String): Double = ln((nDoc + 1.0) / ((df[w] ?: 1) + 1.0)).coerceAtLeast(0.05)

        // ── 三种策略下的逐点击结果
        // 每种策略： weighted counts
        class Acc {
            var wAll = 0.0
            var wNoAnswer = 0.0        // 什么都没给
            var wCorrect = 0.0         // 给了且对
            var wWrongWarned = 0.0     // 给了、错、有标注
            var wWrongSilent = 0.0     // 给了、错、无标注（ALIGNED）
        }

        fun evalStrategy(useUncertainText: Boolean, paraFallback: Boolean): Acc {
            val acc = Acc()
            for (t in truth) {
                val u = unitOf[norm(t.en)]
                val status = u?.status
                var shown = ""
                var warned = false
                when {
                    status == AlignmentStatus.ALIGNED -> shown = norm(u.targetText)
                    status == AlignmentStatus.UNCERTAIN && useUncertainText -> {
                        shown = norm(u.targetText)
                        warned = true
                    }
                    else -> {
                        if (paraFallback) {
                            // 段落兜底：展示该英文句所在段落的完整中文
                            val pi = t.para
                            val paraZh = guardParas(zhParas, pi)
                            shown = paraZh
                            warned = true // 段级必须标注
                        }
                    }
                }
                val correct = shown.isNotEmpty() && truthSet[norm(t.en)]?.contains(shown) == true
                for (w in contentWords(t.en)) {
                    val wt = idf(w)
                    acc.wAll += wt
                    when {
                        shown.isEmpty() -> acc.wNoAnswer += wt
                        correct -> acc.wCorrect += wt
                        warned -> acc.wWrongWarned += wt
                        else -> acc.wWrongSilent += wt
                    }
                }
            }
            return acc
        }

        val strategies = listOf(
            Triple("A 严格句级(UNCERTAIN不展示)", false, false),
            Triple("B UNCERTAIN展示+标注", true, false),
            Triple("C 段落兜底(UNALIGNED展示段落)", true, true)
        )

        // ── 联合函数
        fun jointProduct(acc: Acc): Double =
            (acc.wCorrect / acc.wAll) * ((acc.wCorrect + acc.wWrongWarned + acc.wWrongSilent) / acc.wAll)

        fun jointLambda(acc: Acc, lam: Double): Double {
            val n1 = (acc.wCorrect + acc.wWrongWarned + acc.wWrongSilent) / acc.wAll
            val n2 = acc.wCorrect / (acc.wCorrect + acc.wWrongWarned + acc.wWrongSilent).coerceAtLeast(1e-9)
            return (1 - lam) * n1 + lam * n2
        }

        fun jointKappa(acc: Acc, kappa: Double): Double {
            val n1 = (acc.wCorrect + acc.wWrongWarned + acc.wWrongSilent) / acc.wAll
            val n2 = acc.wCorrect / (acc.wCorrect + acc.wWrongWarned + acc.wWrongSilent).coerceAtLeast(1e-9)
            return n1 * n2.pow(kappa)
        }

        fun jointExpectation(acc: Acc, c1: Double = 0.3, c2: Double = 0.7, c3: Double = 1.0): Double {
            val cost = (acc.wNoAnswer * c1 + acc.wWrongWarned * c2 + acc.wWrongSilent * c3) / acc.wAll
            return 1.0 - cost
        }

        println("=== 联合用户体验指标（IDF 加权，4520 次点击机会）===")
        println()
        println("%-32s %8s %8s %8s %10s %10s %10s".format(
            "策略", "无答案%", "正确%", "告警错%", "静默错%", "N1×N2", "期望体验"))
        println("-".repeat(96))
        val results = ArrayList<Triple<String, Acc, Double>>()
        for ((label, unc, fb) in strategies) {
            val acc = evalStrategy(unc, fb)
            val noAns = acc.wNoAnswer / acc.wAll * 100
            val correct = acc.wCorrect / acc.wAll * 100
            val ww = acc.wWrongWarned / acc.wAll * 100
            val ws = acc.wWrongSilent / acc.wAll * 100
            val exp = jointExpectation(acc) * 100
            val prod = jointProduct(acc) * 100
            results += Triple(label, acc, exp)
            println("%-32s %7.2f%% %7.2f%% %7.2f%% %9.2f%% %9.2f%% %9.2f%%".format(
                label, noAns, correct, ww, ws, prod, exp))
        }

        println()
        println("=== 各联合函数对三种策略的排序 ===")
        for ((label, acc, _) in results) {
            println("  $label")
            println("    product      = %.4f".format(jointProduct(acc)))
            println("    lambda(0.7)  = %.4f".format(jointLambda(acc, 0.7)))
            println("    kappa(2.0)   = %.4f".format(jointKappa(acc, 2.0)))
            println("    expectation  = %.4f".format(jointExpectation(acc)))
        }

        println()
        println("=== 关键判读 ===")
        val a = results[0].second
        val b = results[1].second
        val c = results[2].second
        fun better(x: Double, y: Double) = if (x > y) "胜" else "负"
        println("  product:     B %s A (%.4f vs %.4f)".format(
            better(jointProduct(b), jointProduct(a)), jointProduct(b), jointProduct(a)))
        println("  expectation: B %s A (%.4f vs %.4f)".format(
            better(jointExpectation(b), jointExpectation(a)), jointExpectation(b), jointExpectation(a)))
        println("  product:     C %s B (%.4f vs %.4f)".format(
            better(jointProduct(c), jointProduct(b)), jointProduct(c), jointProduct(b)))
        println("  expectation: C %s B (%.4f vs %.4f)".format(
            better(jointExpectation(c), jointExpectation(b)), jointExpectation(c), jointExpectation(b)))
        println()
        println("  ⚠️ 若 product 认为 C 优于 B，而 expectation 认为 B 优于 C，")
        println("     说明「段落兜底」只在「数量优先」的口径下才显得好——")
        println("     但它给用户的不是「这一句的译文」，与需求 N2 的定义冲突。")
    }

    private fun guardParas(zhParas: List<String>, i: Int): String =
        if (i in zhParas.indices) zhParas[i] else ""
}
