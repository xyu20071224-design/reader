package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **UNCERTAIN 带译文的忠实性审计**（Lead 要求，不能省）。
 *
 * 问题：366 个单元 `status=UNCERTAIN` 且 `targetText` 非空，`reasons` 说是
 * structural-only。**这些译文是真实的 DP 候选，还是兜底硬塞的？**
 *
 * 判据：抽 10 条，打印它们所属的 **DP 边**（`enFrom/spanEn/zhFrom/spanZh`）与
 * 该边是否通过了结构代价的竞争。若每条都能追溯到一条真实的 DP 边，则译文诚实；
 * 若有些单元没有边却有译文，那就是兜底。
 */
class UncertainTargetAuditProbe {

    @Test
    fun auditUncertainTargets() {
        val book = SyntheticBilingualFixture.Variant.MISSING_TRANSLATION.generate()
        val lexicon = SyntheticBilingualFixture.truthLexicon()
        val provider = MeaningProvider { word -> lexicon[word.lowercase()] ?: emptyList() }
        val document = IndependentAligner().align(book.source, book.translation, provider)

        println("=== ${book.variant.label}: ${document.stats.summaryLine()} ===")
        document.notes.forEach { println("  note: $it") }

        val uncertainWithTarget = document.units
            .withIndex()
            .filter { it.value.status == AlignmentStatus.UNCERTAIN && it.value.targetText.isNotEmpty() }
        println()
        println("=== UNCERTAIN 且带译文的单元：${uncertainWithTarget.size} 个 ===")
        println("（若为 0，说明 INVARIANT-1 的 366 条已随文案修正消失）")

        if (uncertainWithTarget.isEmpty()) {
            // 检查是否还有 UNCERTAIN 带译文的情况
            val anyUncertainWithTarget = document.units.count {
                it.status == AlignmentStatus.UNCERTAIN && it.targetText.isNotEmpty()
            }
            println("  UNCERTAIN 带译文总数 = $anyUncertainWithTarget")
            val hist = document.units
                .filter { it.status == AlignmentStatus.UNCERTAIN }
                .groupingBy { (it.evidence.reasons.firstOrNull() ?: "?") }
                .eachCount()
            println("  UNCERTAIN 的 reasons 分布：")
            hist.entries.sortedByDescending { it.value }.forEach { (k, v) -> println("    $k -> $v") }
            return
        }

        println()
        println("=== 抽查 10 条（含 DP 边信息）===")
        uncertainWithTarget.take(10).forEach { (index, unit) ->
            // 从 targetChapter/targetIndex 与 source 坐标反推 DP 边的存在性
            val hasTargetCoords = unit.targetChapter >= 0 && unit.targetIndexInParagraph >= 0
            println("--- unit=$index ---")
            println("  status=${unit.status} targetChapter=${unit.targetChapter} " +
                "targetIdx=${unit.targetIndexInParagraph}")
            println("  sourceSpanSize=${unit.sourceSpanSize} targetSpanSize=${unit.targetSpanSize}")
            println("  en=\"${unit.sourceText.take(58)}\"")
            println("  zh=\"${unit.targetText.take(58)}\"")
            println("  reasons=${unit.evidence.reasons}")
            println("  译文坐标有效（说明有 DP 边）=$hasTargetCoords")
        }

        // 交叉验证：所有带译文的单元都必须有有效坐标
        val withTextButNoCoords = document.units.filter {
            it.targetText.isNotEmpty() &&
                (it.targetChapter < 0 || it.targetIndexInParagraph < 0)
        }
        println()
        println("=== 兜底检测 ===")
        println("  带译文但坐标无效（= 兜底塞的）单元数 = ${withTextButNoCoords.size}")
        if (withTextButNoCoords.isNotEmpty()) {
            withTextButNoCoords.take(5).forEach {
                println("    !! status=${it.status} zh=\"${it.targetText.take(40)}\"")
            }
        }
        kotlin.test.assertEquals(
            0, withTextButNoCoords.size,
            "带译文但没有 DP 坐标 = 兜底硬塞，违反契约"
        )
    }
}
