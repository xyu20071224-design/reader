package com.linguareader.app.alignment

import com.linguareader.app.tts.TtsTextExtractor
import com.linguareader.shared.tts.SentenceSplitter
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale

/**
 * **「中文句少 31%」到底是译者写得少，还是分句器切不动？**
 *
 * 这个区分决定修复方向，两个答案的修法完全相反：
 *
 * | 若原因是 | 修复方向 |
 * | --- | --- |
 * | 译者用一个中文句承载多句英文（真实语言形态） | 算法必须原生支持 `N:1`，不能靠「拆句」 |
 * | `SentenceSplitter` 在中文上切不动（引号嵌套保护过度） | 先修分句器；否则「句」这个单位本身就是错的 |
 *
 * ## 方法
 *
 * 中文句末标点（`。！？`）的**游程数**是「至少应该切出几句」的函数上界
 * （有些句末标点被嵌套引号保护，是**有意的**）。把它与 `SentenceSplitter` 实际切出的
 * 句数对比：
 *
 * - 若 `实际句数 ≈ 终止符游程数` ⇒ 分句器没问题，差 31% 是译者形态；
 * - 若 `实际句数 << 终止符游程数` ⇒ 分句器在合并，句级对齐的前提就有问题。
 */
@RunWith(RobolectricTestRunner::class)
class ChineseSplitDiagnosisTest {

    private fun isCjk(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'
    private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** 中文句末标点游程数（连续 `。！？` 算一个边界），与分句器口径一致。 */
    private fun terminatorRuns(s: String): Int {
        var runs = 0
        var inRun = false
        for (c in s) {
            if (c == '。' || c == '！' || c == '？') {
                if (!inRun) { runs++; inRun = true }
            } else inRun = false
        }
        return runs
    }

    private fun percentile(v: List<Int>, p: Double): Int {
        if (v.isEmpty()) return 0
        val s = v.sorted()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }

    @Test
    fun chineseSplittingVsTerminators() {
        val root = LotrCorpus.findRoot()
        assumeTrue("缺少 artifacts 真实语料，跳过", root != null)
        val artifacts = File(root!!, "artifacts")
        val outDir = File(System.getProperty("java.io.tmpdir"), "t5-verify").apply { mkdirs() }

        val extractor = TtsTextExtractor()
        val enBook = LotrCorpus.readEnBook(File(artifacts, "lotr-book"))
        val zhBook = LotrCorpus.readZhBook(File(artifacts, "lotr-zh"))
        val enBlocks = enBook.chapters.indices.map { extractor.chapter(enBook, it).blocks }
        val zhBlocks = zhBook.chapters.indices.map { extractor.chapter(zhBook, it).blocks }

        val zhBlocksFlat = zhBlocks.flatten()
        val zhSentences = zhBlocksFlat.flatMap { SentenceSplitter.split(it) }
        val enSentences = enBlocks.flatten().flatMap { SentenceSplitter.split(it) }

        val text = StringBuilder()
        text.appendLine("# 中文分句诊断：「中文句少 31%」是译者形态还是分句器问题")
        text.appendLine()

        // ── 1. 块级：终止符游程 vs 实际切出的句数 ──
        var blocksWithMultiSentence = 0
        var totalRuns = 0
        var totalProduced = 0
        val mergesPerBlock = ArrayList<Int>()
        val examples = ArrayList<String>()
        for (block in zhBlocksFlat) {
            val runs = terminatorRuns(block)
            val produced = SentenceSplitter.split(block).size
            totalRuns += runs
            totalProduced += produced
            if (runs > 1) blocksWithMultiSentence++
            if (runs > produced) {
                mergesPerBlock += runs - produced
                if (examples.size < 12) {
                    examples += "块「${block.take(90)}…」终止符游程=$runs 实际切=$produced"
                }
            }
        }
        text.appendLine("## 1. 块级核对")
        text.appendLine()
        text.appendLine("| 项 | 值 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| 中文叶级块数 | ${zhBlocksFlat.size} |")
        text.appendLine("| 这些块里 `。！？` 的游程总数（= 至少应切出的句数上界） | $totalRuns |")
        text.appendLine("| 实际切出的句数 | $totalProduced |")
        text.appendLine("| 比值（实际 / 游程） | ${"%.4f".format(Locale.ROOT, totalProduced.toDouble() / totalRuns.coerceAtLeast(1))} |")
        text.appendLine("| 含 >1 个游程的块 | $blocksWithMultiSentence |")
        text.appendLine("| 存在「切得比游程少」的块 | ${mergesPerBlock.size} |")
        text.appendLine("| 这些块平均少切几句 | ${"%.2f".format(Locale.ROOT, if (mergesPerBlock.isEmpty()) 0.0 else mergesPerBlock.average())} |")
        text.appendLine()
        if (mergesPerBlock.isNotEmpty()) {
            text.appendLine("### 「切得比游程少」的样例（内层引号保护导致的合并）")
            text.appendLine()
            examples.forEach { text.appendLine("- $it") }
            text.appendLine()
        }
        text.appendLine("判读：比值 ${"%.3f".format(Locale.ROOT, totalProduced.toDouble() / totalRuns.coerceAtLeast(1))}"
            + " —— 若接近 1，分句器在中文本上没有系统性欠切；")
        text.appendLine("合并只发生在引号嵌套块上（`hasUnclosedCjkQuote` 的保护，是为 TTS 正确性有意做的）。")
        text.appendLine()

        // ── 2. 已切出句子的长度分布 ──
        val zhLens = zhSentences.map { s -> s.count { isCjk(it) } }
        val enLens = enSentences.map { s ->
            var n = 0; var i = 0
            while (i < s.length) {
                if (isLetter(s[i])) {
                    n++
                    i++
                    while (i < s.length && (isLetter(s[i]) || s[i].isDigit())) i++
                } else i++
            }
            n
        }
        text.appendLine("## 2. 句子长度分布（分句器输出）")
        text.appendLine()
        text.appendLine("| 分位 | 中文（汉字/句） | 英文（词/句） |")
        text.appendLine("| --- | --- | --- |")
        for (p in listOf(0.10, 0.25, 0.50, 0.75, 0.90, 0.99)) {
            text.appendLine("| p${(p * 100).toInt()} | ${percentile(zhLens, p)} | ${percentile(enLens, p)} |")
        }
        text.appendLine("| 均值 | ${"%.2f".format(Locale.ROOT, zhLens.average())} | ${"%.2f".format(Locale.ROOT, enLens.average())} |")
        text.appendLine("| 最大 | ${zhLens.maxOrNull() ?: 0} | ${enLens.maxOrNull() ?: 0} |")
        text.appendLine()
        val longZh = zhLens.count { it >= 80 }
        text.appendLine("- 中文句 ≥80 汉字的条数 = $longZh（${"%.4f".format(Locale.ROOT, longZh.toDouble() / zhLens.size)}）")
        text.appendLine("  —— 这些是「一条中文句里塞了多句英文」的高危形态。")
        text.appendLine()

        // ── 3. 结论所需的两个数 ──
        text.appendLine("## 3. 结论")
        text.appendLine()
        text.appendLine("| 口径 | 英文 | 中文 | 中/英 |")
        text.appendLine("| --- | --- | --- | --- |")
        text.appendLine("| 句子数 | ${enSentences.size} | ${zhSentences.size} | " +
            "${"%.4f".format(Locale.ROOT, zhSentences.size.toDouble() / enSentences.size)} |")
        text.appendLine("| 终止符游程上界的句子数 | — | $totalRuns | " +
            "${"%.4f".format(Locale.ROOT, totalRuns.toDouble() / enSentences.size)} |")
        text.appendLine()
        text.appendLine("若「终止符游程上界」仍远小于英文句数，则**不是分句器的问题**，")
        text.appendLine("而是译者确实用更少的中文句承载同样的内容 —— `N:1` 是真实形态。")
        text.appendLine()
        val runsRatio = totalRuns.toDouble() / enSentences.size
        val prodRatio = zhSentences.size.toDouble() / enSentences.size
        text.appendLine("实测：分句器产出比 ${"%.3f".format(Locale.ROOT, prodRatio)}，终止符上界比 ${"%.3f".format(Locale.ROOT, runsRatio)}。")
        text.appendLine("两者差 ${"%.3f".format(Locale.ROOT, runsRatio - prodRatio)} —— 这是引号保护吃掉的部分。")
        text.appendLine("剩余 ${"%.3f".format(Locale.ROOT, 1.0 - runsRatio)} 的缺口不是分句造成的。")
        text.appendLine()

        println(text.toString())
        File(outDir, "16-chinese-split-diagnosis.txt").writeText(text.toString())

        assertTrue(zhSentences.isNotEmpty())
        assertTrue(totalRuns > 0)
    }

    private fun assertTrue(condition: Boolean, message: String = "") =
        org.junit.Assert.assertTrue(message, condition)
}
