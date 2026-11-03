package com.linguareader.app.alignment

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.translation.EcdictMeaningIndex
import com.linguareader.shared.translation.TraditionalSimplified
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale

/**
 * **人工可核对的逐词展开**：为什么 recall 这么低。
 *
 * 上面的实验只给了数字。这个测试把几条样本**完整展开**：每个英文内容词的
 * ECDICT 候选短语、哪些在译文里命中、为什么没命中。这样 Lead 能自己判断
 * 「是 ECDICT 释义不行」还是「我的匹配太严」。
 *
 * 这是防止我自己测错的最后一道关：如果这里看到的候选短语明明合理、译文里也确实
 * 出现了近义表达，只是字面不同，那么结论「ECDICT 作为**字面**桥不够用」成立，
 * 且能反证「不是测量 bug」。
 */
@RunWith(RobolectricTestRunner::class)
class RecallCaseExpansionTest {

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

    @Test
    fun expandSpecificCases() {
        val root = LotrCorpus.findRoot()
        assumeTrue("缺少 artifacts 真实语料，跳过", root != null)
        val artifacts = File(root!!, "artifacts")
        val outDir = File(System.getProperty("java.io.tmpdir"), "t5-verify").apply { mkdirs() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val meaning = LotrCorpus.meaningProvider(EcdictMeaningIndex(app))

        val samples = GoldenSamples.parse(File(artifacts, "alignment-eval/golden-samples.json"))
        val positive = samples.filter { !it.garbage && it.approvedByHuman && it.approvedLevel == "SENTENCE" }
            .filter { it.approvedZh.isNotBlank() }

        // 挑 6 条：长句/短句、命中多的/完全零命中的
        val chosen = positive.take(6)

        val text = StringBuilder()
        text.appendLine("# recall 低的具体成因（逐词展开，人工可核对）")
        text.appendLine()
        text.appendLine("对每条样本：列出英文内容词的 **ECDICT 原始释义** 与清洗后的候选短语，")
        text.appendLine("再逐个标记「该短语是否字面出现在人工批准的译文里」。")
        text.appendLine()
        text.appendLine("**读法**：若某个词的释义明明是对的（如 `concern` → `关心/关切`），")
        text.appendLine("而译文用了同义但不同形的说法（`滿懷關切`），那就是**字面桥**的固有限制，")
        text.appendLine("不是测量 bug；反之若释义本身荒谬，那是词典质量问题。")
        text.appendLine()

        for ((idx, sample) in chosen.withIndex()) {
            val zhRaw = sample.approvedZh
            val zh = TraditionalSimplified.toSimplified(zhRaw)
            text.appendLine("---")
            text.appendLine("## 样本 ${idx + 1}: `${sample.id}`（人工判定 ${sample.verdict}）")
            text.appendLine()
            text.appendLine("**英文**：${sample.en}")
            text.appendLine()
            text.appendLine("**人工批准译文（原文，繁体）**：${zhRaw.take(160)}")
            if (zh != zhRaw) {
                text.appendLine()
                text.appendLine("**归一后（V1 实际匹配的文本）**：${zh.take(160)}")
            }
            text.appendLine()
            text.appendLine("| 英文词 | ECDICT 原始释义（截断） | 清洗后候选短语 | 命中的 |")
            text.appendLine("| --- | --- | --- | --- |")
            var totalTerms = 0
            var totalHits = 0
            for (w in words(sample.en).distinct()) {
                if (w.length < 2) continue
                val raw = runCatching { meaning.chineseCandidates(w) }.getOrNull().orEmpty()
                if (raw.isEmpty()) {
                    text.appendLine("| `$w` | （词典无此词） | — | — |")
                    continue
                }
                val terms = raw.mapNotNull { cleanTerm(it) }.distinct()
                if (terms.isEmpty()) {
                    text.appendLine("| `$w` | `${raw.take(3).joinToString("/")}` | （清洗后为空） | — |")
                    continue
                }
                val hits = terms.filter { zh.contains(it) }
                totalTerms += terms.size
                totalHits += hits.size
                val rawPreview = raw.take(3).joinToString(" / ").replace("|", "\\|").take(70)
                text.appendLine(
                    "| `$w` | $rawPreview | ${terms.take(8).joinToString("/")}" +
                        "${if (terms.size > 8) "…(${terms.size})" else ""} | " +
                        "${if (hits.isEmpty()) "**0**" else hits.joinToString("/")} |"
                )
            }
            text.appendLine()
            text.appendLine("该样本：候选短语合计 $totalTerms，字面命中 $totalHits" +
                "（${String.format(Locale.ROOT, "%.4f", if (totalTerms > 0) totalHits.toDouble() / totalTerms else 0.0)}）")
            text.appendLine()
        }

        text.appendLine("---")
        text.appendLine()
        text.appendLine("## 观察小结")
        text.appendLine()
        text.appendLine("请对照上表自行判断。两种解释都能从表里读出来：")
        text.appendLine()
        text.appendLine("1. **同义不同形**：如 `concern` → 词典给 `关心/挂念`，译文写 `滿懷關切`——")
        text.appendLine("   语义对，字面不对。这是**字面桥**的固有上限，换更强的选词也修不掉。")
        text.appendLine("2. **释义是查词义项，不是句子语境义**：ECDICT 给一个词列所有义项，")
        text.appendLine("   句中真正用到的那个义项与其它义项混在一起，IDF 分母被稀释。")
        text.appendLine()

        println(text.toString())
        File(outDir, "20-recall-case-expansion.txt").writeText(text.toString())

        assertTrue(chosen.isNotEmpty())
    }

    private fun assertTrue(condition: Boolean, message: String = "") =
        org.junit.Assert.assertTrue(message, condition)
}
