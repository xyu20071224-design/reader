package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * 临时诊断：定位 `missingTranslation` 变体上的那条错标（unit=62）。
 *
 * 核心问题：该英文句在**被删掉的第 2 段**里（真值上无译文），却被报 ALIGNED 指向别的段。
 * 要区分两种成因：
 * - (a) A（margin 无信息时不否决）放行过宽
 * - (b) 句级 DP 跨段落「借」了中文句
 *
 * 判据：看该单元的中文句是否与英文句**不同段落组**。
 */
class MissingTranslationProbe {

    @Test
    fun traceFalseAlignedUnit() {
        val book = SyntheticBilingualFixture.Variant.MISSING_TRANSLATION.generate()
        val document = IndependentAligner().align(
            book.source, book.translation, OracleLexicon(book)
        )
        val truth = book.truth

        println("=== note ===")
        document.notes.forEach { println("  $it") }
        println("=== stats: ${document.stats.summaryLine()} ===")

        // 逐条检查：英文句段落 vs 中文句段落
        println("=== 英文段 → 中文段 的配对分布（ALIGNED 单元）===")
        val pairCounts = LinkedHashMap<String, Int>()
        document.units.forEachIndexed { index, unit ->
            if (unit.status != AlignmentStatus.ALIGNED) return@forEachIndexed
            val sourceKey = unit.sourceText.substringAfterLast('\u00AB').substringBefore('\u00BB')
            val targetKey = unit.targetText.substringAfterLast('\u300C').substringBefore('\u300D')
            val sourceParagraph = unit.sourceParagraph.take(12)
            // 该中文句属于哪个中文段落？按文本在 translation 里定位
            val targetParagraphIndex = locateParagraph(book.translation, unit.targetText)
            val sourceParagraphIndex = locateParagraph(book.source, unit.sourceText)
            val key = "enP=$sourceParagraphIndex -> zhP=$targetParagraphIndex"
            pairCounts.merge(key, 1, Int::plus)
            if (unit.targetText.isNotEmpty()) {
                // 只打印那些「英文段与中文段索引差很大」的（跨段信号）
                if (sourceParagraphIndex >= 0 && targetParagraphIndex >= 0 &&
                    Math.abs(sourceParagraphIndex - targetParagraphIndex) > 1
                ) {
                    println(
                        "  !! 跨段：unit=$index enP=$sourceParagraphIndex zhP=$targetParagraphIndex " +
                            "enKey=$sourceKey zhKey=$targetKey " +
                            "en=\"${unit.sourceText.take(40)}\" zh=\"${unit.targetText.take(30)}\""
                    )
                }
            }
        }
        pairCounts.entries.sortedByDescending { it.value }.take(20).forEach { (key, count) ->
            println("  $key × $count")
        }

        // 定位真值上「无译文」的英文句，看它们被报成了什么
        println("=== 真值无译文的英文句（应当 UNALIGNED）===")
        document.units.forEachIndexed { index, unit ->
            val key = AlignmentProjection
                .sourceKeysIn(unit.sourceText, truth.sourceMarkerUniverse)
                .firstOrNull() ?: return@forEachIndexed
            if (key !in truth.untranslatedSourceKeys) return@forEachIndexed
            if (unit.status == AlignmentStatus.ALIGNED) {
                println(
                    "  !! unit=$index status=${unit.status} skip=${unit.skipReason} " +
                        "en=\"${unit.sourceText.take(46)}\" zh=\"${unit.targetText.take(36)}\" " +
                        "z=${r(unit.evidence.lengthDeviation)} " +
                        "drift=${r(unit.evidence.positionDrift)} " +
                        "verify=${r(unit.evidence.verifyScore)} " +
                        "why=${unit.evidence.reasons}"
                )
            }
        }

        // 统计真值无译文句的状态分布
        val statusOfUntranslated = LinkedHashMap<AlignmentStatus, Int>()
        document.units.forEach { unit ->
            val key = AlignmentProjection
                .sourceKeysIn(unit.sourceText, truth.sourceMarkerUniverse)
                .firstOrNull() ?: return@forEach
            if (key !in truth.untranslatedSourceKeys) return@forEach
            statusOfUntranslated.merge(unit.status, 1, Int::plus)
        }
        println("  真值无译文句的状态分布：$statusOfUntranslated")
    }

    /** 该文本落在第几个段落（全局段序）。找不到返回 -1。 */
    private fun locateParagraph(chapters: List<AlignmentChapter>, text: String): Int {
        var paragraphIndex = 0
        for (chapter in chapters) {
            for (paragraph in chapter.paragraphs) {
                if (paragraph.contains(text) || text.contains(paragraph)) return paragraphIndex
                paragraphIndex++
            }
        }
        return -1
    }

    private fun r(v: Double): Double =
        if (v == Double.MAX_VALUE) -1.0 else Math.round(v * 1000.0) / 1000.0
}
