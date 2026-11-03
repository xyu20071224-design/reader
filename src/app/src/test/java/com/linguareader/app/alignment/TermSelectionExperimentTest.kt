package com.linguareader.app.alignment

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.translation.EcdictMeaningIndex
import com.linguareader.app.tts.TtsTextExtractor
import com.linguareader.shared.translation.TraditionalSimplified
import com.linguareader.shared.tts.SentenceSplitter
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.math.ln

/**
 * **决定性实验：V1 recall 低，是「ECDICT 不行」还是「选词（`terms(E)`）不行」？**
 *
 * ## 背景（Lead 指出的方法论问题）
 *
 * 前一版 `LexicalRecallCeilingTest` 复刻建桥时**没有剔除功能词**，于是 `terms(E)` 里
 * 塞满了 `of/to/a/in/he/it/was/said` 的 ECDICT 释义（`属于`/`信息论`/`的过去式`），
 * 这些在文学译文里永不命中，却占满了 recall 的**分母权重** → recall 必然趋近 0。
 *
 * 那种测法测的是「选词 bug 有多严重」，**不是**「ECDICT 作为语义桥的能力上限」。
 * 本文件把两者分开测。
 *
 * ## 三个变体（同一 48 条人工批准样本、同一正确 target，只换选词策略）
 *
 * | 变体 | `terms(E)` 的来源词 |
 * | --- | --- |
 * | V0 现状复刻 | 词频 Top-1500，**剔除实现里的 `FUNCTION_WORDS` 表**（= 忠实复刻实现） |
 * | V1 IDF 反选 | 在上述基础上，按**英文侧 IDF 降序**取前 40%（丢弃 IDF 最低的 60%，即最"到处都有"的词） |
 * | V2 纯内容词 | 直接剔除**英文词频 Top-300**（标准停用词量级），再从剩下的词里取 Top-1500 |
 *
 * 三者的差别只在「哪些英文词进桥」，target、打分公式、IDF、df 过滤全部相同。
 *
 * ## 结果的含义（Lead 定义）
 *
 * - V1/V2 的 recall 中位数跃升到 **0.3+** ⇒ ECDICT 可行，问题在选词，修好就能用
 * - 仍 **< 0.05** ⇒ ECDICT 作为句级语义桥本质不行，架构级结论
 * - **0.05–0.3** ⇒ 部分可行，需要更强信号补充
 */
@RunWith(RobolectricTestRunner::class)
class TermSelectionExperimentTest {

    // ── 照抄 `AlignTuning` 常量（`:app` 测试够不到 `:shared` 的 `internal`）──
    private val LEXICON_TOP_K = 1500
    private val LEXICON_DF_RATIO = 0.15
    private val LEXICON_DF_MIN_ALLOWANCE = 3
    private val TERM_MIN_CHARS = 2
    private val TERM_MAX_CHARS = 4
    private val VERIFY_RECALL_MIN = 0.34

    private fun isCjk(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'
    private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun isDigit(c: Char) = c in '0'..'9'
    private fun isWordCont(c: Char) = isLetter(c) || isDigit(c) || c == '\'' || c == '’' || c == '-'

    /**
     * 实现里 `VerifyLayer.FUNCTION_WORDS` 的**逐字照抄**（这个集合是选词口径的一部分，
     * 不复刻它就不是「现状复刻」）。
     */
    private val FUNCTION_WORDS: Set<String> = setOf(
        "the", "a", "an", "of", "and", "to", "in", "is", "was", "were", "are", "be", "been",
        "being", "it", "its", "he", "she", "they", "them", "his", "her", "their", "you", "your",
        "i", "my", "me", "we", "our", "us", "that", "this", "these", "those", "there", "here",
        "for", "on", "at", "by", "with", "from", "as", "but", "or", "if", "not", "no", "yes",
        "do", "does", "did", "have", "has", "had", "will", "would", "could", "should", "can",
        "may", "might", "must", "shall", "so", "than", "then", "when", "where", "what", "which",
        "who", "whom", "whose", "how", "why", "all", "any", "some", "one", "two", "up", "down",
        "out", "into", "over", "under", "about", "after", "before", "again", "more", "most",
        "very", "just", "now", "only", "also", "too", "such", "own", "same", "other", "each",
        "every", "both", "few", "many", "much", "said", "says", "say"
    )

    private fun words(s: String): List<String> {
        val out = ArrayList<String>(16)
        var i = 0
        while (i < s.length) {
            if (isLetter(s[i])) {
                var j = i + 1
                while (j < s.length && isWordCont(s[j])) j++
                out += s.substring(i, j).lowercase(Locale.ROOT)
                i = j
            } else i++
        }
        return out
    }

    private fun cleanTerm(candidate: String): String? {
        var cjk = 0
        for (c in candidate) {
            when {
                isCjk(c) -> cjk++
                c.isWhitespace() -> Unit
                else -> return null
            }
        }
        if (cjk < TERM_MIN_CHARS || cjk > TERM_MAX_CHARS) return null
        return candidate.filter { isCjk(it) }
    }

    private fun f(v: Double, d: Int = 4) = String.format(Locale.ROOT, "%.${d}f", v)

    private fun percentile(v: List<Double>, p: Double): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.sorted()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }

    /** 一个变体跑完的结果。 */
    private class Result(
        val name: String,
        val selectedWords: Int,
        val bridgeWords: Int,
        val distinctTerms: Int,
        val keptTerms: Int,
        val meanTermsPerSentence: Double,
        val recalls: List<Double>
    ) {
        val median get() = recalls.sorted().let { it[it.size / 2] }
        val mean get() = recalls.average()
        val pass get() = recalls.count { it >= 0.34 }
        val zero get() = recalls.count { it == 0.0 }
    }

    @Test
    fun termSelectionIsTheBottleneck() {
        val root = LotrCorpus.findRoot()
        assumeTrue("缺少 artifacts 真实语料，跳过", root != null)
        val artifacts = File(root!!, "artifacts")
        val outDir = File(System.getProperty("java.io.tmpdir"), "t5-verify").apply { mkdirs() }
        val app = ApplicationProvider.getApplicationContext<Application>()

        val extractor = TtsTextExtractor()
        val enBook = LotrCorpus.readEnBook(File(artifacts, "lotr-book"))
        val zhBook = LotrCorpus.readZhBook(File(artifacts, "lotr-zh"))
        val enBlocks = enBook.chapters.indices.map { extractor.chapter(enBook, it).blocks }
        val zhBlocks = zhBook.chapters.indices.map { extractor.chapter(zhBook, it).blocks }
        val meaning = LotrCorpus.meaningProvider(EcdictMeaningIndex(app))

        val enSentences = enBlocks.flatten().flatMap { SentenceSplitter.split(it) }
        val zhSentences = zhBlocks.flatten().flatMap { SentenceSplitter.split(it) }
            .map { TraditionalSimplified.toSimplified(it) }

        // ── 英文词频 + 英文侧 df（IDF 用）──
        val freq = HashMap<String, Int>(32768)
        val enDf = HashMap<String, Int>(32768)
        for (s in enSentences) {
            val ws = words(s)
            for (w in ws) freq.merge(w, 1, Int::plus)
            for (w in ws.distinct()) enDf.merge(w, 1, Int::plus)
        }
        val enN = enSentences.size.toDouble()
        fun enIdf(w: String): Double = ln((1.0 + enN) / (1.0 + (enDf[w] ?: 0)))

        val samples = GoldenSamples.parse(File(artifacts, "alignment-eval/golden-samples.json"))
        val positive = samples.filter { !it.garbage && it.approvedByHuman && it.approvedLevel == "SENTENCE" }
            .filter { it.approvedZh.isNotBlank() }

        val text = StringBuilder()
        text.appendLine("# 决定性实验：`terms(E)` 选词策略 × V1 recall（人工真值）")
        text.appendLine()
        text.appendLine("样本 = 人工 `ok`/`ok2` 且 `approved.level=SENTENCE` 的句对（n=${positive.size}）。")
        text.appendLine("target = **人工批准的那条正确译文**（把 DP 选路错误完全排除）。")
        text.appendLine("打分公式 = `VerifyLayer.score` 的 recall（IDF 加权命中 / 全部 terms 的 IDF）。")
        text.appendLine()
        text.appendLine("## 对上一版测量的更正")
        text.appendLine()
        text.appendLine("前一版 `LexicalRecallCeilingTest` **没有剔除功能词**，`terms(E)` 里混入了")
        text.appendLine("`of/to/a/in/he/it/was/said` 的 ECDICT 释义（`属于`/`信息论`/`的过去式`…），")
        text.appendLine("这些在译文里永不命中却占满分母 ⇒ recall 被系统性压低。**那版数字作废。**")
        text.appendLine("本文件 V0 才是忠实复刻（含实现的 `FUNCTION_WORDS` 剔除）。")
        text.appendLine()

        // ══ 变体定义 ══
        val allRanked = freq.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
        )
        val contentWords = allRanked.filter { it.key !in FUNCTION_WORDS }
        val top300 = allRanked.take(300).map { it.key }.toSet()
        val idfSorted = contentWords.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { enIdf(it.key) }
                .thenByDescending { it.value }
                .thenBy { it.key }
        )

        val selections = linkedMapOf(
            "V0 现状复刻（词频 Top-1500，剔除 FUNCTION_WORDS）" to
                contentWords.take(LEXICON_TOP_K).map { it.key },
            "V1 IDF 反选（丢弃 IDF 最低 60%，保留前 40%）" to
                idfSorted.take((contentWords.size * 0.40).toInt()).take(LEXICON_TOP_K).map { it.key },
            "V2 纯内容词（剔除英文词频 Top-300 后取 Top-1500）" to
                contentWords.filter { it.key !in top300 }.take(LEXICON_TOP_K).map { it.key }
        )

        // 中文侧 df 只与「短语集合」有关，逐个变体各算一次（集合不同）
        val results = ArrayList<Result>()

        for ((variantName, selected) in selections) {
            // 建桥
            val wordToTerms = HashMap<String, List<String>>(4096)
            val allTerms = LinkedHashSet<String>()
            for (w in selected) {
                val cands = runCatching { meaning.chineseCandidates(w) }.getOrNull().orEmpty()
                if (cands.isEmpty()) continue
                val terms = cands.mapNotNull { cleanTerm(it) }.distinct()
                if (terms.isEmpty()) continue
                wordToTerms[w] = terms
                allTerms.addAll(terms)
            }
            // 中文 df + IDF
            val df = HashMap<String, Int>(allTerms.size * 2)
            for (s in zhSentences) for (t in allTerms) if (s.contains(t)) df.merge(t, 1, Int::plus)
            val n = zhSentences.size.coerceAtLeast(1)
            val idf = HashMap<String, Double>(allTerms.size * 2)
            for (t in allTerms) {
                val d = df[t] ?: 0
                if (d == 0) continue
                if (d > LEXICON_DF_RATIO * n && d > LEXICON_DF_MIN_ALLOWANCE) continue
                idf[t] = ln((1.0 + n) / (1.0 + d))
            }
            if (idf.isEmpty()) continue

            // 逐样本 recall
            val recalls = ArrayList<Double>(positive.size)
            var termSum = 0
            for (sample in positive) {
                val zh = TraditionalSimplified.toSimplified(sample.approvedZh)
                val ws = words(sample.en).distinct()
                val terms = LinkedHashSet<String>()
                for (w in ws) wordToTerms[w]?.forEach { terms += it }
                val usable = terms.filter { idf.containsKey(it) }
                termSum += usable.size
                var total = 0.0
                var matched = 0.0
                for (t in usable) {
                    val weight = idf[t] ?: 0.0
                    total += weight
                    if (zh.contains(t)) matched += weight
                }
                recalls += if (total > 0) matched / total else 0.0
            }
            results += Result(
                name = variantName,
                selectedWords = selected.size,
                bridgeWords = wordToTerms.size,
                distinctTerms = allTerms.size,
                keptTerms = idf.size,
                meanTermsPerSentence = termSum.toDouble() / positive.size.coerceAtLeast(1),
                recalls = recalls
            )
        }

        // ══ 汇总表 ══
        text.appendLine("## 汇总")
        text.appendLine()
        text.appendLine("| 变体 | 选出词 | 建成桥的词 | 候选短语 | 过 df 保留 | 平均每句 terms | " +
            "**recall 中位数** | 均值 | recall≥0.34 | recall=0 |")
        text.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        for (r in results) {
            text.appendLine(
                "| ${r.name} | ${r.selectedWords} | ${r.bridgeWords} | ${r.distinctTerms} | " +
                    "${r.keptTerms} | ${f(r.meanTermsPerSentence, 1)} | **${f(r.median)}** | " +
                    "${f(r.mean)} | ${r.pass}/${r.recalls.size} | ${r.zero} |"
            )
        }
        text.appendLine()
        text.appendLine("（门槛 `VERIFY_RECALL_MIN = $VERIFY_RECALL_MIN`，recall 中位数达标才算「ECDICT 可行」）")
        text.appendLine()

        // ══ 判定 ══
        text.appendLine("## 判定（按 Lead 事先约定的三档）")
        text.appendLine()
        val best = results.maxByOrNull { it.median }
        if (best != null) {
            val verdict = when {
                best.median >= 0.30 -> "**A 档**：ECDICT 可行，瓶颈在 `terms(E)` 选词 —— 修选词就能用。"
                best.median < 0.05 -> "**B 档**：ECDICT 作为句级语义桥**本质不行**（架构级结论）。"
                else -> "**C 档**：部分可行（0.05–0.3），需要更强的信号补充。"
            }
            text.appendLine("最佳变体 = `${best.name}`，recall 中位数 = **${f(best.median)}**")
            text.appendLine()
            text.appendLine("⇒ $verdict")
            text.appendLine()
            text.appendLine("注意另一组同样重要的数：**`recall ≥ $VERIFY_RECALL_MIN` 的样本数 = " +
                "${best.pass}/${best.recalls.size}**。即使中位数改善，若这一格仍是 0，")
            text.appendLine("意味着没有任何一条样本能靠 V1 单独过门——那么覆盖率仍上不去。")
            text.appendLine()
        }

        // ══ 逐变体 recall 分布 ══
        text.appendLine("## 各变体的 recall 分位")
        text.appendLine()
        text.appendLine("| 变体 | p10 | p25 | p50 | p75 | p90 | max |")
        text.appendLine("| --- | --- | --- | --- | --- | --- | --- |")
        for (r in results) {
            text.appendLine(
                "| ${r.name} | ${f(percentile(r.recalls, 0.10))} | ${f(percentile(r.recalls, 0.25))} | " +
                    "${f(percentile(r.recalls, 0.50))} | ${f(percentile(r.recalls, 0.75))} | " +
                    "${f(percentile(r.recalls, 0.90))} | ${f(r.recalls.max())} |"
            )
        }
        text.appendLine()

        // ══ 逐样本三列 ══
        text.appendLine("## 逐样本 recall（三变体并列）")
        text.appendLine()
        text.appendLine("| # | id | " + results.joinToString(" | ") { it.name.substringBefore(" ") } + " |")
        text.appendLine("| --- | --- | " + results.joinToString(" | ") { "---" } + " |")
        for (i in positive.indices) {
            text.appendLine(
                "| ${i + 1} | ${positive[i].id} | " +
                    results.joinToString(" | ") { f(it.recalls[i]) } + " |"
            )
        }
        text.appendLine()

        println(text.toString())
        File(outDir, "18-term-selection-experiment.txt").writeText(text.toString())

        assertTrue(results.size >= 2, "至少要有两个变体跑出来")
        assertTrue(positive.isNotEmpty(), "需要人工批准 SENTENCE 样本")
    }

    private fun assertTrue(condition: Boolean, message: String = "") =
        org.junit.Assert.assertTrue(message, condition)
}
