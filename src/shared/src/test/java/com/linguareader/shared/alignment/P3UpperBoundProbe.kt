package com.linguareader.shared.alignment

import com.linguareader.shared.translation.MeaningIndex
import com.linguareader.shared.translation.TranslationAligner
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.ln

/**
 * **P3 上界实验（零 API 费用）**：如果把**完美句界**直接喂给对齐器，静默错还剩多少？
 *
 * ## 这个实验回答什么
 *
 * P3 的构想是「让模型按句返回译文，把构造性句界传给对齐器，绕过中文分句器」。
 * 在投入那 12~14 个文件的改造之前，必须知道它的**天花板**：即使句界完美，
 * 对齐器还有多少错？
 *
 * ## 做法
 *
 * 把真值的中英文**逐句**拆成「一段一句」再喂给 `TranslationAligner.align`：
 *  - 中文侧每段恰好一句 → 分句器不可能切错（这是 P3 能达到的最好状态）
 *  - 英文侧同样逐句 → 段级对齐即句级对齐
 * 于是测量结果就是「句界完美 + 现有对齐算法」的上界。
 *
 * ## 判读
 *
 *  - 若上界静默错 ≈ 0 → P3 的收益等于当前全部残差，值得做
 *  - 若上界静默错与当前接近 → 残差不在句界，P3 无用
 *  - 中间态 → 差额就是 P3 的最大收益，再权衡改造代价
 */
class P3UpperBoundProbe {

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

    @Test
    fun measure() {
        val repo = repoRoot() ?: run { println("[p3ub] SKIP"); return }
        val ecdict = EcdictProvider.load(repo)
        val meaningIndex: MeaningIndex? = ecdict?.let { p ->
            object : MeaningIndex {
                override fun phrasesOf(word: String): Set<String> =
                    p.chineseCandidates(word).flatMap { EcdictProvider.parseGlosses(it) }.toSet()
            }
        }

        for ((name, rel) in listOf(
            "ch1" to "artifacts/ai-ch1/ch1_truth.json",
            "ch3" to "artifacts/ai-ch3/ch3_merged_partial.json"
        )) {
            val file = File(repo, rel)
            if (!file.isFile) { println("=== $name MISSING ==="); continue }
            val arr = JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("paragraphs")

            // 真值逐句列表
            data class T(val en: String, val zh: String)
            val truth = ArrayList<T>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val en = o.getJSONArray("en")
                val zh = o.getJSONArray("zh")
                for (k in 0 until minOf(en.length(), zh.length())) {
                    truth += T(en.getString(k), zh.getString(k))
                }
            }

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

            println()
            println("=== $name （真值句 ${truth.size}，不同英文句 ${distinctEn.size}）===")

            // ---- A. 句界完美：保留段落结构，只把中文分句换成真值切分（真正的 P3 形态）----
            val enParas = ArrayList<String>()
            val zhParas = ArrayList<String>()
            val known = HashMap<String, List<String>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val en = o.getJSONArray("en")
                val zh = o.getJSONArray("zh")
                val enText = StringBuilder()
                val zhText = StringBuilder()
                val zhSents = ArrayList<String>()
                for (k in 0 until en.length()) {
                    if (enText.isNotEmpty()) enText.append(' ')
                    enText.append(en.getString(k))
                    val z = zh.getString(k)
                    zhText.append(z)
                    zhSents += z
                }
                enParas.add(enText.toString())
                zhParas.add(zhText.toString())
                known[zhText.toString()] = zhSents
            }
            val pairsPerfect = TranslationAligner.align(
                listOf(enParas), listOf(zhParas), meaningIndex,
                com.linguareader.shared.translation.TranslationAligner.ZhSplitPolicy
                    .withKnownSentences(known)
            )
            report("A 构造性句界（P3）", pairsPerfect, distinctEn, truthSet, wOf, wAll)

            // ---- B. 现状：同一批段落，让分句器自己推断 ----
            val pairsCurrent = TranslationAligner.align(listOf(enParas), listOf(zhParas), meaningIndex)
            report("B 现状（分句器推断）", pairsCurrent, distinctEn, truthSet, wOf, wAll)
        }

        println()
        println("=== 判读 ===")
        println("  A 是 P3 能达到的状态（中文句界由构造已知，英文侧仍用生产分句器）。")
        println("  A 与 B 的差额 = P3 的实际收益。若差额很小 → 不值得做 12~14 个文件的改造。")
    }

    private fun report(
        label: String,
        pairs: List<com.linguareader.shared.translation.AlignedSentencePair>,
        distinctEn: Set<String>,
        truthSet: Map<String, MutableSet<String>>,
        wOf: Map<String, Double>,
        wAll: Double
    ) {
        val got = HashMap<String, String>()
        for (p in pairs) {
            val k = p.enSentence.filterNot { it.isWhitespace() }
            if (k.isNotEmpty() && !got.containsKey(k)) got[k] = p.zhSentence.filterNot { it.isWhitespace() }
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
        val wp = { v: Double -> if (wAll <= 0) 0.0 else v * 100 / wAll }
        val cost = wp(wNone) + 2.0 * wp(wWrong)
        println(
            "  $label: pairs=${pairs.size} 正确=${correct}(${"%.2f".format(correct * 100.0 / total)}%) " +
                "静默错=$wrong(${"%.2f".format(wrong * 100.0 / total)}%) 无答案=$none | " +
                "加权: 正确 ${"%.2f".format(wp(wCorrect))}% 静默错 ${"%.2f".format(wp(wWrong))}% " +
                "无答案 ${"%.2f".format(wp(wNone))}% 代价=${"%.2f".format(cost)}"
        )
    }
}
