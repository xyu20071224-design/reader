package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import kotlin.test.Test

/** 临时诊断：量 evaluator fixture 的句长方差与 margin 分布，判定 G3 是否过严。 */
class MarginProbe {

    @Test
    fun measureMarginAndLengthVariance() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val source = BookSide.build(SideKind.SOURCE, book.source)
        val target = BookSide.build(SideKind.TARGET, book.translation)

        println("=== 句长分布（evaluator fixture base）===")
        val enWords = (0 until source.sentCount).filter { source.sentLangUnit[it] }
            .map { source.sentWords[it] }
        val zhChars = (0 until target.sentCount).filter { target.sentLangUnit[it] }
            .map { target.sentChars[it] }
        println("  enWords: n=${enWords.size} min=${enWords.min()} max=${enWords.max()} " +
            "distinct=${enWords.toSet().size} ${distribution(enWords)}")
        println("  zhChars: n=${zhChars.size} min=${zhChars.min()} max=${zhChars.max()} " +
            "distinct=${zhChars.toSet().size} ${distribution(zhChars)}")

        // 直接算「正确配对」与「邻位替代」的结构代价差
        val density = DensityModel(source)
        val structural = BooleanArray(source.chapterCount) { source.isStructural(it) }
        density.totalRatioFromChapters(target.chapterChars, source.chapterWords, structural)
        val anchors = AnchorLayer(source, target, density)
        anchors.build()
        val chapters = ChapterLayer(source, target, anchors, density)
        chapters.run()
        density.computeBook(chapters.acceptedPairChars, chapters.acceptedPairWords)

        println("=== 句级结构代价：正确对 vs 邻位替代（前 15 句）===")
        println("  rBook=${Math.round(density.rBook * 1000) / 1000.0}")
        val costs = ArrayList<Double>()
        for (i in 0 until minOf(15, source.sentCount)) {
            val chosen = simpleCost(source, target, density, i, i)
            val altMinus2 = if (i - 2 >= 0) simpleCost(source, target, density, i, i - 2) else null
            val altMinus1 = if (i - 1 >= 0) simpleCost(source, target, density, i, i - 1) else null
            val altPlus1 = if (i + 1 < target.sentCount) simpleCost(source, target, density, i, i + 1) else null
            val alternatives = listOfNotNull(altMinus2, altMinus1, altPlus1)
            val best = alternatives.minOrNull() ?: Double.NaN
            val margin = if (alternatives.isEmpty()) Double.NaN else best - chosen
            if (!margin.isNaN()) costs.add(margin)
            println(
                "  i=$i en=${source.sentWords[i]}w zh=${target.sentChars[i]}c " +
                    "chosen=${r(chosen)} altMin=${r(best)} margin=${r(margin)}"
            )
        }
        if (costs.isNotEmpty()) {
            val sorted = costs.sorted()
            println(
                "=== margin 分布（n=${sorted.size}）: " +
                    "p10=${r(sorted[(sorted.size * 0.1).toInt()])} " +
                    "p50=${r(sorted[sorted.size / 2])} " +
                    "p90=${r(sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.lastIndex)])} " +
                    "max=${r(sorted.last())}  (MARGIN_MIN=${AlignTuning.MARGIN_MIN})"
            )
        }
    }

    /** 与 `PairingEngine.pairCost` 同构的最简版（只算长度 + 位置，不含锚点）。 */
    private fun simpleCost(
        source: BookSide, target: BookSide, density: DensityModel,
        i: Int, j: Int
    ): Double {
        val words = source.sentWords[i]
        val chars = target.sentChars[j]
        val rho = density.observedRatio(words, chars)
        val sigma = density.sigmaFor(words)
        val z = Math.abs(Math.log(rho) - Math.log(density.rBook)) / sigma
        val lengthTerm = minOf(1.0, (z / AlignTuning.Z_CAP) * (z / AlignTuning.Z_CAP))
        val posI = i.toDouble() / source.sentCount
        val posJ = j.toDouble() / target.sentCount
        return lengthTerm + AlignTuning.W_POSITION * Math.abs(posI - posJ)
    }

    private fun distribution(values: List<Int>): String {
        val grouped = values.groupingBy { it }.eachCount().entries.sortedBy { it.key }
        return grouped.joinToString(", ") { "${it.key}×${it.value}" }
    }

    private fun r(v: Double): Any =
        if (v.isNaN() || v == Double.MAX_VALUE) "n/a" else Math.round(v * 10000.0) / 10000.0
}
