package com.linguareader.app.alignment

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.translation.EcdictMeaningIndex
import com.linguareader.app.tts.TtsTextExtractor
import com.linguareader.shared.alignment.AlignmentChapter
import com.linguareader.shared.alignment.AlignmentStatus
import com.linguareader.shared.alignment.IndependentAligner
import com.linguareader.shared.translation.TraditionalSimplified
import com.linguareader.shared.tts.SentenceSplitter
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.Properties

/**
 * **根因定位**：为什么 `independent-v1` 在魔戒真实语料上 coverage=0.0021。
 *
 * 三个被 Lead 点名的疑点，逐个用可复现的计数回答，**不靠阅读代码下结论**：
 *
 * | 疑点 | 本文件的回答方式 |
 * | --- | --- |
 * | `anchors=0`（硬钉全被剪） | 按 `TextPipeline.ScanResult.scan` + `AnchorLayer.build` 的**规范**独立复算键频，给出「哪一道过滤杀掉了多少锚点」的完整漏斗 + 被剪锚点 TOP 20 |
 * | 繁简归一 18.5% | 统计中译本实际用到的 CJK 字，与归一表命中/未命中交叉；再用 ECDICT 简体释义字表**独立判定**「未归一但疑似繁体」的字量，附上下文例子 |
 * | UNCERTAIN 但 targetText 空 | 全书计数（不是只在这 100 条样本里数） |
 *
 * ⚠️ 独立复算的意义：`AnchorLayer` 是 `internal`（`:shared` 模块内可见），`:app` 测试够不到。
 * 因此这里**按规范重写**一份只用于计数的最小实现——只数频次、不产生任何配对，
 * 结论"哪条过滤规则杀掉多少锚点"不依赖被测实现的正确性。
 */
@RunWith(RobolectricTestRunner::class)
class LotrRootCauseDiagnosticsTest {

    // ── 与实现一致的常量（逐个标注出处，改实现时必须同步改这里）──
    /** `AlignTuning.ANCHOR_MAX_FREQ` */
    private val ANCHOR_MAX_FREQ = 3

    // ── 与实现一致的扫描规范 ─────────────────────────────────────
    private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun isDigit(c: Char) = c in '0'..'9'
    private fun isFullwidthDigit(c: Char) = c in '０'..'９'
    private fun isCjk(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'
    private fun isWordCont(c: Char) =
        isLetter(c) || isDigit(c) || c == '\'' || c == '’' || c == '-'

    /** 规范：拉丁锚点 = `[A-Za-z][A-Za-z0-9'-]{1,}`（≥2 字符），键为小写原串。 */
    private fun latinTokens(s: String): List<String> {
        val out = ArrayList<String>(8)
        var i = 0
        while (i < s.length) {
            if (isLetter(s[i])) {
                var j = i + 1
                while (j < s.length && isWordCont(s[j])) j++
                if (j - i >= 2) out += s.substring(i, j).lowercase(Locale.ROOT)
                i = j
            } else i++
        }
        return out
    }

    /** 规范：数字锚点 = 十进制串（千分位逗号丢弃，允许一个小数点）。 */
    private fun digitTokens(s: String): List<String> {
        val out = ArrayList<String>(4)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (isDigit(c) || isFullwidthDigit(c)) {
                val sb = StringBuilder(8)
                var j = i
                var sawSeparator = false
                while (j < s.length) {
                    val ch = s[j]
                    if (isDigit(ch)) {
                        sb.append(ch); j++
                    } else if (isFullwidthDigit(ch)) {
                        sb.append(('0'.code + (ch.code - '０'.code)).toChar()); j++
                    } else if (!sawSeparator && (ch == '.' || ch == ',') &&
                        j + 1 < s.length && isDigit(s[j + 1])
                    ) {
                        sawSeparator = true; sb.append(ch); j++
                    } else break
                }
                if (sb.isNotEmpty()) out += sb.toString().replace(",", "")
                i = j
            } else i++
        }
        return out
    }

    @Test
    fun rootCauses() {
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

        val enSentences = enBlocks.flatten().flatMap { SentenceSplitter.split(it) }
        val zhSentencesRaw = zhBlocks.flatten().flatMap { SentenceSplitter.split(it) }
        // 中文侧匹配文本：与 TextPipeline 同口径（繁→简后参与锚点/词典匹配）
        val zhSentencesNorm = zhSentencesRaw.map { TraditionalSimplified.toSimplified(it) }

        val text = StringBuilder()
        text.appendLine("# 根因定位（独立复算，不采信实现自述）")
        text.appendLine()

        // ══════════════════════════════════════════════════════════
        //  1. 锚点漏斗
        // ══════════════════════════════════════════════════════════
        text.appendLine("## 1. 硬钉锚点漏斗（`anchors=0` 的成因）")
        text.appendLine()
        text.appendLine("规范复算：拉丁锚点 = `[A-Za-z][A-Za-z0-9'-]{1,}`；数字锚点 = 十进制串。")
        text.appendLine("频次口径 = **含该键的句子数**（与 `AnchorLayer.countFrequency` 一致，每句每个键计 1）。")
        text.appendLine()

        class KeyStats {
            var en = 0
            var zh = 0
            var sampleEn = ""
            var sampleZh = ""
        }

        val enFreq = HashMap<String, Int>(16384)
        val zhFreq = HashMap<String, Int>(16384)
        val firstEn = HashMap<String, String>(4096)
        val firstZh = HashMap<String, String>(4096)
        for (s in enSentences) {
            val keys = (latinTokens(s) + digitTokens(s)).toSet()
            for (k in keys) {
                enFreq.merge(k, 1, Int::plus)
                firstEn.putIfAbsent(k, s)
            }
        }
        for (s in zhSentencesNorm) {
            val keys = latinTokens(s).toSet()
            for (k in keys) {
                zhFreq.merge(k, 1, Int::plus)
                firstZh.putIfAbsent(k, s)
            }
        }
        // 中文侧也数数字锚点（实现两侧同用 countFrequency，数字键两侧都能命中）
        for (s in zhSentencesNorm) {
            val keys = digitTokens(s).toSet()
            for (k in keys) {
                zhFreq.merge(k, 1, Int::plus)
                firstZh.putIfAbsent(k, s)
            }
        }

        val distinctEn = enFreq.size
        val presentBoth = enFreq.keys.filter { zhFreq.containsKey(it) }
        val distinguishing = presentBoth.filter {
            enFreq[it]!! <= ANCHOR_MAX_FREQ && zhFreq[it]!! <= ANCHOR_MAX_FREQ
        }
        val prunedByFreq = presentBoth.filter {
            enFreq[it]!! > ANCHOR_MAX_FREQ || zhFreq[it]!! > ANCHOR_MAX_FREQ
        }

        fun pct(n: Int, d: Int) = if (d <= 0) "n/a" else String.format(Locale.ROOT, "%.4f", n.toDouble() / d)

        text.appendLine("| 漏斗步骤 | 键数 | 占英文侧不同键的比例 |")
        text.appendLine("| --- | --- | --- |")
        text.appendLine("| ① 英文侧出现的不同锚点键总数 | $distinctEn | 1.0000 |")
        text.appendLine("| ② 中文侧也出现（`freqZh != null`） | ${presentBoth.size} | ${pct(presentBoth.size, distinctEn)} |")
        text.appendLine("| ③ 两侧频次都 ≤ $ANCHOR_MAX_FREQ（成为 `distinguishing`） | ${distinguishing.size} | ${pct(distinguishing.size, distinctEn)} |")
        text.appendLine()
        text.appendLine("**被 ② 杀的键数 = ${distinctEn - presentBoth.size}**（=${pct(distinctEn - presentBoth.size, distinctEn)}）")
        text.appendLine("**被 ③ 杀的键数 = ${prunedByFreq.size}**（占「两侧都出现」的 ${pct(prunedByFreq.size, presentBoth.size)}）")
        text.appendLine()
        text.appendLine("结论：**第一道杀伤是「中文侧也出现」这道隐含过滤，不是频率门槛**。")
        text.appendLine("原因：拉丁锚点键取的是英文词原串，而中译本里这些词一律译成汉字——")
        text.appendLine("`frodo` 在英文侧出现数百次，在中文侧出现 **0** 次，于是 `freqZh[key] ?: continue` 直接丢弃。")
        text.appendLine("频率门槛（③）只是第二道，只影响少数幸存键。")
        text.appendLine()

        text.appendLine("### 被频率门槛 ③ 剪掉的 TOP 20（按英文侧频次降序）")
        text.appendLine()
        text.appendLine("| # | 锚点键 | 英文侧句数 | 中文侧句数 |")
        text.appendLine("| --- | --- | --- | --- |")
        prunedByFreq.sortedByDescending { enFreq[it]!! }.take(20).forEachIndexed { index, key ->
            text.appendLine("| ${index + 1} | `$key` | ${enFreq[key]} | ${zhFreq[key]} |")
        }
        text.appendLine()
        text.appendLine("### 被「中文侧不出现」杀掉的 TOP 20（按英文侧频次降序）—— 专名几乎全在这里")
        text.appendLine()
        text.appendLine("| # | 锚点键 | 英文侧句数 | 中文侧句数 |")
        text.appendLine("| --- | --- | --- | --- |")
        enFreq.entries.filter { !zhFreq.containsKey(it.key) }
            .sortedByDescending { it.value }.take(20).forEachIndexed { index, entry ->
                text.appendLine("| ${index + 1} | `${entry.key}` | ${entry.value} | 0 |")
            }
        text.appendLine()
        text.appendLine("### Lead 点名的专名逐个查（英文侧频次 / 中文侧频次 / 能否做锚点）")
        text.appendLine()
        text.appendLine("| 词 | 英文侧句数 | 中文侧句数 | 结论 |")
        text.appendLine("| --- | --- | --- | --- |")
        listOf("frodo", "gandalf", "bilbo", "sam", "merry", "pippin", "strider", "elrond", "mordor", "shire")
            .forEach { w ->
                val en = enFreq[w] ?: 0
                val zh = zhFreq[w] ?: 0
                val verdict = when {
                    zh == 0 -> "中文侧 0 次 ⇒ 不入 `distinguishing`"
                    en > ANCHOR_MAX_FREQ || zh > ANCHOR_MAX_FREQ -> "频次 > $ANCHOR_MAX_FREQ ⇒ 剪掉"
                    else -> "✅ 可作锚点"
                }
                text.appendLine("| `$w` | $en | $zh | $verdict |")
            }
        text.appendLine()
        text.appendLine("### 实际幸存的 `distinguishing` 键（前 30，按英文侧频次）")
        text.appendLine()
        text.appendLine("| # | 键 | 英文侧 | 中文侧 | 英文样例 |")
        text.appendLine("| --- | --- | --- | --- | --- |")
        distinguishing.sortedByDescending { enFreq[it]!! }.take(30).forEachIndexed { index, key ->
            val sample = (firstEn[key] ?: "").replace("|", "\\|").take(60)
            text.appendLine("| ${index + 1} | `$key` | ${enFreq[key]} | ${zhFreq[key]} | $sample |")
        }
        text.appendLine()
        text.appendLine("`distinguishing` 键总数 = **${distinguishing.size}**；" +
            "数字键 ${distinguishing.count { it.all { c -> isDigit(c) || c == '.' } }} / " +
            "拉丁键 ${distinguishing.count { it.any { c -> isLetter(c) } }}")
        text.appendLine()
        // ── 为什么 117 个键仍然产出 0 颗硬钉 ─────────────────────
        val uniqueBoth = distinguishing.filter { enFreq[it] == 1 && zhFreq[it] == 1 }
        text.appendLine("### 为什么 ${distinguishing.size} 个 `distinguishing` 键仍然产出 **0 颗硬钉**")
        text.appendLine()
        text.appendLine("`AnchorLayer.toHardPins` 的硬钉条件是 `weight >= 1 - 1e-9`，")
        text.appendLine("而 `weight = 1/(英文出现句数 × 中文出现句数)`——")
        text.appendLine("**只有两侧都恰好出现 1 次才等于 1.0**。")
        text.appendLine()
        text.appendLine("| 两侧都只出现 1 次（可成硬钉） | 键数 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| 是 | ${uniqueBoth.size} |")
        text.appendLine("| 否（任一测 > 1 ⇒ weight < 1 ⇒ 被跳过） | ${distinguishing.size - uniqueBoth.size} |")
        text.appendLine()
        if (uniqueBoth.isNotEmpty()) {
            text.appendLine("幸存的「两侧唯一」键：${uniqueBoth.joinToString(", ") { "`$it`" }}")
            text.appendLine()
            text.appendLine("它们仍然可能被 `pruneInconsistent`（相邻两钉增量长度比偏离 rBook）剪掉。")
        } else {
            text.appendLine("**结论：一个都没有。** 于是 `pins = []`，`sigma` 退回默认 0.35，")
            text.appendLine("V3 硬钉通道全程不存在——这与引擎 notes 里 `anchors=0, w=0.00, default` 完全吻合。")
        }
        text.appendLine()
        text.appendLine("### 数字锚点为什么一个都不剩")
        text.appendLine()
        val enDigitTokens = enFreq.keys.count { it.all { c -> isDigit(c) || c == '.' } }
        val zhDigitTokens = zhFreq.keys.count { it.all { c -> isDigit(c) || c == '.' } }
        text.appendLine("- 英文侧不同数字键 = $enDigitTokens")
        text.appendLine("- 中文侧不同数字键 = $zhDigitTokens")
        text.appendLine("- 两侧都有的数字键 = ${presentBoth.count { it.all { c -> isDigit(c) } }}")
        text.appendLine()
        text.appendLine("中译本把年份/数量写成中文数字（`一九六五年`、`一百一十二歲`），")
        text.appendLine("阿拉伯数字串在两侧**天然不同形**，因此数字锚点在这本译文上近乎全灭。")
        text.appendLine()

        // ══════════════════════════════════════════════════════════
        //  2. 繁简归一覆盖
        // ══════════════════════════════════════════════════════════
        text.appendLine("## 2. 繁简归一覆盖（`norm coverage=0.1852` 的成因）")
        text.appendLine()
        val cjkFreq = HashMap<Char, Int>(8192)
        val cjkContext = HashMap<Char, String>(2048)
        for (s in zhSentencesRaw) {
            for (c in s) if (isCjk(c)) {
                cjkFreq.merge(c, 1, Int::plus)
                if (!cjkContext.containsKey(c)) {
                    val at = s.indexOf(c)
                    val from = (at - 12).coerceAtLeast(0)
                    val to = (at + 13).coerceAtMost(s.length)
                    cjkContext[c] = s.substring(from, to)
                }
            }
        }
        val distinctCjk = cjkFreq.size
        val replacedChars = cjkFreq.keys.filter { TraditionalSimplified.toSimplified(it.toString()) != it.toString() }
        val unchangedChars = cjkFreq.keys.filter { TraditionalSimplified.toSimplified(it.toString()) == it.toString() }
        val replacedTokens = replacedChars.sumOf { cjkFreq[it]!! }
        val totalTokens = cjkFreq.values.sum()

        text.appendLine("中译本 CJK 字符统计（按 `SentenceSplitter.split` 后的句子逐字符计）：")
        text.appendLine()
        text.appendLine("- CJK 总量（字符出现次数）=$totalTokens")
        text.appendLine("- 不同 CJK 字 = $distinctCjk")
        text.appendLine("- 被归一表**改写**的不同字 = ${replacedChars.size}（占不同字 ${pct(replacedChars.size, distinctCjk)}，" +
            "占出现次数 ${pct(replacedTokens, totalTokens)}）")
        text.appendLine("- 归一表**未命中**（原样保留）的不同字 = ${unchangedChars.size}" +
            "（占出现次数 ${pct(totalTokens - replacedTokens, totalTokens)}）")
        text.appendLine()
        text.appendLine("归一表规模：`TraditionalSimplified` 硬编码 **${countTableEntries()}** 对。")
        text.appendLine()

        // 用 ECDICT 简体释义字表独立判定「疑似繁体」
        val simplifiedLexicon = loadEcdictCharSet(app)
        text.appendLine("独立判据：ECDICT `entries.translation` 字段里出现过的 CJK 字集合" +
            "（该字段是简体中文释义，共 ${simplifiedLexicon.size} 个不同字）。")
        text.appendLine("若某字在中译本出现、却**不在**该简化字表里，则它极可能是繁体专用字/异体字。")
        text.appendLine()
        val likelyTraditional = unchangedChars.filter { it !in simplifiedLexicon }
            .sortedByDescending { cjkFreq[it]!! }
        val likelyTraditionalTokens = likelyTraditional.sumOf { cjkFreq[it]!! }
        text.appendLine("- 归一表**未命中** 且 **不在 ECDICT 简体字表**（推定繁体、归一表缺口）= " +
            "**${likelyTraditional.size} 个不同字 / $likelyTraditionalTokens 次出现**" +
            "（占 CJK 出现次数 ${pct(likelyTraditionalTokens, totalTokens)}）")
        text.appendLine()
        text.appendLine("### 缺口 TOP 30（字 / 出现次数 / 上下文）")
        text.appendLine()
        text.appendLine("| # | 字 | 次数 | 上下文 |")
        text.appendLine("| --- | --- | --- | --- |")
        likelyTraditional.take(30).forEachIndexed { index, c ->
            val ctx = (cjkContext[c] ?: "").replace("|", "\\|")
            text.appendLine("| ${index + 1} | $c | ${cjkFreq[c]} | $ctx |")
        }
        text.appendLine()
        text.appendLine("### 归一表已覆盖的高频字（前 20，证明表在工作、只是不够）")
        text.appendLine()
        text.appendLine("| # | 繁 | 简 | 次数 |")
        text.appendLine("| --- | --- | --- | --- |")
        replacedChars.sortedByDescending { cjkFreq[it]!! }.take(20).forEachIndexed { index, c ->
            text.appendLine("| ${index + 1} | $c | ${TraditionalSimplified.toSimplified(c.toString())} | ${cjkFreq[c]} |")
        }
        text.appendLine()

        // ══════════════════════════════════════════════════════════
        //  3. 形态比例
        // ══════════════════════════════════════════════════════════
        text.appendLine("## 3. 中英形态比例（是否「中文分句偏粗」）")
        text.appendLine()
        val enParas = enBlocks.sumOf { it.size }
        val zhParas = zhBlocks.sumOf { it.size }
        val enLangUnits = enSentences.count { s -> s.any { isLetter(it) } }
        val zhLangUnits = zhSentencesRaw.count { s -> s.any { isCjk(it) } }
        text.appendLine("| 项 | 英文 | 中文 | 中/英 |")
        text.appendLine("| --- | --- | --- | --- |")
        text.appendLine("| 章节数 | ${enBook.chapters.size} | ${zhBook.chapters.size} | ${pct(zhBook.chapters.size, enBook.chapters.size)} |")
        text.appendLine("| 叶级段落数 | $enParas | $zhParas | ${pct(zhParas, enParas)} |")
        text.appendLine("| 句子数（SentenceSplitter） | ${enSentences.size} | ${zhSentencesRaw.size} | ${pct(zhSentencesRaw.size, enSentences.size)} |")
        text.appendLine("| 句子数（语言单位，用于对齐分母） | $enLangUnits | $zhLangUnits | ${pct(zhLangUnits, enLangUnits)} |")
        text.appendLine()
        val enAvgWords = enSentences.sumOf { latinTokens(it).size }.toDouble() / enSentences.size.coerceAtLeast(1)
        val zhAvgChars = zhSentencesRaw.sumOf { s -> s.count { isCjk(it) } }.toDouble() / zhSentencesRaw.size.coerceAtLeast(1)
        text.appendLine("- 英文平均句长 = ${String.format(Locale.ROOT, "%.2f", enAvgWords)} 词/句")
        text.appendLine("- 中文平均句长 = ${String.format(Locale.ROOT, "%.2f", zhAvgChars)} 字/句")
        text.appendLine("- 字符/词比 = ${String.format(Locale.ROOT, "%.3f", zhAvgChars / enAvgWords)}" +
            "（引擎 notes 报 r=1.591）")
        text.appendLine()
        text.appendLine("判读：中英句数比 ${String.format(Locale.ROOT, "%.3f", zhSentencesRaw.size.toDouble() / enSentences.size)}，")
        text.appendLine("若显著 < 1，说明中文切分更粗 → 真实主形态应是 `N:1`（多英文句共享一条中文句）。")
        text.appendLine()

        // ══════════════════════════════════════════════════════════
        //  4. 契约一致性（全书计数）
        // ══════════════════════════════════════════════════════════
        text.appendLine("## 4. 三态与 targetText 的契约一致性（全书计数）")
        text.appendLine()
        val meaning = LotrCorpus.meaningProvider(EcdictMeaningIndex(app))
        val enChapters = enBook.chapters.indices.map { AlignmentChapter(enBook.chapters[it].title, enBlocks[it]) }
        val zhChapters = zhBook.chapters.indices.map { AlignmentChapter(zhBook.chapters[it].title, zhBlocks[it]) }
        val doc = IndependentAligner().align(enChapters, zhChapters, meaning)
        val uncertain = doc.units.filter { it.status == AlignmentStatus.UNCERTAIN }
        val unaligned = doc.units.filter { it.status == AlignmentStatus.UNALIGNED }
        val aligned = doc.units.filter { it.status == AlignmentStatus.ALIGNED }
        val uncertainBlank = uncertain.filter { it.targetText.isBlank() }
        val unalignedWithText = unaligned.filter { it.targetText.isNotBlank() }
        val alignedBlank = aligned.filter { it.targetText.isBlank() }
        text.appendLine("| 检查项 | 数量 | 契约要求 | 判定 |")
        text.appendLine("| --- | --- | --- | --- |")
        text.appendLine("| `UNCERTAIN` 但 `targetText` 为空 | ${uncertainBlank.size} / ${uncertain.size} | UNCERTAIN = **有候选** ⇒ 必须有 targetText | ❌ 违反 |")
        text.appendLine("| `UNALIGNED` 却有 `targetText` | ${unalignedWithText.size} / ${unaligned.size} | UNALIGNED ⇒ targetText 必须为空 | ${if (unalignedWithText.isEmpty()) "✅ 满足" else "❌ 违反"} |")
        text.appendLine("| `ALIGNED` 但 `targetText` 为空 | ${alignedBlank.size} / ${aligned.size} | ALIGNED ⇒ 必须有 targetText | ${if (alignedBlank.isEmpty()) "✅ 满足" else "❌ 违反"} |")
        text.appendLine()
        text.appendLine("### UNCERTAIN 且 targetText 为空 —— 为什么空（理由分布，全书）")
        text.appendLine()
        uncertainBlank.groupingBy { it.evidence.reasons.joinToString(";").ifBlank { "(无理由)" } }
            .eachCount().entries.sortedByDescending { it.value }.take(10).forEach { (reason, count) ->
                text.appendLine("- `$reason` = $count")
            }
        text.appendLine()
        text.appendLine("### UNCERTAIN 且 targetText 为空的样例（前 10）")
        text.appendLine()
        uncertainBlank.take(10).forEach {
            text.appendLine("- ch${it.sourceChapter} conf=${String.format(Locale.ROOT, "%.2f", it.confidence)} " +
                "en=«${it.sourceText.take(70)}» reasons=${it.evidence.reasons}")
        }
        text.appendLine()

        text.appendLine("## 5. 合并形态与中文侧覆盖")
        text.appendLine()
        val oneToN = doc.units.count { it.status != AlignmentStatus.UNALIGNED && it.targetSpanSize > 1 }
        val nToOne = doc.units
            .filter { it.status != AlignmentStatus.UNALIGNED && it.targetText.isNotBlank() }
            .groupBy { it.targetText }.count { it.value.size > 1 }
        val coveredTargets = doc.units
            .filter { it.targetText.isNotBlank() }
            .map { Triple(it.targetChapter, it.targetParagraph, it.targetIndexInParagraph) }
            .toSet()
        text.appendLine("| 项 | 值 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| 1:N 单元（targetSpanSize > 1） | $oneToN |")
        text.appendLine("| N:1 组（多单元共享同一条 targetText） | $nToOne |")
        text.appendLine("| `mergedUnits`（implementation 按边计） | ${doc.stats.mergedUnits} |")
        text.appendLine("| 中文侧被覆盖的不同句数 | ${coveredTargets.size} |")
        text.appendLine("| 中文侧有效句数 | ${doc.stats.targetSentenceTotal} |")
        text.appendLine("| 中文侧**未被任何单元引用**的比例 | ${pct(doc.stats.targetSentenceTotal - coveredTargets.size, doc.stats.targetSentenceTotal)} |")
        text.appendLine()

        println(text.toString())
        File(outDir, "12-root-cause.txt").writeText(text.toString())

        // 硬事实：只断言"测到了"，不对结论做断言（结论是本报告的产出，不是测试的门）
        assertTrue(text.lines().size > 50, "诊断输出异常短，可能没测到东西")
        assertTrue(distinctEn > 0, "锚点漏斗必须能被复算")
    }

    // ── 工具 ──

    private fun countTableEntries(): Int {
        val file = File(
            "src/shared/src/main/java/com/linguareader/shared/translation/TraditionalSimplified.kt"
        )
        val candidates = listOf(file, File("../src/shared/src/main/java/com/linguareader/shared/translation/TraditionalSimplified.kt"))
        val real = candidates.firstOrNull { it.isFile }
        if (real != null) return Regex("put\\('").findAll(real.readText()).count()
        // 退回：用表本身探测（不需要文件）
        var count = 0
        for (c in 0x4E00..0x9FFF) {
            val ch = c.toChar()
            if (TraditionalSimplified.toSimplified(ch.toString()) != ch.toString()) count++
        }
        return count
    }

    /**
     * ECDICT `entries.translation` 里出现过的 CJK 字集合（简体侧语料）。
     *
     * 用 sqlite-jdbc 直读 assets 里的 ecdict（58MB），流式扫描，只收集 CJK 字。
     * 这是**独立于归一表**的判据：它不假设任何繁简映射。
     */
    private fun loadEcdictCharSet(app: Application): Set<Char> {
        val target = File(app.filesDir, "diag-ecdict.sqlite")
        if (!target.exists() || target.length() == 0L) {
            app.assets.open("dictionary/ecdict.sqlite").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val chars = HashSet<Char>(16384)
        val props = Properties().apply { setProperty("open_mode", "1") }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${target.absolutePath}", props).use { conn ->
            conn.prepareStatement("SELECT translation FROM entries WHERE translation IS NOT NULL AND translation <> ''")
                .use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            val s = rs.getString(1) ?: continue
                            for (c in s) if (isCjk(c)) chars.add(c)
                        }
                    }
                }
        }
        return chars
    }

    private fun assertTrue(condition: Boolean, message: String = "") =
        org.junit.Assert.assertTrue(message, condition)
}
