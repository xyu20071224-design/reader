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
 * **ECDICT 能力上限实验（最有利于 V1 的配置）**——这是决定项目方向的数字。
 *
 * ## 与前一版的区别（这是关键）
 *
 * 前一版复刻了实现的若干"减法"（功能词表、df 过滤、封顶……），测出来的是
 * **「这个实现有多差」**。本文件反过来：**把所有能帮 V1 的手段都开满**，测它的**上限**。
 *
 * | 手段 | 本文件的配置（最有利） |
 * | --- | --- |
 * | `terms(E)` 选词 | **只用内容词**：剔除「df > 2% 英文句数」的高频词（数据驱动，不靠手工表） |
 * | 词条来源 | 英文句的**全部**内容词都查 ECDICT，**不限 Top-K**（Top-K 是我加的人为限制） |
 * | 匹配粒度 | **只用 2–4 字子串**（不用单字，单字容易虚高） |
 * | 元信息词条 | 剔除释义含「的过去式/的复数/的第三人称单数/的现在分词」等纯形态学元信息 |
 * | 打分 | 标准 IDF 加权 recall，**不加封顶、不加折扣** |
 * | target | **人工批准的正确译文**（把 DP 错误完全排除） |
 * | 繁简 | 已归一（对 V1 有利） |
 *
 * 如果**这个配置**下 recall 中位数仍 < 0.05，那「ECDICT 作为句级语义桥能力不足」
 * 就是**架构级结论**，不是实现问题。
 *
 * ## 附带：三个诊断阈值（帮 Lead 判断中间档）
 *
 * 除 recall 外还报：
 * - **`termHitRate`**：这些句子的候选短语里，在译文中**字面命中**的比例（不看 IDF）
 * - **`wouldPassAt(x)`**：若把门槛降到 x，有多少样本能过（看放宽门槛的代价）
 */
@RunWith(RobolectricTestRunner::class)
class EcdictCeilingBestCaseTest {

    private val TERM_MIN_CHARS = 2
    private val TERM_MAX_CHARS = 4
    private val VERIFY_RECALL_MIN = 0.34

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

    /** 纯形态学元信息释义（对句子语义无贡献），剔除。 */
    private val META_INFO = listOf(
        "的过去式", "的过去分词", "的复数", "的第三人称单数", "的现在分词",
        "的缩写", "的简写", "的复数形式", "过去式", "复数形式"
    )

    private fun isMetaInfoPhrase(phrase: String): Boolean = false // 短语级不判，见下

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
        if (cjk < TERM_MIN_CHARS || cjk > TERM_MAX_CHARS) return null
        return candidate.filter { isCjk(it) }
    }

    private fun f(v: Double, d: Int = 4) = String.format(Locale.ROOT, "%.${d}f", v)

    private fun percentile(v: List<Double>, p: Double): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.sorted()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }

    @Test
    fun ecdictBestCaseRecallCeiling() {
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

        val text = StringBuilder()
        text.appendLine("# ECDICT 能力上限（最有利于 V1 的配置，不对实现做任何复刻）")
        text.appendLine()
        text.appendLine("## 配置（每一条都是「帮 V1」而非「复刻实现」）")
        text.appendLine()
        text.appendLine("| 项 | 本实验 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| 选词 | 英文侧 df ≤ 2% 总句数的词（数据驱动的内容词判据） |")
        text.appendLine("| 词表规模 | **不限 Top-K**（该句有任何内容词就查） |")
        text.appendLine("| 匹配粒度 | 2–4 字子串，**不用单字** |")
        text.appendLine("| 元信息词条 | 剔除含「的过去式/的复数/的第三人称单数…」等形态学释义 |")
        text.appendLine("| 打分 | 标准 IDF 加权 recall，**无封顶、无折扣** |")
        text.appendLine("| 繁简 | 已归一（有利于命中） |")
        text.appendLine("| target | **人工批准的正确译文** |")
        text.appendLine()

        // ── 英文 df，用于内容词判据 ──
        val enDf = HashMap<String, Int>(32768)
        for (s in enSentences) for (w in words(s).distinct()) enDf.merge(w, 1, Int::plus)
        val enN = enSentences.size
        val dfThreshold = (enN * 0.02).toInt()
        val contentWordSet = enDf.filterValues { it <= dfThreshold }.keys

        text.appendLine("英文句数 = $enN，内容词判据 = 英文 df ≤ $dfThreshold（2%）。")
        text.appendLine("符合判据的不同词 = ${contentWordSet.size} / ${enDf.size}" +
            "（${f(contentWordSet.size.toDouble() / enDf.size)}）")
        text.appendLine()
        // 被这个判据剔除的高频词（即"功能词"的数据驱动版本）
        val dropped = enDf.entries.sortedByDescending { it.value }.filter { it.value > dfThreshold }
        text.appendLine("### 被「df > 2%」判据剔除的高频词 TOP 30（数据驱动，不是手工表）")
        text.appendLine()
        text.appendLine("| # | 词 | 英文句数 | 占全书 |")
        text.appendLine("| --- | --- | --- | --- |")
        dropped.take(30).forEachIndexed { i, e ->
            text.appendLine("| ${i + 1} | `${e.key}` | ${e.value} | ${f(e.value.toDouble() / enN)} |")
        }
        text.appendLine()
        text.appendLine("共剔除 ${dropped.size} 个不同词。")
        text.appendLine()

        // ── 建桥：只对样本里出现的内容词查词典（最省且最有利）──
        val samples = GoldenSamples.parse(File(artifacts, "alignment-eval/golden-samples.json"))
        val positive = samples.filter { !it.garbage && it.approvedByHuman && it.approvedLevel == "SENTENCE" }
            .filter { it.approvedZh.isNotBlank() }

        // 收集所有样本里出现的内容词
        val probeWords = LinkedHashSet<String>()
        for (s in positive) for (w in words(s.en)) if (w in contentWordSet && w.length >= 2) probeWords.add(w)

        text.appendLine("## 建桥（只对样本句里出现的内容词查 ECDICT）")
        text.appendLine()
        val wordToTerms = HashMap<String, List<String>>(probeWords.size * 2)
        val allTerms = LinkedHashSet<String>()
        var queried = 0
        var withTerms = 0
        var metaDropped = 0
        for (w in probeWords) {
            queried++
            val cands = runCatching { meaning.chineseCandidates(w) }.getOrNull().orEmpty()
            val terms = ArrayList<String>(cands.size)
            for (c in cands) {
                val cleaned = cleanTerm(c)
                if (cleaned == null) {
                    if (META_INFO.any { m -> c.contains(m) }) metaDropped++
                    continue
                }
                terms += cleaned
            }
            if (terms.isEmpty()) continue
            withTerms++
            wordToTerms[w] = terms.distinct()
            allTerms.addAll(terms)
        }
        text.appendLine("| 项 | 值 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| 查询的内容词 | $queried |")
        text.appendLine("| ECDICT 有候选 | $withTerms（${f(withTerms.toDouble() / queried.coerceAtLeast(1))}） |")
        text.appendLine("| 被剔为形态学元信息的词条 | $metaDropped |")
        text.appendLine("| 不同候选短语 | ${allTerms.size} |")
        text.appendLine()

        // ── 中文侧 IDF（不限 df 过滤，最有利）──
        val zhDf = HashMap<String, Int>(allTerms.size * 2)
        for (s in zhSentences) for (t in allTerms) if (s.contains(t)) zhDf.merge(t, 1, Int::plus)
        val n = zhSentences.size.coerceAtLeast(1)
        val idf = HashMap<String, Double>(allTerms.size * 2)
        for (t in allTerms) {
            val d = zhDf[t] ?: 0
            if (d == 0) continue
            idf[t] = ln((1.0 + n) / (1.0 + d))
        }
        text.appendLine("中文句数 n = $n；有 df > 0 的短语 = ${idf.size}（**不做 df 上限过滤**，最有利）")
        text.appendLine()

        // ── 逐样本 ──
        text.appendLine("## 逐样本（最有利配置下的 recall）")
        text.appendLine()
        text.appendLine("| # | id | 内容词 | 候选短语 | 命中数 | 字面命中率 | **recall** | ≥0.34? |")
        text.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |")
        val recalls = ArrayList<Double>(positive.size)
        val hitRates = ArrayList<Double>(positive.size)
        val details = ArrayList<String>(positive.size)
        var pass = 0
        var zero = 0
        for ((index, sample) in positive.withIndex()) {
            val zh = TraditionalSimplified.toSimplified(sample.approvedZh)
            val ws = words(sample.en).distinct().filter { it in contentWordSet }
            val terms = LinkedHashSet<String>()
            for (w in ws) wordToTerms[w]?.forEach { terms += it }
            val usable = terms.filter { idf.containsKey(it) }
            var total = 0.0
            var matched = 0.0
            var hits = 0
            for (t in usable) {
                val weight = idf[t] ?: 0.0
                total += weight
                if (zh.contains(t)) {
                    matched += weight
                    hits++
                }
            }
            val recall = if (total > 0) matched / total else 0.0
            val hitRate = if (usable.isEmpty()) 0.0 else hits.toDouble() / usable.size
            recalls += recall
            hitRates += hitRate
            if (recall >= VERIFY_RECALL_MIN) pass++
            if (recall == 0.0) zero++
            details += "${sample.id}\t${ws.size}\t${usable.size}\t$hits\t${f(hitRate)}\t${f(recall)}"
            text.appendLine(
                "| ${index + 1} | ${sample.id} | ${ws.size} | ${usable.size} | $hits | " +
                    "${f(hitRate)} | **${f(recall)}** | ${if (recall >= VERIFY_RECALL_MIN) "✅" else "❌"} |"
            )
        }
        text.appendLine()

        // ── 汇总与判定 ──
        val median = percentile(recalls, 0.50)
        val verdict = when {
            median >= 0.30 -> "**A 档：ECDICT 可行**，瓶颈在 `terms(E)` 选词（修选词就能用）"
            median < 0.05 -> "**B 档：ECDICT 作为句级语义桥能力不足**（架构级结论）"
            else -> "**C 档：部分可行**（0.05–0.3），需要更强的信号补充"
        }
        text.appendLine("## 结论（Lead 事先约定的三档判定）")
        text.appendLine()
        text.appendLine("| 指标 | 值 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| 样本数 | ${positive.size} |")
        text.appendLine("| **recall 中位数（主判据）** | **${f(median)}** |")
        text.appendLine("| recall 均值 | ${f(recalls.average())} |")
        text.appendLine("| recall p90 | ${f(percentile(recalls, 0.90))} |")
        text.appendLine("| recall 最大值 | ${f(recalls.max())} |")
        text.appendLine("| recall = 0 的样本 | $zero（${f(zero.toDouble() / positive.size)}） |")
        text.appendLine("| **recall ≥ $VERIFY_RECALL_MIN** | **$pass / ${positive.size}** |")
        text.appendLine("| 字面命中率中位数（不看 IDF） | ${f(percentile(hitRates, 0.50))} |")
        text.appendLine()
        text.appendLine("⇒ $verdict")
        text.appendLine()
        text.appendLine("### 中间档诊断：把门槛放宽到多少才有样本能过")
        text.appendLine()
        text.appendLine("| 门槛 | 能过的样本数 | 占比 |")
        text.appendLine("| --- | --- | --- |")
        for (bar in listOf(0.34, 0.25, 0.20, 0.15, 0.10, 0.05, 0.02, 0.01)) {
            val c = recalls.count { it >= bar }
            text.appendLine("| $bar | $c / ${positive.size} | ${f(c.toDouble() / positive.size)} |")
        }
        text.appendLine()
        text.appendLine("⚠️ 放宽门槛的代价：V1 是 `decide()` 批准 ALIGNED 的主要通道之一。")
        text.appendLine("把门槛降到 recall 中位数附近，等于让「本来零证据的对」也能通过验证——")
        text.appendLine("那会把错标（B）从 0 变成非 0，直接破 `MAX_FALSE_ALIGNED = 0` 红线。")
        text.appendLine()
        text.appendLine("### 逐样本原始数（供 Lead 复核）")
        text.appendLine()
        text.appendLine("```")
        text.appendLine("id\t内容词\t候选短语\t命中\t字面命中率\trecall")
        details.forEach { text.appendLine(it) }
        text.appendLine("```")

        println(text.toString())
        File(outDir, "19-ecdict-ceiling-best-case.txt").writeText(text.toString())

        assertTrue(positive.isNotEmpty(), "需要人工批准 SENTENCE 样本")
        assertTrue(allTerms.isNotEmpty(), "ECDICT 桥不能为空")
    }

    private fun assertTrue(condition: Boolean, message: String = "") =
        org.junit.Assert.assertTrue(message, condition)
}
