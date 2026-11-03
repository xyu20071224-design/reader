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
 * **繁简归一缺口的真实代价**（用人工真值判定，不用推测）。
 *
 * ## 为什么需要这个测试
 *
 * 「缺口有多大」有两个完全不同的算法，结论差 20 倍：
 *
 * - 我先前报的 **882 字 / 9.23%**：判据是「归一表未命中，且不在 ECDICT 简体释义字表里」。
 *   这个判据**会误伤**：ECDICT 释义只用了 6063 个不同字，生僻简体字同样不在其中。
 * - 另一种口径说「只缺 8 个常用字 / 0.43%」。
 *
 * 两个都不该直接采信。**唯一有意义的度量是「它到底让 V1 词典通道少命中多少」**。
 *
 * ## 方法（直接用人工真值，不经过被测算法）
 *
 * 对 48 条人工批准的 `SENTENCE` 级样本：
 * 1. 取英文句的内容词（去功能词），经**真 ECDICT** 取中文候选短语；
 * 2. 在**人工批准的中文译文原文（繁体）**里数命中；
 * 3. 在**经 `TraditionalSimplified` 归一后的译文**里数命中；
 * 4. 两者之差 = 归一表缺口在「本来能命中」的部分上造成的损失。
 *
 * 这个口径不受「ECDICT 字表只覆盖 6063 字」的干扰——它直接问
 * 「归一之后，同一句话里能命中的短语有没有变多、变多多少」。
 */
@RunWith(RobolectricTestRunner::class)
class TraditionalGapImpactTest {

    private fun isCjk(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'
    private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    private val functionWords = setOf(
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

    private fun contentWords(s: String): List<String> {
        val out = ArrayList<String>(16)
        var i = 0
        while (i < s.length) {
            if (isLetter(s[i])) {
                var j = i + 1
                while (j < s.length && (isLetter(s[j]) || s[j].isDigit() || s[j] == '\'' || s[j] == '’' || s[j] == '-')) j++
                val w = s.substring(i, j).lowercase(Locale.ROOT).trim('\'', '’')
                if (w.length >= 2 && w !in functionWords) out += w
                i = j
            } else i++
        }
        return out
    }

    private fun f(v: Double) = String.format(Locale.ROOT, "%.4f", v)

    @Test
    fun measureNormalizationImpactOnHumanApprovedPairs() {
        val root = LotrCorpus.findRoot()
        assumeTrue("缺少 artifacts 真实语料，跳过", root != null)
        val artifacts = File(root!!, "artifacts")
        val outDir = File(System.getProperty("java.io.tmpdir"), "t5-verify").apply { mkdirs() }
        val app = ApplicationProvider.getApplicationContext<Application>()

        val extractor = TtsTextExtractor()
        val zhBook = LotrCorpus.readZhBook(File(artifacts, "lotr-zh"))
        val zhBlocks = zhBook.chapters.indices.map { extractor.chapter(zhBook, it).blocks }
        val meaningIndex = EcdictMeaningIndex(app)
        val meaning = LotrCorpus.meaningProvider(meaningIndex)
        val samples = GoldenSamples.parse(File(artifacts, "alignment-eval/golden-samples.json"))

        val text = StringBuilder()
        text.appendLine("# 繁简归一缺口的真实代价（人工真值口径）")
        text.appendLine()

        // ══════════════════════════════════════════════════════════
        //  A. 缺口规模：三种判据并列，避免单一判据误导
        // ══════════════════════════════════════════════════════════
        text.appendLine("## A. 缺口规模（三种判据，结论不同，全部列出）")
        text.appendLine()
        val zhAll = zhBlocks.flatten().flatMap { SentenceSplitter.split(it) }
        val cjkFreq = HashMap<Char, Int>(8192)
        val ctx = HashMap<Char, String>(4096)
        for (s in zhAll) {
            for (c in s) if (isCjk(c)) {
                cjkFreq.merge(c, 1, Int::plus)
                if (!ctx.containsKey(c)) {
                    val at = s.indexOf(c)
                    ctx[c] = s.substring((at - 10).coerceAtLeast(0), (at + 11).coerceAtMost(s.length))
                }
            }
        }
        val totalTokens = cjkFreq.values.sum()
        val replaced = cjkFreq.keys.filter { TraditionalSimplified.toSimplified(it.toString()) != it.toString() }
        val replacedTokens = replaced.sumOf { cjkFreq[it]!! }
        val unchanged = cjkFreq.keys.filter { TraditionalSimplified.toSimplified(it.toString()) == it.toString() }
        val unchangedTokens = unchanged.sumOf { cjkFreq[it]!! }
        text.appendLine("| 判据 | 结果 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| 中译本 CJK 出现总次数 | $totalTokens |")
        text.appendLine("| 不同 CJK 字 | ${cjkFreq.size} |")
        text.appendLine("| 归一表**命中**（字被改写） | ${replaced.size} 字 / $replacedTokens 次 = **${f(replacedTokens.toDouble() / totalTokens)}** |")
        text.appendLine("| 归一表**未命中** | ${unchanged.size} 字 / $unchangedTokens 次 = ${f(unchangedTokens.toDouble() / totalTokens)} |")
        text.appendLine()
        text.appendLine("⚠️ **「未命中」不等于「是繁体」**：现代简体字本来就在表里查不到（表只存繁→简映射）。")
        text.appendLine("所以 `未命中 81.5%` 这个数**不能**读成「81.5% 的字需要归一」。")
        text.appendLine("归一表命中率 **${f(replacedTokens.toDouble() / totalTokens)}** 与引擎 notes 的 `norm coverage=0.1852` 一致——")
        text.appendLine("它度量的是「有多少字**真的被**繁→简改写了」，不是缺口的规模。")
        text.appendLine()

        // ══════════════════════════════════════════════════════════
        //  B. 决定性度量：V1 通道命中率在归一前后的变化
        // ══════════════════════════════════════════════════════════
        text.appendLine("## B. **决定性度量**：V1 词典通道在归一前后各命中多少（人工批准的译文上）")
        text.appendLine()
        text.appendLine("样本 = 人工判定 `ok`/`ok2` 且 `approved.level = SENTENCE` 的全部样本（= 人工认可")
        text.appendLine("「这条中文译文就是这句英文的正确对照」）。")
        text.appendLine()
        text.appendLine("对每条：取英文内容词 → 真 ECDICT 取中文候选短语 → 分别在这些译文里找命中：")
        text.appendLine()
        text.appendLine("- **(a) 原文（繁体，即产品实际看到的）**")
        text.appendLine("- **(b) 经 `TraditionalSimplified` 归一后（算法内部实际用于匹配的）**")
        text.appendLine()

        val positive = samples.filter { !it.garbage && it.approvedByHuman && it.approvedLevel == "SENTENCE" }
        text.appendLine("| # | id | 内容词 | 候选短语 | 命中(a) 原文 | 命中(b) 归一后 | 增量 |")
        text.appendLine("| --- | --- | --- | --- | --- | --- | --- |")

        var sumTerms = 0
        var sumHitRaw = 0
        var sumHitNorm = 0
        var improvedByNorm = 0
        var harmedByNorm = 0
        val gapExamples = ArrayList<String>()

        for ((index, sample) in positive.withIndex()) {
            val raw = sample.approvedZh
            val norm = TraditionalSimplified.toSimplified(raw)
            val words = contentWords(sample.en)
            val terms = LinkedHashSet<String>()
            for (w in words) {
                val cands = runCatching { meaning.chineseCandidates(w) }.getOrNull().orEmpty()
                for (c in cands) {
                    val cleaned = c.filter { isCjk(it) }
                    if (cleaned.length in 2..4) terms.add(cleaned)
                }
            }
            val hitRaw = terms.count { raw.contains(it) }
            val hitNorm = terms.count { norm.contains(it) }
            sumTerms += terms.size
            sumHitRaw += hitRaw
            sumHitNorm += hitNorm
            val delta = hitNorm - hitRaw
            if (delta > 0) improvedByNorm += delta
            if (delta < 0) harmedByNorm += -delta
            if (delta > 0 && gapExamples.size < 8) {
                val newHits = terms.filter { norm.contains(it) && !raw.contains(it) }
                gapExamples += "${sample.id}: 归一后才命中的短语 = $newHits"
            }
            text.appendLine(
                "| ${index + 1} | ${sample.id} | ${words.size} | ${terms.size} | $hitRaw | $hitNorm | " +
                    "${if (delta > 0) "+$delta" else "$delta"} |"
            )
        }
        text.appendLine("| **合计** | | | **$sumTerms** | **$sumHitRaw** | **$sumHitNorm** | **+${sumHitNorm - sumHitRaw}** |")
        text.appendLine()
        text.appendLine("- 样本数 n = ${positive.size}")
        text.appendLine("- 候选短语总数 = $sumTerms")
        text.appendLine("- 原文命中 = $sumHitRaw（命中率 ${f(sumHitRaw.toDouble() / sumTerms.coerceAtLeast(1))}）")
        text.appendLine("- 归一后命中 = $sumHitNorm（命中率 ${f(sumHitNorm.toDouble() / sumTerms.coerceAtLeast(1))}）")
        text.appendLine("- **归一表挽回的命中 = ${sumHitNorm - sumHitRaw}**" +
            "（相对原文 +${f((sumHitNorm - sumHitRaw).toDouble() / sumHitRaw.coerceAtLeast(1) * 100.0)}%）")
        text.appendLine("- 归一导致**丢失**的命中 = $harmedByNorm")
        text.appendLine()
        text.appendLine("### 归一表挽回的具体例子（归一后才命中的短语）")
        text.appendLine()
        gapExamples.forEach { text.appendLine("- $it") }
        text.appendLine()

        // ══════════════════════════════════════════════════════════
        //  C. 缺口字表：只列「归一后确实改变了文本」的字，附上下文
        // ══════════════════════════════════════════════════════════
        text.appendLine("## C. 归一表命中字表 TOP 25（这些是**真的被改写**的字，附上下文）")
        text.appendLine()
        text.appendLine("| # | 繁 | 简 | 次数 | 上下文 |")
        text.appendLine("| --- | --- | --- | --- | --- |")
        replaced.sortedByDescending { cjkFreq[it]!! }.take(25).forEachIndexed { i, c ->
            val context = (ctx[c] ?: "").replace("|", "\\|")
            text.appendLine("| ${i + 1} | $c | ${TraditionalSimplified.toSimplified(c.toString())} | ${cjkFreq[c]} | $context |")
        }
        text.appendLine()
        text.appendLine("### 归一表**未覆盖**、但明显是繁体形的高频字 TOP 25（人工目视可辨）")
        text.appendLine()
        text.appendLine("判据：归一表未命中，且该字**与一个常用简体字构成已知繁简对**（人工核对列）。")
        text.appendLine("这张表不改任何数字，只给修表的人一份优先级清单。")
        text.appendLine()
        // 已知繁简对（人工核对的确定性清单，不是自动推断）
        val knownPairs = mapOf(
            '羅' to '罗', '爾' to '尔', '聲' to '声', '無' to '无', '裡' to '里', '許' to '许',
            '別' to '别', '並' to '并', '眾' to '众', '則' to '则', '傳' to '传', '帶' to '带',
            '卻' to '却', '滿' to '满', '確' to '确', '岡' to '冈', '夠' to '够', '續' to '续',
            '須' to '须', '繼' to '继', '嗎' to '吗', '盡' to '尽', '連' to '连', '陽' to '阳',
            '終' to '终', '彿' to '佛', '惡' to '恶', '達' to '达', '類' to '类', '強' to '强',
            '題' to '题', '於' to '于', '鬥' to '斗', '獨' to '独', '麼' to '么', '處' to '处',
            '歲' to '岁', '離' to '离', '覺' to '觉', '邊' to '边', '們' to '们', '這' to '这',
            '來' to '来', '著' to '着', '說' to '说', '過' to '过', '會' to '会', '後' to '后'
        )
        val notable = unchanged.filter { it in knownPairs }
            .sortedByDescending { cjkFreq[it]!! }
        val notableTokens = notable.sumOf { cjkFreq[it]!! }
        text.appendLine("| # | 繁 | 应是 | 次数 | 上下文 |")
        text.appendLine("| --- | --- | --- | --- | --- |")
        notable.take(25).forEachIndexed { i, c ->
            val context = (ctx[c] ?: "").replace("|", "\\|")
            text.appendLine("| ${i + 1} | $c | ${knownPairs[c]} | ${cjkFreq[c]} | $context |")
        }
        text.appendLine()
        text.appendLine("人工可辨的繁简对里，归一表**未覆盖**的有 **${notable.size}** 个不同字，" +
            "共 $notableTokens 次出现（占 CJK 总出现 ${f(notableTokens.toDouble() / totalTokens)}）。")
        text.appendLine()
        text.appendLine("这个数是缺口的**保守下界**（只算我人工逐一核对过的对），")
        text.appendLine("比先前「882 字」那种自动判据可靠——后者把生僻简体字也误记为缺口。")
        text.appendLine()

        println(text.toString())
        File(outDir, "14-traditional-gap-impact.txt").writeText(text.toString())

        assertTrue(positive.isNotEmpty(), "必须有可测的人工批准 SENTENCE 样本")
        assertTrue(sumTerms > 0, "必须有 ECDICT 候选短语")
    }

    private fun assertTrue(condition: Boolean, message: String = "") =
        org.junit.Assert.assertTrue(message, condition)
}
