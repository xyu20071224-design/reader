package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **覆盖率诊断**（Lead 要求，纯诊断，不改任何阈值）。
 *
 * 回答一个问题：**这个语料本身可分吗？**
 *
 * ```
 * sep(s) = score(s, t_true) − max_{t_other ≠ t_true} score(s, t_other)
 * ```
 *
 * `score` 用 **V1 通道自己的打分**（IDF recall），**不用 DP 代价**——用 DP 代价就是自证。
 *
 * 判据（Lead 给的）：
 * - `sep ≤ 0` 占比 > 40% → **语料不可分**，覆盖率低是语料问题，禁止动算法
 * - `sep ≤ 0` 占比低但覆盖率仍低 → **算法过保守**，问题在门
 *
 * 同时量三个嫌疑点（A: margin 门、B: z 与 σ 耦合、C: 合并单元）的占比。
 */
class CoverageDiagnosisProbe {

    @Test
    fun intrinsicSeparability() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val lexicon = OracleLexicon(book)
        val source = BookSide.build(SideKind.SOURCE, book.source)
        val target = BookSide.build(SideKind.TARGET, book.translation)
        val density = DensityModel(source)
        val structural = BooleanArray(source.chapterCount) { source.isStructural(it) }
        density.totalRatioFromChapters(target.chapterChars, source.chapterWords, structural)
        val verify = VerifyLayer(source, target, density, lexicon)
        verify.build()

        val truth = book.truth
        // 真值：英文句下标 → 真值中文句下标
        val trueTarget = IntArray(source.sentCount) { -1 }
        for (s in 0 until source.sentCount) {
            val key = AlignmentProjection
                .sourceKeysIn(source.sentText[s], truth.sourceMarkerUniverse)
                .firstOrNull() ?: continue
            val unitId = truth.trueTargetUnitsOf(key).firstOrNull() ?: continue
            val marker = truth.targetUnits[unitId].markers.firstOrNull() ?: continue
            trueTarget[s] = targetIndexOfMarker(target, marker)
        }

        println("=== base 内禀区分度（V1 recall，不用 DP 代价）===")
        println("  verify.notes=${verify.notes}")
        val separations = ArrayList<Double>()
        var notSeparable = 0
        var evaluated = 0
        for (s in 0 until source.sentCount) {
            val tTrue = trueTarget[s]
            if (tTrue < 0) continue
            val scoreTrue = verify.score(s, 1, tTrue, 1)
            if (!scoreTrue.available) continue
            evaluated++
            var bestOther = 0.0
            for (t in 0 until target.sentCount) {
                if (t == tTrue) continue
                val score = verify.score(s, 1, t, 1)
                if (score.available && score.recall > bestOther) bestOther = score.recall
            }
            val separation = scoreTrue.recall - bestOther
            separations.add(separation)
            if (separation <= 0.0) notSeparable++
        }
        separations.sort()
        println("  评测句数=$evaluated（V1 available）")
        println(
            "  sep 分布：p10=${fmt(pct(separations, 0.1))} p50=${fmt(pct(separations, 0.5))} " +
                "p90=${fmt(pct(separations, 0.9))} max=${fmt(pct(separations, 1.0))}"
        )
        val notSeparableRate = if (evaluated == 0) 0.0 else notSeparable.toDouble() / evaluated
        println("  sep ≤ 0 的句子：$notSeparable/$evaluated = ${fmt(notSeparableRate * 100)}%")
        println(
            "  → 判据：> 40% 说明语料不可分（禁止动算法）；明显低于 40% 说明算法过保守"
        )
    }

    @Test
    fun blockerBreakdown() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val document = IndependentAligner().align(
            book.source, book.translation, OracleLexicon(book)
        )
        println("=== base 非 ALIGNED 单元的阻断门占比 ===")
        println("  ${document.stats.summaryLine()}")
        val total = document.units.size
        val blocks = LinkedHashMap<String, Int>()
        document.units.forEach { unit ->
            if (unit.status == AlignmentStatus.ALIGNED) return@forEach
            val why = when (unit.status) {
                AlignmentStatus.UNALIGNED -> "UNALIGNED:${unit.skipReason}"
                else -> (unit.evidence.reasons.firstOrNull() ?: "?").substringBefore('=').substringBefore(' ')
            }
            blocks.merge(why, 1, Int::plus)
        }
        blocks.entries.sortedByDescending { it.value }.forEach { (why, count) ->
            println("  ${why.padEnd(46)} : ${count.toString().padStart(3)}  (${fmt(count * 100.0 / total)}%)")
        }

        // 嫌疑 A：margin < 0.35 占比（在非 ALIGNED 里）
        // 嫌疑 B：z 分布
        val nonAligned = document.units.filter { it.status != AlignmentStatus.ALIGNED }
        val margins = nonAligned.map { it.evidence.lengthDeviation }.filter { it != Double.MAX_VALUE }
        println()
        println("=== 嫌疑 B：z（lengthDeviation）分布 ===")
        if (margins.isNotEmpty()) {
            val sorted = margins.sorted()
            println(
                "  n=${sorted.size} p10=${fmt(sorted[(sorted.size * 0.1).toInt()])} " +
                    "p50=${fmt(sorted[sorted.size / 2])} p90=${fmt(sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.lastIndex)])}"
            )
            val overLimit = sorted.count { it > AlignTuning.Z_LIMIT_SINGLE }
            println(
                "  z > Z_LIMIT_SINGLE(${AlignTuning.Z_LIMIT_SINGLE}) 的比例：" +
                    "$overLimit/${sorted.size} = ${fmt(overLimit * 100.0 / sorted.size)}%"
            )
            val overTight = sorted.count { it > AlignTuning.Z_TIGHT }
            println(
                "  z > Z_TIGHT(${AlignTuning.Z_TIGHT}) 的比例：" +
                    "$overTight/${sorted.size} = ${fmt(overTight * 100.0 / sorted.size)}%"
            )
        }
        println("  σ=${fmt(document.notes.firstOrNull { it.startsWith("sigma=") }?.substringAfter("sigma=")?.takeWhile { it != ' ' }?.toDoubleOrNull() ?: 0.35)}")
    }

    @Test
    fun mergedSpanBreakdown() {
        val book = SyntheticBilingualFixture.Variant.SENTENCE_MERGE.generate()
        val document = IndependentAligner().align(
            book.source, book.translation, OracleLexicon(book)
        )
        println("=== 嫌疑 C：sentenceMerge 的合并单元 ===")
        println("  ${document.stats.summaryLine()}")
        val total = document.units.size
        val blocks = LinkedHashMap<String, Int>()
        document.units.forEach { unit ->
            if (unit.status == AlignmentStatus.ALIGNED) return@forEach
            val why = when (unit.status) {
                AlignmentStatus.UNALIGNED -> "UNALIGNED:${unit.skipReason}"
                else -> (unit.evidence.reasons.firstOrNull() ?: "?").substringBefore('=').substringBefore(' ')
            }
            blocks.merge(why, 1, Int::plus)
        }
        blocks.entries.sortedByDescending { it.value }.forEach { (why, count) ->
            println("  ${why.padEnd(46)} : ${count.toString().padStart(3)}  (${fmt(count * 100.0 / total)}%)")
        }
        val mergedNeedsLexical = document.units.count { unit ->
            unit.status != AlignmentStatus.ALIGNED &&
                unit.evidence.reasons.any { it.contains("merged span needs lexical witness") }
        }
        println()
        println(
            "  `merged span needs lexical witness` 占非 ALIGNED 的：" +
                "$mergedNeedsLexical/${document.units.count { it.status != AlignmentStatus.ALIGNED }}"
        )
        val mergedUnits = document.units.count { it.targetSpanSize > 1 }
        println("  targetSpanSize > 1 的单元数：$mergedUnits / $total")
        println("  stats.mergedUnits（按 DP 边）：${document.stats.mergedUnits}")
    }

    // ──────────────────────────────────────────────────────────────

    private fun targetIndexOfMarker(target: BookSide, marker: String): Int {
        for (t in 0 until target.sentCount) {
            if (target.sentText[t].contains(marker)) return t
        }
        return -1
    }

    private fun pct(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        return sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]
    }

    private fun fmt(v: Double): String = (Math.round(v * 1000.0) / 1000.0).toString()
}
