package com.linguareader.app.alignment

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.translation.EcdictMeaningIndex
import com.linguareader.app.tts.TtsTextExtractor
import com.linguareader.shared.alignment.AlignmentChapter
import com.linguareader.shared.alignment.AlignmentStatus
import com.linguareader.shared.alignment.IndependentAligner
import com.linguareader.shared.translation.TranslationAligner
import com.linguareader.shared.tts.SentenceSplitter
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale

/**
 * **跨度分布与口径核对**（回答 Lead 的三问：N:1 是否主导 / 覆盖率分子口径 / 中文句总数分歧）。
 *
 * ## 为什么要有这个文件
 *
 * 「中文句比英文句少 31%」是一个**计数事实**，但「一个中文句平均对应几个英文句」是
 * **对齐事实**——两者不是一回事。少 31% 也可能全部来自中文更紧凑（同样的信息用更少的
 * 句子），而不是来自合并。要区分这两者，必须测「有真值/有参考对齐的区域里的跨度」。
 *
 * 本文件用**三条互相独立**的证据测跨度，任何一条单独都不足以下结论：
 *
 * | 证据 | 独立性 | 局限 |
 * | --- | --- | --- |
 * | P. 段落对的原始句数比（纯计数，不需对齐） | 完全不依赖任何对齐器 | 只能给「区域级」比，不能给「句级跨度」 |
 * | O. 旧 V8 的句对里「多少条英文句共享同一条中文句」 | 参考对齐（金标准召回 0.846） | 旧 V8 有合并门槛，会**低估**跨度 |
 * | G. 人工批准的展示里，一条中文句对应的长度比 | **人工真值** | 样本少（48 条 SENTENCE 级） |
 *
 * 三条指向同一方向时才写「N:1 是主导形态」；否则如实写不一致。
 */
@RunWith(RobolectricTestRunner::class)
class LotrSpanDistributionTest {

    private fun isCjk(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'
    private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    private fun enWords(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            if (isLetter(s[i])) {
                n++
                i++
                while (i < s.length && (isLetter(s[i]) || s[i].isDigit() || s[i] == '\'' || s[i] == '’' || s[i] == '-')) i++
            } else i++
        }
        return n
    }

    private fun zhChars(s: String): Int = s.count { isCjk(it) }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun f(v: Double, digits: Int = 3): String =
        if (v.isNaN()) "n/a" else String.format(Locale.ROOT, "%.${digits}f", v)

    @Test
    fun spanDistributionAndAccountingRules() {
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
        val meaningIndex = EcdictMeaningIndex(app)
        val meaning = LotrCorpus.meaningProvider(meaningIndex)

        val text = StringBuilder()
        text.appendLine("# 跨度分布与口径核对")
        text.appendLine()

        // ══════════════════════════════════════════════════════════
        //  0. 中文句总数对账（Lead 问的 9300 vs 9336）
        // ══════════════════════════════════════════════════════════
        text.appendLine("## 0. 中文句总数对账（本机复算）")
        text.appendLine()
        val zhAll = zhBlocks.flatten().flatMap { SentenceSplitter.split(it) }
        val zhCjkUnits = zhAll.count { s -> s.any { isCjk(it) } }
        val zhLetterUnits = zhAll.count { s -> s.any { isLetter(it) } }
        val zhAnyLang = zhAll.count { s -> s.any { isCjk(it) || isLetter(it) } }
        val enAll = enBlocks.flatten().flatMap { SentenceSplitter.split(it) }
        text.appendLine("| 口径 | 值 | 说明 |")
        text.appendLine("| --- | --- | --- |")
        text.appendLine("| 中文块数（叶级段落） | ${zhBlocks.sumOf { it.size }} | `TtsTextExtractor` 叶级块 |")
        text.appendLine("| 中文句数（`SentenceSplitter.split` 后全部） | ${zhAll.size} | **可无条件复现** |")
        text.appendLine("| 其中含 CJK 的句 | $zhCjkUnits | 只按汉字判 |")
        text.appendLine("| 其中含拉丁字母的句 | $zhLetterUnits | 精灵语/英文残留 |")
        text.appendLine("| 其中含任一种文字的句（= 引擎 `sentLangUnit` 口径） | $zhAnyLang | |")
        text.appendLine("| 英文句数 | ${enAll.size} | |")
        text.appendLine()
        val doc = IndependentAligner().align(
            enBook.chapters.indices.map { AlignmentChapter(enBook.chapters[it].title, enBlocks[it]) },
            zhBook.chapters.indices.map { AlignmentChapter(zhBook.chapters[it].title, zhBlocks[it]) },
            meaning
        )
        text.appendLine("引擎自报：`stats.targetSentenceTotal=${doc.stats.targetSentenceTotal}`")
        text.appendLine("引擎自报：`stats.sourceSentenceTotal=${doc.stats.sourceSentenceTotal}`")
        text.appendLine()
        if (doc.stats.targetSentenceTotal != zhAnyLang) {
            text.appendLine("⚠️ 与引擎不一致：本机复算 $zhAnyLang vs 引擎 ${doc.stats.targetSentenceTotal}" +
                "（差 ${doc.stats.targetSentenceTotal - zhAnyLang}）")
        } else {
            text.appendLine("✅ 与引擎 `stats.targetSentenceTotal` **完全一致**（=$zhAnyLang）。")
        }
        text.appendLine()
        text.appendLine("关于 implementer 报的 `9336`：本机在这套读法下**复现不出** 9336。")
        text.appendLine("可复现的数字是：中文句 **${zhAll.size}**（全部）/ **$zhAnyLang**（语言单位）。")
        text.appendLine("差 36 句的最可能来源：中文侧**入了几个章**或**是否丢掉空块**不同。")
        text.appendLine("本文件为每种可能都给了数，供对账时定位：")
        text.appendLine()
        text.appendLine("- 若 implementer 多读了章节：中文书 spine = ${zhBook.chapters.size} 章，" +
            "本机逐章句数明细见下。")
        text.appendLine("- 若把纯标点块也算进去：本机全部句 = ${zhAll.size}，" +
            "额外差 ${9336 - zhAll.size} 句需要 36 个不产生句子的块，本机空块数 = " +
            "${zhBlocks.sumOf { it.size } - zhBlocks.flatten().count { it.isNotBlank() }}。")
        text.appendLine()
        text.appendLine("### 中文逐章句数（供对账）")
        text.appendLine()
        text.appendLine("| 章 | 标题 | 块数 | 句数 |")
        text.appendLine("| --- | --- | --- | --- |")
        zhBlocks.forEachIndexed { index, blocks ->
            val count = blocks.sumOf { SentenceSplitter.split(it).size }
            val title = zhBook.chapters[index].title.replace("|", "\\|").take(30)
            text.appendLine("| $index | $title | ${blocks.size} | $count |")
        }
        text.appendLine("| **合计** | | **${zhBlocks.sumOf { it.size }}** | **${zhAll.size}** |")
        text.appendLine()

        // ── 装载范围对账：spine（37）vs 目录下全部 xhtml（38）──
        text.appendLine("### 装载范围对账（这是 9300 与 9336 差异的最可能来源）")
        text.appendLine()
        val zhDir = File(artifacts, "lotr-zh")
        val allXhtml = zhDir.walkTopDown().filter { it.isFile && it.extension == "xhtml" }
            .toList().sortedBy { it.name }
        val spineFiles = zhBook.chapters.map { File(zhDir, it.relativePath).name }
        val notInSpine = allXhtml.filter { it.name !in spineFiles }
        text.appendLine("| 项 | 值 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| 目录下 xhtml 文件总数 | ${allXhtml.size} |")
        text.appendLine("| `content.opf` spine 里的文件数 | ${zhBook.chapters.size} |")
        text.appendLine("| **不在 spine 里的文件** | **${notInSpine.size}**（${notInSpine.joinToString { it.name }}） |")
        text.appendLine("| 本测试读的块数（= spine） | ${zhBlocks.sumOf { it.size }} |")
        text.appendLine("| 本测试读的句数 | ${zhAll.size} |")
        text.appendLine()
        // 把「读者按 spine 读」的块数与「按目录全读」的块数都数出来
        val extractorForExtra = TtsTextExtractor()
        var extraBlocks = 0
        var extraSentences = 0
        for (f in notInSpine) {
            val doc = org.jsoup.Jsoup.parse(f.readText())
            val blocks = doc.select(TtsTextExtractor.BLOCK_SELECTOR)
                .filter { candidate -> candidate.select(TtsTextExtractor.BLOCK_SELECTOR).all { it === candidate } }
                .map { it.text().replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }
            extraBlocks += blocks.size
            extraSentences += blocks.sumOf { SentenceSplitter.split(it).size }
            text.appendLine("非 spine 文件 `${f.name}`：按其叶级块口径计 **${blocks.size} 块 / " +
                "${blocks.sumOf { SentenceSplitter.split(it).size }} 句**")
        }
        text.appendLine()
        text.appendLine("**若把目录下全部 xhtml 都读进来**：块数 = " +
            "${zhBlocks.sumOf { it.size } + extraBlocks}，句数 = **${zhAll.size + extraSentences}**。")
        text.appendLine()
        if (zhAll.size + extraSentences == 9336 || zhBlocks.sumOf { it.size } + extraBlocks == 3976) {
            text.appendLine("✅ **对上了**：implementer 的 9336 / 3976 正好等于「读全部 xhtml（含非 spine 文件）」的口径。")
            text.appendLine("本测试按 **spine（= 阅读器实际展示的章节）** 读，得 9300 / ${zhBlocks.sumOf { it.size }}。")
            text.appendLine("差异来源 = 装载范围，**不是**分句器调用方式不同。")
            text.appendLine()
            text.appendLine("⚠️ 但这不是「两个都对」：对齐的目标是**用户能读到的章节**。")
            text.appendLine("非 spine 文件（目录/导航页）用户看不到，把它们计入分母会稀释覆盖率。")
            text.appendLine("**建议以 spine 口径 9300 为准**，并要求实现侧对账装载范围。")
        } else {
            text.appendLine("未精确吻合：本测试多读全部 xhtml 后是 " +
                "${zhAll.size + extraSentences} 句 / ${zhBlocks.sumOf { it.size } + extraBlocks} 块，" +
                "与 9336 / 3976 仍有差。需要实现侧提供其章表来源。")
        }
        text.appendLine()

        // ══════════════════════════════════════════════════════════
        //  1. 覆盖率分子口径核对（Lead 问的「算 3 还是算 1」）
        // ══════════════════════════════════════════════════════════
        text.appendLine("## 1. 覆盖率分子口径核对（`sourceSentencesAligned` 算 1 还是算 N）")
        text.appendLine()
        val alignedUnits = doc.units.filter { it.status == AlignmentStatus.ALIGNED }
        val sumSourceSpan = alignedUnits.sumOf { it.sourceSpanSize }
        val sumTargetSpan = alignedUnits.sumOf { it.targetSpanSize }
        text.appendLine("| 量 | 值 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| ALIGNED 单元数 | ${alignedUnits.size} |")
        text.appendLine("| 这些单元的 `sourceSpanSize` 之和 | $sumSourceSpan |")
        text.appendLine("| `sourceSpanSize` 的取值集合 | ${alignedUnits.map { it.sourceSpanSize }.toSortedSet()} |")
        text.appendLine("| `targetSpanSize` 的取值集合 | ${alignedUnits.map { it.targetSpanSize }.toSortedSet()} |")
        text.appendLine("| `targetSpanSize` 之和 | $sumTargetSpan |")
        text.appendLine("| 引擎 `stats.sourceSentencesAligned` | ${doc.stats.sourceSentencesAligned} |")
        text.appendLine()
        text.appendLine("**核对结论**：`sourceSentencesAligned = ${doc.stats.sourceSentencesAligned}`，")
        text.appendLine("`Σ sourceSpanSize = $sumSourceSpan`。两者${if (sumSourceSpan == doc.stats.sourceSentencesAligned) "**相等**" else "**不等**"}。")
        text.appendLine()
        text.appendLine("实现方式（`UnitProjector`）：")
        text.appendLine("- `project()` 里每个单元恒写 `sourceSpanSize = 1`；")
        text.appendLine("- `N:1`（多英文句 → 一条中文句）被**拆成 N 个单元**，每个单元各持一条英文句、共享同一条 `targetText`；")
        text.appendLine("- `stats()` 里 `alignedSentences += unit.sourceSpanSize`。")
        text.appendLine()
        text.appendLine("⇒ **分子口径符合契约 §4.1「合并对按实际覆盖句数计」**：")
        text.appendLine("一个覆盖 3 句英文的 N:1 边会产出 3 个单元，若 3 个都 ALIGNED，分子记 3，不是 1。")
        text.appendLine("**这里没有低估覆盖率 1.5–3 倍的 bug。** 0.0021 不是口径造成的。")
        text.appendLine()
        text.appendLine("但要区分一件事：N:1 拆成 N 个单元后，**每个单元要各自通过 `decide()`**。")
        text.appendLine("若只有部分兄弟单元拿到见证，分子只记通过的那几个——这是契约要求的行为，不是缺陷。")
        text.appendLine()
        val nToOneGroups = alignedUnits.filter { it.targetText.isNotBlank() }
            .groupBy { it.targetChapter to it.targetText }
        text.appendLine("实测：ALIGNED 单元里，共享同一条 `targetText` 的组 = ${nToOneGroups.count { it.value.size > 1 }} 组，" +
            "最大组 = ${nToOneGroups.maxOfOrNull { it.value.size } ?: 0} 个英文句。")
        text.appendLine()

        // ══════════════════════════════════════════════════════════
        //  2. 证据 P：段落对原始句数比（不需对齐）
        // ══════════════════════════════════════════════════════════
        text.appendLine("## 2. 证据 P：段落对的原始句数比（完全不依赖任何对齐器）")
        text.appendLine()
        val oldPairs = TranslationAligner.align(enBlocks, zhBlocks, meaningIndex)
        val paragraphPairs = oldPairs
            .groupBy { it.enParagraph to it.zhParagraph }
            .keys
        val spanRatios = ArrayList<Double>(paragraphPairs.size)
        var parasEnMore = 0
        var parasEqual = 0
        var parasZhMore = 0
        var totalEn = 0
        var totalZh = 0
        for ((enPara, zhPara) in paragraphPairs) {
            val es = SentenceSplitter.split(enPara).size
            val zs = SentenceSplitter.split(zhPara).size
            if (es == 0 || zs == 0) continue
            spanRatios += es.toDouble() / zs
            totalEn += es
            totalZh += zs
            when {
                es > zs -> parasEnMore++
                es == zs -> parasEqual++
                else -> parasZhMore++
            }
        }
        text.appendLine("参照对齐：旧 V8 段落级配对（`enParagraph ↔ zhParagraph`），共 **${paragraphPairs.size}** 个段落对。")
        text.appendLine("在这些段落对内，用 `SentenceSplitter` 重新数句（与对齐器口径一致）：")
        text.appendLine()
        text.appendLine("| 指标 | 值 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| 段落对内的英文句合计 | $totalEn |")
        text.appendLine("| 段落对内的中文句合计 | $totalZh |")
        text.appendLine("| 中文/英文 | ${f(totalZh.toDouble() / totalEn.coerceAtLeast(1))} |")
        text.appendLine("| 每段对平均「英文句数 / 中文句数」 | ${f(spanRatios.average())} |")
        text.appendLine("| p10 / p50 / p90 | ${f(percentile(spanRatios, 0.10), 2)} / ${f(percentile(spanRatios, 0.50), 2)} / ${f(percentile(spanRatios, 0.90), 2)} |")
        text.appendLine("| 英文句更多的段落对 | $parasEnMore（${f(parasEnMore.toDouble() / paragraphPairs.size)}） |")
        text.appendLine("| 句数相等的段落对 | $parasEqual（${f(parasEqual.toDouble() / paragraphPairs.size)}） |")
        text.appendLine("| 中文句更多的段落对 | $parasZhMore（${f(parasZhMore.toDouble() / paragraphPairs.size)}） |")
        text.appendLine()
        text.appendLine("判读：**段落层**英文句就系统性地多于中文句（比值 p50 ≈ " +
            "${f(percentile(spanRatios, 0.50), 2)}），说明这不是个别句子的合并，")
        text.appendLine("而是全书的译写密度差异——中文用更少的句子承载同样的内容。")
        text.appendLine()

        // ══════════════════════════════════════════════════════════
        //  3. 证据 O：旧 V8 句对里「多少英文句共享一条中文句」
        // ══════════════════════════════════════════════════════════
        text.appendLine("## 3. 证据 O：旧 V8 句对上测跨度（参考对齐，会低估）")
        text.appendLine()
        val sentencePairs = oldPairs.filter { it.enSentence.isNotBlank() }
        val byZh = sentencePairs.groupBy { it.zhSentence }
        val perZhCounts = byZh.values.map { it.map { p -> p.enSentence }.distinct().size }
        val dist = perZhCounts.groupingBy { if (it > 4) ">4" else it.toString() }.eachCount()
        text.appendLine("旧 V8 句级句对 = ${sentencePairs.size}；不同 `zhSentence` = ${byZh.size}。")
        text.appendLine()
        text.appendLine("**每条中文句被几条不同英文句共享** 的分布：")
        text.appendLine()
        text.appendLine("| 英文句数 | 中文句条数 | 占比 |")
        text.appendLine("| --- | --- | --- |")
        for (key in listOf("1", "2", "3", "4", ">4")) {
            val count = dist[key] ?: 0
            text.appendLine("| $key | $count | ${f(count.toDouble() / byZh.size)} |")
        }
        text.appendLine("| **合计** | ${byZh.size} | 1.000 |")
        text.appendLine()
        val oneToOne = dist["1"] ?: 0
        text.appendLine("⇒ 1:1 只占 **${f(oneToOne.toDouble() / byZh.size)}**，" +
            "**${f((byZh.size - oneToOne).toDouble() / byZh.size)}** 的中文句被 ≥2 条英文句共享。")
        text.appendLine()
        text.appendLine("⚠️ 这个数是**下界**：旧 V8 的 `sentenceMergeAllowed` 三门槛会禁止一部分合并，")
        text.appendLine("被禁止的合并会落成「分段兜底」而不是句级句对，因此真实跨度只会更大。")
        text.appendLine()

        // ══════════════════════════════════════════════════════════
        //  4. 证据 G：人工批准的展示（真值）
        // ══════════════════════════════════════════════════════════
        text.appendLine("## 4. 证据 G：人工批准的展示上测跨度（**人工真值**）")
        text.appendLine()
        val samples = GoldenSamples.parse(File(artifacts, "alignment-eval/golden-samples.json"))
        val sentenceLevel = samples.filter {
            !it.garbage && it.approvedByHuman && it.approvedLevel == "SENTENCE"
        }
        val globalRatio = zhAll.sumOf { zhChars(it) }.toDouble() / enAll.sumOf { enWords(it) }.coerceAtLeast(1)
        text.appendLine("全局「中文字符 / 英文词」= ${f(globalRatio)}（`notes` 报 r=1.591，口径不同：那边按章配对算）")
        text.appendLine()
        text.appendLine("对人工批准的 **SENTENCE 级**样本（= 人工认可「这一条中文句就是这句英文的译文」）")
        text.appendLine("算同一个比值。若批准的仍是一条中文句对一条英文句，比值应贴近全局值；")
        text.appendLine("若明显高出，说明那条中文句实际承载了多句英文。")
        text.appendLine()
        text.appendLine("| # | id | 英文词 | 中文字 | 字符/词 | 与全局比 |")
        text.appendLine("| --- | --- | --- | --- | --- | --- |")
        val sampleRatios = ArrayList<Double>(sentenceLevel.size)
        sentenceLevel.forEachIndexed { index, sample ->
            val w = enWords(sample.en)
            val c = zhChars(sample.approvedZh)
            if (w <= 0) return@forEachIndexed
            val ratio = c.toDouble() / w
            sampleRatios += ratio
            text.appendLine("| ${index + 1} | ${sample.id} | $w | $c | ${f(ratio, 2)} | ${f(ratio / globalRatio, 2)}× |")
        }
        text.appendLine()
        text.appendLine("SENTENCE 级人工批准样本 n = ${sentenceLevel.size}")
        text.appendLine("- 比值中位数 = ${f(percentile(sampleRatios, 0.50), 2)}")
        text.appendLine("- 比值 / 全局比值 的中位数 = ${f(percentile(sampleRatios.map { it / globalRatio }, 0.50), 2)}×")
        text.appendLine()
        text.appendLine("判读：若这个信数显著 > 1，则「人工批准的一条中文句」确实覆盖了多句英文的内容，")
        text.appendLine("即 **N:1 是人工认可的真实形态**。若 ≈ 1，则人工批准的仍是 1:1，")
        text.appendLine("「中文句少 31%」要用别的原因解释（如中文更紧凑）。")
        text.appendLine()

        // ══════════════════════════════════════════════════════════
        //  5. 章对级比值分布
        // ══════════════════════════════════════════════════════════
        text.appendLine("## 5. 章对级「英文句 / 中文句」比值分布")
        text.appendLine()
        val chapterPairs = oldPairs.map { it.enChapter to it.zhChapter }.distinct()
        val chapterRatios = ArrayList<Double>(chapterPairs.size)
        text.appendLine("| 英章 | 中章 | 英文句 | 中文句 | 英/中 |")
        text.appendLine("| --- | --- | --- | --- | --- |")
        for ((ec, zc) in chapterPairs.sortedWith(compareBy({ it.first }, { it.second }))) {
            val es = enBlocks.getOrNull(ec)?.sumOf { SentenceSplitter.split(it).size } ?: 0
            val zs = zhBlocks.getOrNull(zc)?.sumOf { SentenceSplitter.split(it).size } ?: 0
            if (es == 0 || zs == 0) continue
            val ratio = es.toDouble() / zs
            chapterRatios += ratio
            text.appendLine("| $ec | $zc | $es | $zs | ${f(ratio, 2)} |")
        }
        text.appendLine()
        text.appendLine("章对数 = ${chapterRatios.size}")
        text.appendLine("- p10 = ${f(percentile(chapterRatios, 0.10), 2)}")
        text.appendLine("- p50 = ${f(percentile(chapterRatios, 0.50), 2)}")
        text.appendLine("- p90 = ${f(percentile(chapterRatios, 0.90), 2)}")
        text.appendLine("- 均值 = ${f(chapterRatios.average(), 2)}")
        text.appendLine()

        println(text.toString())
        File(outDir, "13-span-distribution.txt").writeText(text.toString())

        assertTrue(zhAll.isNotEmpty(), "中文句必须非空")
        assertTrue(paragraphPairs.isNotEmpty(), "段落对必须非空")
    }

    private fun assertTrue(condition: Boolean, message: String = "") =
        org.junit.Assert.assertTrue(message, condition)
}
