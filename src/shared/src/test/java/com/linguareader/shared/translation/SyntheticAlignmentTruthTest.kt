package com.linguareader.shared.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **合成语料的真值对齐测试**：唯一能全自动量「对齐得对不对」的测试。
 *
 * 真实书（魔戒）只能靠人工判定，覆盖的是「有没有对照」和人工抽样质量；这里用
 * 构造即知真值的合成语料，把句对级 precision / recall 变成 CI 里的机械断言，
 * 并逐类注入已归档病灶（段落合并/拆分/漏译/插入、标题行、纯标点、无编号、
 * 繁体）看对齐器会不会被带偏。
 *
 * 全部离线、无 Android 依赖，跑在 `:shared:test`（CI 已包含）。
 */
class SyntheticAlignmentTruthTest {

    private val markerRegex = Regex("""\d{4}""")

    // ---- 1. 干净语料：逐句完全正确 -----------------------------------------

    @Test
    fun cleanCorpusAlignsEverySentenceExactly() {
        val book = SyntheticBilingualCorpus.build()
        val pairs = TranslationAligner.align(book.enChapters, book.zhChapters, SyntheticBilingualCorpus.meaningIndex())
        val metrics = evaluate(pairs, book, book.markers)
        metrics.report("clean")
        assertEquals("干净语料应当逐句精确配对，错配=${metrics.wrong.take(3)}", 1.0, metrics.precision, 0.0)
        assertEquals("干净语料应当覆盖全部句编号", 1.0, metrics.recall, 0.0)
        assertEquals("句级句对数应等于句数", book.enSentences.size, metrics.sentencePairs)
    }

    // ---- 2. 段落级噪声：漏译 / 合并 / 拆分 / 插入 ---------------------------

    @Test
    fun missingZhParagraphDoesNotCascade() {
        val book = SyntheticBilingualCorpus.build()
        val removed = book.zhChapters[2][1]
        val zh = book.zhChapters.mapIndexed { c, paragraphs ->
            if (c == 2) paragraphs.filterIndexed { p, _ -> p != 1 } else paragraphs
        }
        val removedMarkers = markersOf(removed)
        val pairs = TranslationAligner.align(book.enChapters, zh, SyntheticBilingualCorpus.meaningIndex())
        val metrics = evaluate(pairs, book, book.markers - removedMarkers)
        metrics.report("missing-zh-paragraph")
        assertEquals("漏译段落之外的句对不得被带偏：${metrics.wrong.take(3)}", 1.0, metrics.precision, 0.0)
        assertEquals("漏译段落之外的句编号应全部正确配对", 1.0, metrics.recall, 0.0)
    }

    @Test
    fun mergedAndSplitZhParagraphsKeepSentencePairs() {
        val book = SyntheticBilingualCorpus.build()
        val zh = book.zhChapters.mapIndexed { c, paragraphs ->
            when (c) {
                // 第 1 章：把第 1、2 段合成一段（2 个英文段 ↔ 1 个中文段）
                1 -> buildList {
                    add(paragraphs[0])
                    add(paragraphs[1] + paragraphs[2])
                    addAll(paragraphs.drop(3))
                }
                // 第 3 章：把第 0 段按句拆成两段（1 个英文段 ↔ 2 个中文段）
                3 -> buildList {
                    val sentences = paragraphs[0].split('。').filter { it.isNotBlank() }
                    add(sentences.take(1).joinToString("") { "$it。" })
                    add(sentences.drop(1).joinToString("") { "$it。" })
                    addAll(paragraphs.drop(1))
                }
                else -> paragraphs
            }
        }
        val pairs = TranslationAligner.align(book.enChapters, zh, SyntheticBilingualCorpus.meaningIndex())
        val metrics = evaluate(pairs, book, book.markers)
        metrics.report("merge/split-paragraphs")
        assertEquals("段落合并/拆分不应破坏句级配对：${metrics.wrong.take(3)}", 1.0, metrics.precision, 0.0)
        assertEquals("段落合并/拆分后句编号应全部正确配对", 1.0, metrics.recall, 0.0)
    }

    @Test
    fun foreignAndStructuralParagraphsAreIgnored() {
        val book = SyntheticBilingualCorpus.build()
        val en = book.enChapters.mapIndexed { c, paragraphs ->
            if (c == 0) listOf("chapter heading", "...") + paragraphs else paragraphs
        }
        val zh = book.zhChapters.mapIndexed { c, paragraphs ->
            if (c == 0) paragraphs + "这是一段没有对应英文的中文段落。" else paragraphs
        }
        val pairs = TranslationAligner.align(en, zh, SyntheticBilingualCorpus.meaningIndex())
        val metrics = evaluate(pairs, book, book.markers)
        metrics.report("structural/foreign-paragraphs")
        assertEquals("标题行/纯标点/无对应中文段不应带偏正文：${metrics.wrong.take(3)}", 1.0, metrics.precision, 0.0)
        assertEquals("正文句编号应全部正确配对", 1.0, metrics.recall, 0.0)
    }

    /** 两条英文句译成一条中文句（2:1 句级合并）：合并对的两侧编号集合应当一致。 */
    @Test
    fun mergedZhSentenceKeepsBothMarkersTogether() {
        val book = SyntheticBilingualCorpus.build()
        val zh = book.zhChapters.mapIndexed { c, paragraphs ->
            if (c != 0) return@mapIndexed paragraphs
            paragraphs.mapIndexed { p, paragraph ->
                if (p != 0) return@mapIndexed paragraph
                val sentences = paragraph.split('。').filter { it.isNotBlank() }
                // 第 1、2 句合并成一句（去掉中间的句号），其余不动
                (listOf(sentences[0] + sentences[1]) + sentences.drop(2))
                    .joinToString("") { "$it。" }
            }
        }
        val pairs = TranslationAligner.align(book.enChapters, zh, SyntheticBilingualCorpus.meaningIndex())
        val metrics = evaluate(pairs, book, book.markers)
        metrics.report("2:1-merged-zh-sentence")
        assertEquals("2:1 合并对两侧编号集合应一致：${metrics.wrong.take(3)}", 1.0, metrics.precision, 0.0)
        assertEquals("合并后其余句编号应全部正确配对", 1.0, metrics.recall, 0.0)
    }

    // ---- 3. 词义锚点路径：没有编号时靠 ECDICT 式释义锚定 ---------------------

    @Test
    fun meaningAnchorsCarryAlignmentWithoutNumberMarkers() {
        val book = SyntheticBilingualCorpus.build(markerAt = { null })
        val pairs = TranslationAligner.align(book.enChapters, book.zhChapters, SyntheticBilingualCorpus.meaningIndex())
        assertPositionalTruth("无编号+词义锚点", pairs, book)
    }

    @Test
    fun meaningAnchorsSurviveTraditionalChinese() {
        val book = SyntheticBilingualCorpus.build(traditional = true, markerAt = { null })
        val pairs = TranslationAligner.align(book.enChapters, book.zhChapters, SyntheticBilingualCorpus.meaningIndex())
        assertPositionalTruth("无编号+繁体+词义锚点", pairs, book)
    }

    // ---- 4. 扰动传播半径：局部信息缺失不得扩散 -----------------------------

    @Test
    fun unmarkedWindowDamageStaysLocal() {
        val window = 10..12
        val book = SyntheticBilingualCorpus.build(
            markerAt = { if (it in window) null else "%04d".format(it + 1) }
        )
        val pairs = TranslationAligner.align(book.enChapters, book.zhChapters, SyntheticBilingualCorpus.meaningIndex())
        val metrics = evaluate(pairs, book, book.markers)
        metrics.report("unmarked-window(10..12)")
        assertEquals("窗口外的句对不得被带偏：${metrics.wrong.take(3)}", 1.0, metrics.precision, 0.0)
        assertEquals("窗口外的句编号应全部正确配对", 1.0, metrics.recall, 0.0)
        // 窗口内三句虽然丢了编号，也应各自配到真值句（长度/词义仍可判）
        for (i in window) {
            val pair = pairs.firstOrNull { it.enSentence == book.enSentences[i] }
            assertTrue("窗口内第 $i 句没有句级句对", pair != null)
            assertEquals("窗口内第 $i 句配对错误", book.zhSentences[i], pair!!.zhSentence)
        }
    }

    // ---- 工具 -------------------------------------------------------------

    private fun assertPositionalTruth(label: String, pairs: List<AlignedSentencePair>, book: SyntheticBilingualCorpus.Book) {
        val sentencePairs = pairs.filter { it.enSentence.isNotBlank() }
        println("[synthetic] %-28s 句级句对=%d 真值句数=%d".format(label, sentencePairs.size, book.enSentences.size))
        assertEquals("$label：句级句对数应等于真值句数", book.enSentences.size, sentencePairs.size)
        sentencePairs.forEachIndexed { i, pair ->
            assertEquals("$label：第 $i 句英文配对错误", book.enSentences[i], pair.enSentence)
            assertEquals("$label：第 $i 句中文配对错误", book.zhSentences[i], pair.zhSentence)
        }
    }

    private class Metrics(
        val sentencePairs: Int,
        val exactPairs: Int,
        val covered: Set<String>,
        val expected: Set<String>,
        val wrong: List<String>
    ) {
        val precision: Double get() = if (sentencePairs == 0) 0.0 else exactPairs.toDouble() / sentencePairs
        val recall: Double get() = if (expected.isEmpty()) 1.0 else covered.size.toDouble() / expected.size

        fun report(label: String) = println(
            "[synthetic] %-28s 句级句对=%d 完全正确=%d precision=%.3f recall=%.3f (覆盖 %d/%d) 错配=%d"
                .format(label, sentencePairs, exactPairs, precision, recall, covered.size, expected.size, wrong.size)
        )
    }

    /**
     * 一对正确 = 英文句里的编号集合与中文句里的编号集合完全相同且非空。
     *
     * 英文侧没有编号的句对（例如被抹掉编号的窗口）无法用编号验证，不计入分母；
     * 但它们若配上了一个带编号的中文句，说明错位，计为错配。
     */
    private fun evaluate(
        pairs: List<AlignedSentencePair>,
        book: SyntheticBilingualCorpus.Book,
        expected: Set<String>
    ): Metrics {
        var sentencePairs = 0
        var exact = 0
        val covered = mutableSetOf<String>()
        val wrong = mutableListOf<String>()
        for (pair in pairs) {
            if (pair.enSentence.isBlank()) continue
            val en = markersOf(pair.enSentence)
            val zh = markersOf(pair.zhSentence)
            if (en.isEmpty()) {
                if (zh.isNotEmpty()) wrong += "«${pair.enSentence}» → «${pair.zhSentence}»"
                continue
            }
            sentencePairs++
            if (en == zh) {
                exact++
                covered += en
            } else {
                wrong += "«${pair.enSentence}» → «${pair.zhSentence}»"
            }
        }
        return Metrics(sentencePairs, exact, covered, expected, wrong)
    }

    private fun markersOf(text: String): Set<String> = markerRegex.findAll(text).map { it.value }.toSet()
}
