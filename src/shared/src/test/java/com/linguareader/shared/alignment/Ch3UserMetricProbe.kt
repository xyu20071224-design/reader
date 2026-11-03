package com.linguareader.shared.alignment

import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.ln

/**
 * 把「用户需求」直接翻译成可测量指标，并实测。
 *
 * 用户只关心两件事：
 *  N1「我点了一个词，有没有句级译文？」        → 点击可用率
 *  N2「给我看的这句中文，是不是这一句的译文？」 → 点击正确率
 *
 * 与我们现在测的指标差别：
 *  - 我们的 coverage / precision 是**句子加权**。
 *  - 用户是**按词点击**。长句有更多词 → 更多点击机会；用户点的是**不认识的词**（生僻词），
 *    不是功能词。所以加权方式必须换成「词次 × IDF」。
 *
 * 本探针同时给出三种加权，用来看「指标对加权口径有多敏感」：
 *  A. 句子等权（= 现有指标的口径）
 *  B. 词次等权（每个内容词一次点击机会）
 *  C. 词次 × IDF（生僻词更可能被点）
 */
class Ch3UserMetricProbe {

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    /** 英文功能词（用户基本不会点）。 */
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
        if (repo == null) { println("[user] SKIP"); return }

        val json = JSONObject(File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")
        val enParas = ArrayList<String>()
        val zhParas = ArrayList<String>()
        data class Pair(val en: String, val zh: String)
        val truth = ArrayList<Pair>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            enParas += (0 until en.length()).joinToString(" ") { en.getString(it) }
            zhParas += (0 until zh.length()).joinToString("") { zh.getString(it) }
            for (k in 0 until en.length()) truth += Pair(en.getString(k), zh.getString(k))
        }

        val provider = EcdictProvider.load(repo)
        val doc = AiTranslationAligner().align(
            listOf(AlignmentChapter("c", enParas)),
            listOf(AlignmentChapter("c", zhParas)),
            provider
        )

        // 真值多译集合（重复文本：同一句英文可能有多个合法译文）
        val truthSet = HashMap<String, MutableSet<String>>()
        for (t in truth) truthSet.getOrPut(norm(t.en)) { HashSet() }.add(norm(t.zh))

        // 单元索引：规范化后的英文句 → 单元
        val unitOf = HashMap<String, AlignedUnit>()
        for (u in doc.units) unitOf[norm(u.sourceText)] = u

        // ── IDF（本章内）
        val df = HashMap<String, Int>()
        for (t in truth) contentWords(t.en).distinct().forEach { w -> df[w] = (df[w] ?: 0) + 1 }
        val n = truth.size.toDouble()
        fun idf(w: String): Double {
            val d = df[w] ?: 1
            return ln((n + 1.0) / (d + 1.0)).coerceAtLeast(0.05)
        }

        // ── 逐词点击模拟
        var clicksTotal = 0
        var clicksAnswered = 0        // 有非空译文（ALIGNED 或 UNCERTAIN）
        var clicksAnsweredAligned = 0 // 仅 ALIGNED
        var clicksCorrect = 0         // 有译文且正确
        var clicksCorrectAligned = 0
        var clicksWrongConfident = 0  // ALIGNED 但错（最伤）
        var clicksWrongWarned = 0     // UNCERTAIN 但错

        var wAll = 0.0
        var wAnswered = 0.0
        var wAnsweredAligned = 0.0
        var wCorrect = 0.0
        var wCorrectAligned = 0.0
        var wWrongConfident = 0.0
        var wWrongWarned = 0.0

        var sentTotal = 0
        var sentAnswered = 0
        var sentAligned = 0
        var sentCorrect = 0
        var sentCorrectAligned = 0

        for (t in truth) {
            val u = unitOf[norm(t.en)]
            val shown = norm(u?.targetText ?: "")
            val correct = shown.isNotEmpty() && truthSet[norm(t.en)]?.contains(shown) == true
            val status = u?.status

            sentTotal++
            if (shown.isNotEmpty()) sentAnswered++
            if (status == AlignmentStatus.ALIGNED) {
                sentAligned++
                if (correct) sentCorrectAligned++
            }
            if (correct) sentCorrect++

            // 每个内容词一次点击机会
            val words = contentWords(t.en)
            for (w in words) {
                val weight = idf(w)
                clicksTotal++
                wAll += weight
                when {
                    shown.isEmpty() -> Unit
                    else -> {
                        clicksAnswered++
                        wAnswered += weight
                        if (status == AlignmentStatus.ALIGNED) {
                            clicksAnsweredAligned++
                            wAnsweredAligned += weight
                        }
                        if (correct) {
                            clicksCorrect++
                            wCorrect += weight
                            if (status == AlignmentStatus.ALIGNED) {
                                clicksCorrectAligned++
                                wCorrectAligned += weight
                            }
                        } else {
                            if (status == AlignmentStatus.ALIGNED) {
                                clicksWrongConfident++
                                wWrongConfident += weight
                            } else {
                                clicksWrongWarned++
                                wWrongWarned += weight
                            }
                        }
                    }
                }
            }
        }

        fun p(a: Double, b: Double) = if (b <= 0.0) 0.0 else a * 100.0 / b
        fun pi(a: Int, b: Int) = if (b == 0) 0.0 else a * 100.0 / b

        println("=== 用户需求指标（魔戒第 3 章，730 句，真实 AI 译本）===")
        println("内容词点击机会 = $clicksTotal 次（句子等权口径下是 $sentTotal 句）")
        println()

        println("--- N1：点了词，有没有句级译文 ---")
        println("  句子等权 : %.2f%%  (有译文 %d / %d 句)".format(
            pi(sentAnswered, sentTotal), sentAnswered, sentTotal))
        println("    └ 仅可信(ALIGNED): %.2f%%  (%d 句)".format(pi(sentAligned, sentTotal), sentAligned))
        println("  词次等权 : %.2f%%  (有译文 %d / %d 次点击)".format(
            pi(clicksAnswered, clicksTotal), clicksAnswered, clicksTotal))
        println("    └ 仅可信(ALIGNED): %.2f%%".format(pi(clicksAnsweredAligned, clicksTotal)))
        println("  词次×IDF: %.2f%%".format(p(wAnswered, wAll)))
        println("    └ 仅可信(ALIGNED): %.2f%%".format(p(wAnsweredAligned, wAll)))
        println()

        println("--- N2：给我看的，是不是这一句的译文 ---")
        println("  句子等权 : %.2f%%  (%d / %d 有答案的句)".format(
            pi(sentCorrect, sentAnswered), sentCorrect, sentAnswered))
        println("  词次等权 : %.2f%%  (%d / %d 次点击)".format(
            pi(clicksCorrect, clicksAnswered), clicksCorrect, clicksAnswered))
        println("  词次×IDF: %.2f%%".format(p(wCorrect, wAnswered)))
        println("  仅可信(ALIGNED) 句子等权: %.2f%%".format(pi(sentCorrectAligned, sentAligned)))
        println()

        println("--- 伤害分解（用户拿到错答案的频率）---")
        println("  自信地错（ALIGNED 但错）: %d 次 (%.3f%% 词次 / %.3f%% IDF加权)".format(
            clicksWrongConfident,
            pi(clicksWrongConfident, clicksAnswered),
            p(wWrongConfident, wAll)))
        println("  有警告的错（UNCERTAIN 但错）: %d 次 (%.3f%% 词次 / %.3f%% IDF加权)".format(
            clicksWrongWarned,
            pi(clicksWrongWarned, clicksAnswered),
            p(wWrongWarned, wAll)))
        println()

        // ── 加权敏感性：同一份数据在不同口径下差多少
        println("=== 指标对加权口径的敏感性 ===")
        val covSent = pi(sentAligned, sentTotal)
        val covWord = pi(clicksAnsweredAligned, clicksTotal)
        val covIdf = p(wAnsweredAligned, wAll)
        println("  coverage(ALIGNED): 句子 %.2f%% | 词次 %.2f%% | IDF %.2f%%".format(covSent, covWord, covIdf))
        println("  → 极差 = %.2f 个百分点".format(kotlin.math.max(covSent, kotlin.math.max(covWord, covIdf)) -
            kotlin.math.min(covSent, kotlin.math.min(covWord, covIdf))))
        println()

        // ── 长句 vs 短句：点击集中在哪
        println("=== 点击机会分布（长句是否被过度代表）===")
        val byLen = truth.map { it to contentWords(it.en).size }
        val totalC = byLen.sumOf { it.second }
        val longContribution = byLen.filter { it.second >= 10 }.sumOf { it.second }
        println("  内容词 >=10 的句子: %d 句（%.1f%% 的句子），贡献 %d 次点击（%.1f%% 的点击）".format(
            byLen.count { it.second >= 10 },
            pi(byLen.count { it.second >= 10 }, byLen.size),
            longContribution, pi(longContribution, totalC)))
        println("  → 句子等权会低估长句的影响；用户实际点击更集中在这类句子。")

        // ── 段落级兜底是否有用（N1 的另一种可能形态）
        println()
        println("=== 若允许「段落级译文」兜底，N1 上限 ===")
        var wouldAnswer = 0
        for (t in truth) {
            val u = unitOf[norm(t.en)]
            val shown = norm(u?.targetText ?: "")
            if (shown.isNotEmpty()) wouldAnswer++ else {
                // UNALIGNED 时若展示所在段落译文，算「有答案但非句级」
                wouldAnswer++
            }
        }
        println("  展示段落兜底后：%d / %d = %.2f%% 的点击至少能看到点什么".format(
            wouldAnswer, sentTotal, pi(wouldAnswer, sentTotal)))
        println("  ⚠️ 但这不是用户要的「句级」——需明确产品是否接受段落兜底。")
    }
}
