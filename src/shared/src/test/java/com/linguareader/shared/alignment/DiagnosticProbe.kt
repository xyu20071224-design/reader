package com.linguareader.shared.alignment

import kotlin.test.Test

/** 临时诊断：在**评估台的语料**（`SyntheticBilingualFixture`）上定位为什么不产出 ALIGNED。 */
class DiagnosticProbe {

    @Test
    fun probeEvaluatorFixtureWithLexicon() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val document = IndependentAligner().align(
            book.source, book.translation, OracleLexicon(book)
        )
        println("=== NOTES (evaluator fixture, oracle lexicon) ===")
        document.notes.forEach { println("  $it") }
        println("=== UNITS（前 12 条）===")
        document.units.take(12).forEach {
            println(
                "  [${it.status}] ${it.skipReason ?: ""} en=\"${it.sourceText.take(44)}\" " +
                    "zh=\"${it.targetText.take(24)}\" z=${r(it.evidence.lengthDeviation)} " +
                    "drift=${r(it.evidence.positionDrift)} verify=${r(it.evidence.verifyScore)} " +
                    "why=${it.evidence.reasons}"
            )
        }
        println("=== STATS === ${document.stats.summaryLine()}")

        // 直接探测验证层：对第一句算 V1 三向打分
        val source = BookSide.build(SideKind.SOURCE, book.source)
        val target = BookSide.build(SideKind.TARGET, book.translation)
        val density = DensityModel(source)
        val structural = BooleanArray(source.chapterCount) { source.isStructural(it) }
        density.totalRatioFromChapters(target.chapterChars, source.chapterWords, structural)
        val anchors = AnchorLayer(source, target, density)
        anchors.build()
        val chapters = ChapterLayer(source, target, anchors, density)
        chapters.run()
        density.computeBook(chapters.acceptedPairChars, chapters.acceptedPairWords)
        density.computeSeed(anchors.pinResiduals(), target.chapterChars, target.chapterWords)
        println("=== CHAPTER LAYER ===")
        println("  rTotal=${r(density.rTotal)} rBook=${r(density.rBook)} sigma=${r(density.sigma1)}")
        println("  notes=${chapters.notes}")
        val verify = VerifyLayer(source, target, density, OracleLexicon(book))
        verify.build()
        println("  verify.notes=${verify.notes}")
        println("  rConfidenceLow=${density.rConfidenceLow}")
        println("=== 头 6 对 (i,i) 的 V1/V2 ===")
        for (i in 0 until minOf(6, source.sentCount, target.sentCount)) {
            val score = verify.score(i, 1, i, 1)
            val discourse = verify.discourse(i, 1, i, 1)
            println(
                "  i=$i terms=${verify.termsAvailable(i)} recall=${r(score.recall)} " +
                    "precision=${r(score.precision)} lift=${r(score.lift)} " +
                    "available=${score.available} v2witness=${discourse.witness} " +
                    "v2conflict=${discourse.conflict}"
            )
        }
    }

    private fun r(v: Double): Double =
        if (v == Double.MAX_VALUE) -1.0 else Math.round(v * 1000.0) / 1000.0
}
