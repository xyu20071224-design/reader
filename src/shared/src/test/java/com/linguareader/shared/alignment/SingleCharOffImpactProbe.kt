package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * 诊断：关掉单字匹配后，合成语料上的 `coverableRatio` / `lexicalChannelUsable` 分布。
 *
 * 目的是解释 4 条回归测试的成因，而不是猜。
 */
class SingleCharOffImpactProbe {

    @Test
    fun measureCoverableRatioOnSynthetic() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val lexicon = SyntheticBilingualFixture.truthLexicon()
        val provider = MeaningProvider { word -> lexicon[word.lowercase()] ?: emptyList() }
        val source = BookSide.build(SideKind.SOURCE, book.source)
        val target = BookSide.build(SideKind.TARGET, book.translation)
        val density = DensityModel(source)
        val structural = BooleanArray(source.chapterCount) { source.isStructural(it) }
        density.totalRatioFromChapters(target.chapterChars, source.chapterWords, structural)
        val verify = VerifyLayer(source, target, density, provider)
        verify.build()

        println("=== single-char OFF 下的桥与可查率 ===")
        println("  notes=${verify.notes}")
        println("  lexicalAvailable=${verify.lexicalAvailable}")

        var belowGuard = 0
        var langUnits = 0
        val ratios = ArrayList<Double>()
        for (s in 0 until source.sentCount) {
            if (!source.sentLangUnit[s]) continue
            langUnits++
            val ratio = verify.coverableRatio(s)
            ratios.add(ratio)
            if (ratio < AlignTuning.MIN_COVERABLE_RATIO) belowGuard++
        }
        ratios.sort()
        println("  英文有效句=$langUnits")
        println(
            "  coverableRatio: p10=${f(q(ratios, 0.1))} p50=${f(q(ratios, 0.5))} " +
                "p90=${f(q(ratios, 0.9))}"
        )
        println(
            "  低于护栏 ${AlignTuning.MIN_COVERABLE_RATIO} 的句数=$belowGuard/$langUnits " +
                "(${Math.round(belowGuard * 1000.0 / langUnits) / 10.0}%)"
        )

        // 实际跑一遍，看 marginal 案例的 reasons
        val document = IndependentAligner().align(book.source, book.translation, provider)
        println()
        println("  stats: ${document.stats.summaryLine()}")
        val hist = LinkedHashMap<String, Int>()
        document.units.forEach { unit ->
            if (unit.status == AlignmentStatus.ALIGNED) return@forEach
            val why = when (unit.status) {
                AlignmentStatus.UNALIGNED -> "UNALIGNED:${unit.skipReason}"
                else -> (unit.evidence.reasons.firstOrNull() ?: "?").substringBefore('=').substringBefore(' ')
            }
            hist.merge(why, 1, Int::plus)
        }
        hist.entries.sortedByDescending { it.value }.forEach { (k, v) -> println("    $k -> $v") }
        val withTarget = document.units.count { it.targetText.isNotEmpty() }
        println("  targetText 非空的单元=$withTarget")
    }

    private fun q(sorted: List<Double>, p: Double): Double =
        if (sorted.isEmpty()) 0.0
        else sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]

    private fun f(v: Double): String = String.format("%.3f", v)
}
