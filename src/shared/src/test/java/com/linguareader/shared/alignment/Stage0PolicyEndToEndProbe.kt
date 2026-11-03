package com.linguareader.shared.alignment

import com.linguareader.shared.translation.MeaningIndex
import com.linguareader.shared.translation.TranslationAligner
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.ln

/**
 * **阶段 0c**：用**生产 DP 本身**（`TranslationAligner.align`）端到端比较中文分句口径。
 *
 * ## 为什么不能只用朴素按标配对来估
 *
 * `Stage0SplitterVariantProbe` 用的是「句数相等就按段内下标一一配对」的朴素模型。
 * 它显示「选择性合并」提高了句数相等率、却降低了朴素配对正确率，但生产链路有
 * DP 代价、[com.linguareader.shared.translation.TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE]
 * 与长度比门槛——错配可能被降级成「无答案」（代价远低于静默错）。
 *
 * 所以必须端到端跑真 DP，用同一套用户代价口径判优劣。
 */
class Stage0PolicyEndToEndProbe {

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

    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, EcdictProvider.EXPORT_RELATIVE_PATH).isFile) dir = dir.parentFile
        return dir
    }

    /** 用户代价（与 AlignmentAcceptance 同口径）：无答案 1.0，告警错 1.0，静默错 2.0。 */
    private fun cost(noAnswer: Double, silentWrong: Double) = noAnswer + 2.0 * silentWrong

    @Test
    fun measure() {
        val repo = repoRoot() ?: run { println("[0c] SKIP"); return }
        val ecdict = EcdictProvider.load(repo)
        val meaningIndex: MeaningIndex? = ecdict?.let { p ->
            object : MeaningIndex {
                override fun phrasesOf(word: String): Set<String> =
                    p.chineseCandidates(word)
                        .flatMap { EcdictProvider.parseGlosses(it) }
                        .toSet()
            }
        }

        val policies = listOf(
            "P0 含；句界 (V8)" to TranslationAligner.ZhSplitPolicy.fixed(true),
            "P1 不含； (V9)" to TranslationAligner.ZhSplitPolicy.fixed(false),
            "P2 逐段自适应 (V10)" to TranslationAligner.ZhSplitPolicy.PRODUCTION
        )

        for ((name, rel) in listOf(
            "ch1" to "artifacts/ai-ch1/ch1_truth.json",
            "ch3" to "artifacts/ai-ch3/ch3_merged_partial.json"
        )) {
            val file = File(repo, rel)
            if (!file.isFile) { println("=== $name MISSING ==="); continue }
            val arr = JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("paragraphs")

            val enParas = ArrayList<String>()
            val zhParas = ArrayList<String>()
            data class T(val en: String, val zh: String)
            val truth = ArrayList<T>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val en = o.getJSONArray("en")
                val zh = o.getJSONArray("zh")
                val enText = StringBuilder()
                val zhText = StringBuilder()
                for (k in 0 until en.length()) {
                    if (enText.isNotEmpty()) enText.append(' ')
                    enText.append(en.getString(k))
                    zhText.append(zh.getString(k))
                    truth += T(en.getString(k), zh.getString(k))
                }
                enParas.add(enText.toString())
                zhParas.add(zhText.toString())
            }

            println()
            println("=== $name （${arr.length()} 段 / ${truth.size} 句）===")

            val truthSet = HashMap<String, MutableSet<String>>()
            for (t in truth) truthSet.getOrPut(norm(t.en)) { HashSet() }.add(norm(t.zh))

            val df = HashMap<String, Int>()
            for (t in truth) contentWords(t.en).distinct().forEach { w -> df[w] = (df[w] ?: 0) + 1 }
            val nDoc = truth.size.toDouble()
            fun idf(w: String) = ln((nDoc + 1.0) / ((df[w] ?: 1) + 1.0)).coerceAtLeast(0.05)

            val distinctEn = LinkedHashSet<String>()
            for (t in truth) distinctEn += norm(t.en)
            val wOf = HashMap<String, Double>()
            var wAll = 0.0
            for (k in distinctEn) {
                var b = 0.0
                for (w in contentWords(k)) b += idf(w)
                wOf[k] = b
                wAll += b
            }

            println("  ${"策略".padEnd(26)} ${"正确".padEnd(8)} ${"静默错".padEnd(9)} " +
                "${"无答案".padEnd(9)} ${"加权正确".padEnd(9)} ${"加权静默".padEnd(9)} 代价")
            for ((label, policy) in policies) {
                val started = System.currentTimeMillis()
                val pairs = TranslationAligner.align(
                    listOf(enParas), listOf(zhParas), meaningIndex,
                    sentencePositionPrior = 0.5,
                    paragraphPositionPrior = 0.5,
                    zhSplit = policy
                )
                val elapsed = System.currentTimeMillis() - started

                val got = HashMap<String, String>()
                for (p in pairs) {
                    val k = norm(p.enSentence)
                    if (!got.containsKey(k)) got[k] = norm(p.zhSentence)
                }

                var correct = 0
                var wrong = 0
                var none = 0
                var wCorrect = 0.0
                var wWrong = 0.0
                var wNone = 0.0
                for (k in distinctEn) {
                    val b = wOf[k] ?: 0.0
                    val g = got[k]
                    when {
                        g == null -> { none++; wNone += b }
                        truthSet[k]?.contains(g) == true -> { correct++; wCorrect += b }
                        else -> { wrong++; wWrong += b }
                    }
                }
                val total = distinctEn.size
                val pct = { v: Int -> v * 100.0 / total }
                val wpct = { v: Double -> v * 100.0 / wAll }
                val c = cost(wNone / wAll, wWrong / wAll) * 100

                println(
                    "  ${label.padEnd(26)} " +
                        "${"%.2f%%".format(pct(correct)).padEnd(8)} " +
                        "${"%.2f%%".format(pct(wrong)).padEnd(9)} " +
                        "${"%.2f%%".format(pct(none)).padEnd(9)} " +
                        "${"%.2f%%".format(wpct(wCorrect)).padEnd(9)} " +
                        "${"%.2f%%".format(wpct(wWrong)).padEnd(9)} " +
                        "%.2f  (%dms)".format(c, elapsed)
                )
            }
        }

        println()
        println("=== 判读 ===")
        println("  代价 = 加权无答案 ×1.0 + 加权静默错 ×2.0（用户：告警错≈无答案，静默错更贵）")
        println("  代价最低者胜。若 P0（无条件合并）仍最低 → 保持生产现状。")
    }
}
