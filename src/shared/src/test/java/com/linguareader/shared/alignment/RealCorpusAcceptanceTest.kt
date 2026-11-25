package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **真实语料验收台**：在魔戒英中语料上跑本引擎，报覆盖率与金标准召回。
 *
 * ## 为什么必须有这个文件
 *
 * 合成语料只能证明「不产生错标」（安全性），证明不了「能用」。真实语料（13611 英文句 /
 * 9336 中文句 / 48 英文章 vs 38 中文章 / 全繁体）才是可用性证据。
 *
 * ## 指标
 *
 * | 指标 | 定义 |
 * | --- | --- |
 * | `coverage` | ALIGNED 覆盖的英文有效句 / 英文有效句总数（契约口径，用 `stats`） |
 * | `goldRecall` | 金标准 100 条里 `approved.level != MISS` 的英文有效句中，当前 ALIGNED 经独立真值核验正确的比例 |
 * | `falseAligned` | 当前 ALIGNED 与独立批准译文不符的英文句数（同时保留历史 bad 红线）（**红线**） |
 *
 * 金标准口径说明：`golden-samples.json` 的 `approved.level` 为 `MISS` = 人工确认无对照；
 * `SENTENCE` = 有句级对照；`PARAGRAPH` = 只有段级对照（可接受 ALIGNED 但不算精确召回）。
 */
class RealCorpusAcceptanceTest {

    @Test
    fun measureOnLordsOfTheRings() {
        val inputs = AlignmentCorpusInputs.requireCase("F11")
        val enChapters = inputs.english
        val zhChapters = inputs.chinese
        val ecdict = inputs.requireDictionary()
        println("=== 真实语料 ===")
        println("  en: ${enChapters.size} 章 / ${enChapters.sumOf { it.paragraphs.size }} 段")
        println("  zh: ${zhChapters.size} 章 / ${zhChapters.sumOf { it.paragraphs.size }} 段")
        println("  真 ECDICT（${ecdict.entryCount} 词条 / ${ecdict.lemmaCount} 词形）")

        val started = System.nanoTime()
        val document = IndependentAligner().align(enChapters, zhChapters, ecdict)
        CorpusExecution.requireDocument(document)
        val elapsed = (System.nanoTime() - started) / 1_000_000

        println()
        println("=== 结果（真 ECDICT）===")
        println("  stats: ${document.stats.summaryLine()}")
        println("  elapsed = ${elapsed}ms")
        println("  三态：ALIGNED=${document.stats.alignedUnits} " +
            "UNCERTAIN=${document.stats.uncertainUnits} " +
            "UNALIGNED=${document.stats.unalignedUnits}")
        val skipReasons = document.units
            .filter { it.skipReason != null }
            .groupingBy { it.skipReason!! }
            .eachCount()
        println("  skipReason: $skipReasons")
        println()
        println("  --- notes（前 12 条）---")
        document.notes.take(12).forEach { println("    $it") }

        println()
        println("=== 金标准重放 ===")
        replayGolden(inputs.requireGolden(), document, enChapters, zhChapters)

        // 非空执行与金标准错标红线均须通过；真实书覆盖率尚无已批准阈值。
        kotlin.test.assertTrue(
            document.units.isNotEmpty(),
            "真实语料上必须产出单元（不许静默丢句）"
        )
    }

    // ──────────────────────────────────────────────────────────────

    internal fun replayGolden(json: org.json.JSONObject, document: AlignmentDocument,
        source: List<AlignmentChapter>, target: List<AlignmentChapter>) {
        val metrics = ReviewedGoldenEvaluator.evaluate(json, document, source, target)
        println("  ${metrics.line()}")
        println("  Sentence recall counts independently reviewed source sentences; paragraph recall is separate.")
        metrics.assertPassed()
    }
}
