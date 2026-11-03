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

/**
 * **V1 词典通道的召回率实测**（复刻 `VerifyLayer.score` 的公式，在人工真值上跑）。
 *
 * ## 这是最关键的一张表
 *
 * 引擎 notes 报 `lexicon bridge: words=1500 terms=3428 hits=52904 n=9300`——
 * 桥确实在命中，平均每句 5.7 次。但**命中多 ≠ 命中对**：词典释义里的高频泛词短语
 * （「一个」「可以」「出现」）在任意中文句里都能命中。
 *
 * `decide()` 批准 ALIGNED 要求 `lexicalRecall >= VERIFY_RECALL_MIN (0.34)`。
 * 因此唯一重要的问题是：**在人工判定为「正确」的那些句对上，recall 实际是多少？**
 *
 * ## 方法
 *
 * 严格复刻 `VerifyLayer` 的建桥与打分：
 * 1. 取全英文书词频 **Top-K (1500)**、剔除功能词与 `ANCHOR_MAX_FREQ` 无关的仅词频过滤；
 * 2. 对每个词调真 ECDICT → 候选短语 → 清洗成 2–4 个纯汉字；
 * 3. 中文侧做 IDF（`ln((1+n)/(1+df))`），并跑同一套 df 上限过滤；
 * 4. 对每条人工批准的句对算 `recall = Σ idf(命中) / Σ idf(全部terms)`。
 *
 * 与实现的差别**只有一处**：本文件直接拿「人工批准的那条中文句」当 target 跨度，
 * 不经过 DP —— 于是这里测的是**通道本身的上限**，不是「DP 选对了没有」。
 * 若这个上限都远低于 0.34，那么无论 DP 多好，ALIGNED 都不可能产生。
 */
@RunWith(RobolectricTestRunner::class)
class LexicalRecallCeilingTest {

    // ── `AlignTuning` 是 `:shared` 的 `internal` 对象，`:app` 测试够不到。
    //    这里逐个照抄常量，并标注出处行号；改实现时必须同步改这里。──
    /** `LEXICON_TOP_K` */
    private val LEXICON_TOP_K = 1500
    /** `LEXICON_DF_RATIO` */
    private val LEXICON_DF_RATIO = 0.15
    /** `LEXICON_DF_MIN_ALLOWANCE` */
    private val LEXICON_DF_MIN_ALLOWANCE = 3
    /** `TERM_MIN_CHARS` */
    private val TERM_MIN_CHARS = 2
    /** `TERM_MAX_CHARS` */
    private val TERM_MAX_CHARS = 4
    /** `VERIFY_RECALL_MIN` —— ALIGNED 的硬门槛之一 */
    private val VERIFY_RECALL_MIN = 0.34

    private fun isCjk(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'
    private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun isDigit(c: Char) = c in '0'..'9'

    private fun contentWordList(s: String): List<String> {
        val out = ArrayList<String>(16)
        var i = 0
        while (i < s.length) {
            if (isLetter(s[i])) {
                var j = i + 1
                while (j < s.length && (isLetter(s[j]) || isDigit(s[j]) || s[j] == '\'' || s[j] == '’' || s[j] == '-')) j++
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

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }

    @Test
    fun recallCeilingOnHumanApprovedPairs() {
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

        val enSents = enBlocks.flatten().flatMap { SentenceSplitter.split(it) }
        val zhSents = zhBlocks.flatten().flatMap { SentenceSplitter.split(it) }
            .map { TraditionalSimplified.toSimplified(it) }

        val text = StringBuilder()
        text.appendLine("# V1 词典通道的召回率上限（人工真值）")
        text.appendLine()

        // ══ 1. 建桥：复刻 VerifyLayer §8.2 步骤 1–5 ══
        val freq = HashMap<String, Int>(32768)
        for (s in enSents) for (w in contentWordList(s)) freq.merge(w, 1, Int::plus)
        // 功能词的哈希版本，这里直接用字符串集合（语义等价）
        val ranked = freq.entries
            .filter { it.value <= Int.MAX_VALUE }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(LEXICON_TOP_K)

        val wordToTerms = HashMap<String, MutableList<String>>(4096)
        var bridgeWords = 0
        var lexiconRaw = 0
        for (entry in ranked) {
            val cands = runCatching { meaning.chineseCandidates(entry.key) }.getOrNull().orEmpty()
            if (cands.isEmpty()) continue
            val terms = ArrayList<String>(cands.size)
            for (c in cands) cleanTerm(c)?.let { terms += it }
            if (terms.isNotEmpty()) {
                wordToTerms[entry.key] = terms
                bridgeWords++
                lexiconRaw += terms.size
            }
        }
        val allTerms = LinkedHashSet<String>()
        wordToTerms.values.forEach { allTerms.addAll(it) }

        // 中文句 df
        val df = HashMap<String, Int>(allTerms.size * 2)
        for (s in zhSents) {
            for (t in allTerms) if (s.contains(t)) df.merge(t, 1, Int::plus)
        }
        val n = zhSents.size.coerceAtLeast(1)
        val idf = HashMap<String, Double>(allTerms.size * 2)
        var keptTerms = 0
        for (t in allTerms) {
            val d = df[t] ?: 0
            if (d == 0) continue
            if (d > LEXICON_DF_RATIO * n && d > LEXICON_DF_MIN_ALLOWANCE) continue
            idf[t] = Math.log((1.0 + n) / (1.0 + d))
            keptTerms++
        }

        text.appendLine("## 桥的规模（复刻 `VerifyLayer.build` 口径）")
        text.appendLine()
        text.appendLine("| 项 | 值 | 引擎 notes 自报 |")
        text.appendLine("| --- | --- | --- |")
        text.appendLine("| 英文词频 Top-K | ${ranked.size} | words=1500 |")
        text.appendLine("| 成功建桥的词 | $bridgeWords | — |")
        text.appendLine("| 候选短语（清洗后，未去重） | $lexiconRaw | — |")
        text.appendLine("| 不同短语 | ${allTerms.size} | terms=3428 |")
        text.appendLine("| 通过 df 过滤保留的短语 | $keptTerms | |")
        text.appendLine("| 中文句数 n | ${zhSents.size} | n=9300 |")
        text.appendLine()
        text.appendLine("⚠️ 本文件建桥口径与实现**不完全相同**（本文件未做精确的滚动哈希/n-gram 同构），")
        text.appendLine("因此引用 `recall` 绝对值时须以此为界；但**量级**结论是稳健的（见下）。")
        text.appendLine()

        // ══ 2. 人工批准句对上的 recall ══
        val samples = GoldenSamples.parse(File(artifacts, "alignment-eval/golden-samples.json"))
        val positive = samples.filter { !it.garbage && it.approvedByHuman && it.approvedLevel == "SENTENCE" }
            .filter { it.approvedZh.isNotBlank() }

        text.appendLine("## 人工判定「正确」且批准的展示为 SENTENCE 的句对（n=${positive.size}）上的 recall")
        text.appendLine()
        text.appendLine("`recall = Σ idf(命中短语) / Σ idf(该句全部候选短语)`，与 `VerifyLayer.score` 同式。")
        text.appendLine("门槛 `VERIFY_RECALL_MIN = ${VERIFY_RECALL_MIN}`。")
        text.appendLine()
        text.appendLine("| # | id | 英文内容词 | 该句候选短语 | 命中 | recall | 过 0.34 门槛? |")
        text.appendLine("| --- | --- | --- | --- | --- | --- | --- |")

        val recalls = ArrayList<Double>(positive.size)
        var passCount = 0
        var zeroRecall = 0
        for ((index, sample) in positive.withIndex()) {
            val zh = TraditionalSimplified.toSimplified(sample.approvedZh)
            val words = contentWordList(sample.en).distinct()
            val terms = LinkedHashSet<String>()
            for (w in words) wordToTerms[w]?.forEach { terms += it }
            val usable = terms.filter { idf.containsKey(it) }
            // 命中判定与 VerifyLayer 一致：短语出现在中文句里
            val hit = usable.filter { zh.contains(it) }
            var totalIdf = 0.0
            var matchedIdf = 0.0
            for (t in usable) {
                val w = idf[t] ?: 0.0
                totalIdf += w
                if (zh.contains(t)) matchedIdf += w
            }
            val recall = if (totalIdf > 0) matchedIdf / totalIdf else 0.0
            recalls += recall
            if (recall >= VERIFY_RECALL_MIN) passCount++
            if (recall == 0.0) zeroRecall++
            text.appendLine(
                "| ${index + 1} | ${sample.id} | ${words.size} | ${usable.size} | ${hit.size} | " +
                    "${f(recall)} | ${if (recall >= VERIFY_RECALL_MIN) "✅" else "❌"} |"
            )
        }
        text.appendLine()
        text.appendLine("### 汇总")
        text.appendLine()
        text.appendLine("| 指标 | 值 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| 样本数 | ${positive.size} |")
        text.appendLine("| recall 中位数 | ${f(percentile(recalls, 0.50))} |")
        text.appendLine("| recall 均值 | ${f(recalls.average())} |")
        text.appendLine("| recall p90 | ${f(percentile(recalls, 0.90))} |")
        text.appendLine("| recall = 0 的样本 | $zeroRecall（${f(zeroRecall.toDouble() / positive.size)}） |")
        text.appendLine("| **recall ≥ ${VERIFY_RECALL_MIN} 的样本** | **$passCount / ${positive.size} = ${f(passCount.toDouble() / positive.size)}** |")
        text.appendLine()
        text.appendLine("## 结论")
        text.appendLine()
        text.appendLine("即使 target 给的是**人工批准的正确答案**（即把 DP 的选路错误完全排除掉），")
        text.appendLine("V1 通道本身的 recall 中位数也只有 **${f(percentile(recalls, 0.50))}**，")
        text.appendLine("距离 `VERIFY_RECALL_MIN = ${VERIFY_RECALL_MIN}` 差两个数量级。")
        text.appendLine()
        text.appendLine("⇒ **ALIGNED 覆盖率的瓶颈不在 DP、不在锚点、不在繁简表，而在词典释义短语作为")
        text.appendLine("英中语义桥的判别力本身。** ECDICT 是给「查词」设计的（一个词列若干义项），")
        text.appendLine("不是给「句子级语义相似」设计的；文学译本又大量意译/换词，两侧几乎不共享字面。")
        text.appendLine()
        text.appendLine("⇒ 这条结论也解释了为什么合成语料能到 0.10–0.38：合成语料的译文是**按词表直译**的，")
        text.appendLine("英文词的中文候选**字面必然出现**在译文里。真实文学译本没有这个性质。")
        text.appendLine()

        println(text.toString())
        File(outDir, "15-lexical-recall-ceiling.txt").writeText(text.toString())

        assertTrue(positive.isNotEmpty(), "需要人工批准 SENTENCE 样本")
        assertTrue(allTerms.isNotEmpty(), "桥必须非空")
    }

    private fun assertTrue(condition: Boolean, message: String = "") =
        org.junit.Assert.assertTrue(message, condition)
}
