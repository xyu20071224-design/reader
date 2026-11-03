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
 * **宽松版上界：ECDICT 信号到底存在不存在？**（Lead 指定的关键实验）
 *
 * ## 为什么需要它
 *
 * 严格版（2–4 字子串、IDF 加权）给出 recall 中位数 0.02。但那个数字可能是
 * **匹配策略卡住的**，而不是**信号本身没有的**。两者修法完全不同：
 *
 * | 若 | 则 |
 * | --- | --- |
 * | 宽松版 recall 能到 0.5+ | 信号在，被匹配策略卡住 ⇒ 继续优化匹配 |
 * | 宽松版仍 < 0.2 | ECDICT 释义与译本用词本质不同源 ⇒ V1 这条路走不通 |
 *
 * ## 五个逐级放宽的变体（同一 48 条人工批准真值配对）
 *
 * | 变体 | 命中判据 |
 * | --- | --- |
 * | L0 严格 | 2–4 字完整短语必须整体出现在译文里（= 实现口径） |
 * | L1 短语任意子串 | 短语的**任意 2 字连续子串**出现在译文里即算命中 |
 * | L2 单字命中 | 短语的**任意一个汉字**出现在译文里即算命中 |
 * | L3 义项并集 | 该词**所有**释义里的汉字集合与译文有交集即算整词命中（词级 recall） |
 * | L4 字符重叠率 | 不判命中，直接算「译文汉字集合 ∩ 该词释义汉字集合 / 该词释义汉字集合」的**重叠率**作为软分数 |
 *
 * L2/L3 已经是「几乎必然命中」的量级（常用汉字只有几千个，任何一段中文都会覆盖大量高频字），
 * 所以 **L2/L3 的绝对值很高并不证明信号有用**——它们只是给出「字面上界」。
 * 真正的判别力要看 **L1**（短语级但放宽到 2 字子串）。
 *
 * 因此本文件对每个变体都额外报 **零模型基线**：把 target 换成**随机另一条样本的译文**
 * （即配对错误的情况），看同样判据下 recall 是多少。**只有「正确配对的 recall 显著高于
 * 错误配对的 recall」，信号才存在。** 这一步是防止「宽松判据虚高」的关键。
 */
@RunWith(RobolectricTestRunner::class)
class LenientUpperBoundTest {

    private fun isCjk(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'
    private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun isDigit(c: Char) = c in '0'..'9'
    private fun isWordCont(c: Char) = isLetter(c) || isDigit(c) || c == '\'' || c == '’' || c == '-'

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

    private val META_INFO = listOf(
        "的过去式", "的过去分词", "的复数", "的第三人称单数", "的现在分词",
        "的缩写", "的简写", "的复数形式", "过去式", "复数形式"
    )

    /** 清洗：纯汉字短语，2–4 字，剔除形态学元信息。用于 L0/L1。 */
    private fun cleanTerm(candidate: String): String? {
        for (meta in META_INFO) if (candidate.contains(meta)) return null
        var cjk = 0
        for (c in candidate) {
            when {
                isCjk(c) -> cjk++
                c.isWhitespace() -> Unit
                else -> return null
            }
        }
        if (cjk < 2 || cjk > 4) return null
        return candidate.filter { isCjk(it) }
    }

    private fun f(v: Double, d: Int = 4) = String.format(Locale.ROOT, "%.${d}f", v)

    private fun percentile(v: List<Double>, p: Double): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.sorted()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }

    /** 一条样本的候选（每词：原文释义、清洗短语、全汉字集合）。 */
    private class WordTerms(
        val word: String,
        val phrases: List<String>,
        val allHanChars: Set<Char>
    )

    @Test
    fun lenientUpperBoundWithNullBaseline() {
        val root = LotrCorpus.findRoot()
        assumeTrue("缺少 artifacts 真实语料，跳过", root != null)
        val artifacts = File(root!!, "artifacts")
        val outDir = File(System.getProperty("java.io.tmpdir"), "t5-verify").apply { mkdirs() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val meaning = LotrCorpus.meaningProvider(EcdictMeaningIndex(app))

        val extractor = TtsTextExtractor()
        val zhBook = LotrCorpus.readZhBook(File(artifacts, "lotr-zh"))
        val zhBlocks = zhBook.chapters.indices.map { extractor.chapter(zhBook, it).blocks }
        val zhSentences = zhBlocks.flatten().flatMap { SentenceSplitter.split(it) }
            .map { TraditionalSimplified.toSimplified(it) }

        val samples = GoldenSamples.parse(File(artifacts, "alignment-eval/golden-samples.json"))
        val positive = samples.filter { !it.garbage && it.approvedByHuman && it.approvedLevel == "SENTENCE" }
            .filter { it.approvedZh.isNotBlank() }

        val text = StringBuilder()
        text.appendLine("# 宽松版上界：ECDICT 信号存在吗（含零模型基线）")
        text.appendLine()
        text.appendLine("样本 = 人工批准的 SENTENCE 级真值配对（n=${positive.size}）。")
        text.appendLine("target = 人工批准的正确译文（已繁→简归一）。")
        text.appendLine()

        // ── 建桥：**所有**有释义的英文实词（不排除功能词，让样本量最大）──
        val perSample = ArrayList<List<WordTerms>>(positive.size)
        var wordsQueried = 0
        var wordsWithAnyMeaning = 0
        for (sample in positive) {
            val list = ArrayList<WordTerms>()
            for (w in words(sample.en).distinct()) {
                if (w.length < 2) continue
                wordsQueried++
                val raw = runCatching { meaning.chineseCandidates(w) }.getOrNull().orEmpty()
                if (raw.isEmpty()) continue
                wordsWithAnyMeaning++
                val phrases = raw.mapNotNull { cleanTerm(it) }.distinct()
                // 所有释义里的全部汉字（含元信息释义，最宽松）
                val han = LinkedHashSet<Char>()
                for (r in raw) for (c in r) if (isCjk(c)) han.add(c)
                list += WordTerms(w, phrases, han)
            }
            perSample += list
        }

        // ── terms(E)=∅ 比例（关键诊断：Lead 说 implementer 报 80%）──
        val emptyPhrase = perSample.count { it.all { wt -> wt.phrases.isEmpty() } }
        val emptyAnyHan = perSample.count { it.isEmpty() }
        text.appendLine("## 诊断 1：`terms(E)` 为空的比例")
        text.appendLine()
        text.appendLine("| 口径 | 空的比例 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| 清洗后短语为空（无 2–4 字纯汉字短语） | $emptyPhrase / ${positive.size} = **${f(emptyPhrase.toDouble() / positive.size)}** |")
        text.appendLine("| 一个词都没有 ECDICT 释义 | $emptyAnyHan / ${positive.size} = ${f(emptyAnyHan.toDouble() / positive.size)} |")
        text.appendLine()
        text.appendLine("查询过的不同（词,样本）对 = $wordsQueried，其中 ECDICT 有释义 = $wordsWithAnyMeaning" +
            "（${f(wordsWithAnyMeaning.toDouble() / wordsQueried.coerceAtLeast(1))}）")
        text.appendLine()
        text.appendLine("判读：若「清洗后短语为空」比例很高，说明**短句+功能词为主的句子**在 2–4 字短语口径下")
        text.appendLine("天然没有可匹配项——这既是实现的问题，也是语料形态的问题。")
        text.appendLine()

        // ── 中文侧 IDF（L0/L1 的加权，与实现同式）──
        val vocabulary = LinkedHashSet<String>()
        perSample.forEach { it.forEach { wt -> vocabulary.addAll(wt.phrases) } }
        val df = HashMap<String, Int>(vocabulary.size * 2)
        for (s in zhSentences) for (t in vocabulary) if (s.contains(t)) df.merge(t, 1, Int::plus)
        val n = zhSentences.size.coerceAtLeast(1)
        val idf = HashMap<String, Double>(vocabulary.size * 2)
        for (t in vocabulary) df[t]?.let { idf[t] = ln((1.0 + n) / (1.0 + it)) }

        // ── 五个变体 ──
        class VariantScore(val name: String, val description: String)
        val variants = listOf(
            VariantScore("L0 严格（2–4字短语整体命中）", "短语必须整体出现在译文里，按 IDF 加权"),
            VariantScore("L1 短语任意2字子串", "短语的任一 2 字连续子串出现即算该短语命中，IDF 加权"),
            VariantScore("L2 单字命中", "短语里任一汉字出现即算命中，IDF 加权"),
            VariantScore("L3 词级义项并集", "该词全部释义汉字与译文有交集 ⇔ 整个词算命中（按词计）"),
            VariantScore("L4 字符重叠率", "译文汉字 ∩ 该词释义汉字 / 该词释义汉字，取均值（非命中式）")
        )

        // 逐变体逐样本打分；同时算零模型（target = 下一条样本的译文）
        fun scoreAll(variantIndex: Int, targetOf: (Int) -> String): List<Double> {
            val out = ArrayList<Double>(positive.size)
            for (i in positive.indices) {
                val zh = targetOf(i)
                val ws = perSample[i]
                when (variantIndex) {
                    0, 1, 2 -> {
                        var total = 0.0
                        var matched = 0.0
                        for (wt in ws) {
                            for (t in wt.phrases) {
                                val weight = idf[t] ?: continue
                                total += weight
                                val hit = when (variantIndex) {
                                    0 -> zh.contains(t)
                                    1 -> (0..t.length - 2).any { zh.contains(t.substring(it, it + 2)) }
                                    else -> t.any { c -> zh.indexOf(c) >= 0 }
                                }
                                if (hit) matched += weight
                            }
                        }
                        out += if (total > 0) matched / total else 0.0
                    }
                    3 -> {
                        if (ws.isEmpty()) { out += 0.0; continue }
                        val hits = ws.count { wt -> wt.allHanChars.any { c -> zh.indexOf(c) >= 0 } }
                        out += hits.toDouble() / ws.size
                    }
                    else -> {
                        if (ws.isEmpty()) { out += 0.0; continue }
                        val ratios = ws.map { wt ->
                            if (wt.allHanChars.isEmpty()) 0.0
                            else wt.allHanChars.count { c -> zh.indexOf(c) >= 0 }.toDouble() / wt.allHanChars.size
                        }
                        out += ratios.average()
                    }
                }
            }
            return out
        }

        val correctTargets = { i: Int -> TraditionalSimplified.toSimplified(positive[i].approvedZh) }
        // 零模型：把 target 换成「下一条」样本的译文（错误配对，但同样长度的中文）
        val shiftedTargets = { i: Int -> TraditionalSimplified.toSimplified(positive[(i + positive.size / 2) % positive.size].approvedZh) }

        text.appendLine("## 诊断 2：五个变体 × {真值配对, 零模型（错配）}")
        text.appendLine()
        text.appendLine("**零模型** = 把 target 换成另一条样本的译文。只有「真值配对显著高于零模型」才说明信号存在。")
        text.appendLine()
        text.appendLine("| 变体 | 真值配对 p50 | 真值 p90 | 真值 max | 零模型 p50 | 零模型 p90 | **区分度（真值p50 − 零模型p50）** |")
        text.appendLine("| --- | --- | --- | --- | --- | --- | --- |")

        val allScores = ArrayList<Pair<String, Pair<List<Double>, List<Double>>>>()
        for ((vi, v) in variants.withIndex()) {
            val real = scoreAll(vi, correctTargets)
            val nulls = scoreAll(vi, shiftedTargets)
            allScores += v.name to (real to nulls)
            val sep = percentile(real, 0.50) - percentile(nulls, 0.50)
            text.appendLine(
                "| ${v.name} | ${f(percentile(real, 0.50))} | ${f(percentile(real, 0.90))} | " +
                    "${f(real.max())} | ${f(percentile(nulls, 0.50))} | ${f(percentile(nulls, 0.90))} | " +
                    "**${if (sep >= 0) "+" else ""}${f(sep)}** |"
            )
        }
        text.appendLine()
        for (v in variants) text.appendLine("- `${v.name}`：${v.description}")
        text.appendLine()

        // ── 判定 ──
        val l1Real = allScores[1].second.first
        val l1Null = allScores[1].second.second
        val l1Median = percentile(l1Real, 0.50)
        val l1Sep = l1Median - percentile(l1Null, 0.50)
        val l0Real = allScores[0].second.first
        text.appendLine("## 结论")
        text.appendLine()
        text.appendLine("**判据（Lead 指定）看 L1（短语级但放宽到 2 字子串），不看 L2/L3**——")
        text.appendLine("L2（单字）与 L3（词级义项并集）几乎必然命中：常用汉字只有几千个，")
        text.appendLine("任何一段中文都会覆盖大量高频字，所以它们的绝对值高**不构成信号存在的证据**。")
        text.appendLine()
        text.appendLine("| 关键数 | 值 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| L0 严格 recall 中位数（= 实现口径） | ${f(percentile(l0Real, 0.50))} |")
        text.appendLine("| **L1 宽松 recall 中位数** | **${f(l1Median)}** |")
        text.appendLine("| L1 零模型 recall 中位数 | ${f(percentile(l1Null, 0.50))} |")
        text.appendLine("| **L1 区分度（真值 − 零模型）** | **${if (l1Sep >= 0) "+" else ""}${f(l1Sep)}** |")
        text.appendLine()
        val verdict = when {
            l1Median >= 0.5 && l1Sep > 0.10 ->
                "**信号存在**：放宽到 2 字子串后 recall 达 ${f(l1Median)}，且明显高于零模型。匹配策略有优化空间。"
            l1Median < 0.2 || l1Sep <= 0.05 ->
                "**信号不足**：即使放宽到 2 字子串，recall 中位数 ${f(l1Median)}，且与零模型区分度仅 ${f(l1Sep)}。" +
                    "说明 ECDICT 释义与译本用词**本质不同源**，这条路走不通。"
            else ->
                "**部分可行**：L1 中位数 ${f(l1Median)}，区分度 ${f(l1Sep)}。信号弱但非零。"
        }
        text.appendLine("⇒ $verdict")
        text.appendLine()
        text.appendLine("### 为什么「区分度」比绝对值更重要（方法论说明）")
        text.appendLine()
        text.appendLine("L2/L3 这类宽松判据下，**错误配对也能得到很高的分数**（因为常用字到处都有）。")
        text.appendLine("如果只看绝对值，会误以为「信号很强」；只有与零模型对比，才知道这个分数里")
        text.appendLine("有多少是「真的对上了」、多少是「中文常用字碰巧重叠」。")
        text.appendLine()
        text.appendLine("⚠️ 这也直接解释了为什么**放宽 `VERIFY_RECALL_MIN` 是危险的**：")
        text.appendLine("低门槛下错误配对同样能过门，错标（B）会从 0 变成非 0。")
        text.appendLine()

        // ── 逐样本（L0/L1 两列）──
        text.appendLine("## 逐样本（L0 严格 vs L1 宽松，真值配对）")
        text.appendLine()
        text.appendLine("| # | id | L0 严格 | L1 宽松 |")
        text.appendLine("| --- | --- | --- | --- |")
        for (i in positive.indices) {
            text.appendLine("| ${i + 1} | ${positive[i].id} | ${f(allScores[0].second.first[i])} | ${f(l1Real[i])} |")
        }
        text.appendLine()

        println(text.toString())
        File(outDir, "21-lenient-upper-bound.txt").writeText(text.toString())

        assertTrue(positive.isNotEmpty(), "需要人工批准 SENTENCE 样本")
    }

    private fun assertTrue(condition: Boolean, message: String = "") =
        org.junit.Assert.assertTrue(message, condition)
}
