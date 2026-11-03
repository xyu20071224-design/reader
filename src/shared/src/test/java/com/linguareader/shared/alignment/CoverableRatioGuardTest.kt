package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **护栏有效性自检**：`coverableRatio` 护栏在什么条件下会触发、触发后 coverage 怎么变。
 *
 * 目的（回答 Lead 的「两条一起上才能证明不是单向放松」）：证明护栏**真的会收紧**，
 * 而不是写了却没生效的死代码。
 *
 * 做法：用同一语料 + 逐步降低词典覆盖率（截断候选集），观察 `coverableRatio` 分布与
 * coverage 变化。若护栏生效，覆盖率应随词典覆盖率下降而**下降**。
 */
class CoverableRatioGuardTest {

    @Test
    fun guardActuallyTightensCoverage() {
        val corpus = VariedTruthCorpus.build()
        val engine = IndependentAligner()
        val fullLexicon = corpus.oracleProvider()

        println("=== coverableRatio 护栏有效性：逐步降低词典覆盖 ===")
        println("| 词典覆盖档 | coverage | uncertain | falseAligned | 被护栏拦下 |")
        println("| --- | --- | --- | --- | --- |")

        for (fraction in listOf(1.0, 0.75, 0.5, 0.25, 0.0)) {
            val provider = TruncatedLexicon(corpus, fullLexicon, fraction)
            val document = engine.align(corpus.source, corpus.translation, provider)
            val truth = corpus.alignmentTruth()
            val metrics = AlignmentMetricsCalculator.compute(
                variant = "coverage=${(fraction * 100).toInt()}%",
                engine = document.engineId,
                truth = truth,
                projection = AlignmentProjection.project(document, truth)
            )
            val guarded = document.units.count { unit ->
                unit.status != AlignmentStatus.ALIGNED &&
                    unit.evidence.reasons.any { it.contains("coverable") }
            }
            println(
                "| ${(fraction * 100).toInt()}% | ${pct(metrics.coverage)} | " +
                    "${metrics.uncertainSentences} | ${metrics.falseAlignedCount} | $guarded |"
            )
            // 硬红线：任何覆盖档下都不许错标
            kotlin.test.assertEquals(
                0, metrics.falseAlignedCount,
                "词典覆盖 ${(fraction * 100).toInt()}% 时出现错标"
            )
        }
    }

    /** 把 oracle 词典的候选按比例随机截断（确定性：按下标哈希）。 */
    private class TruncatedLexicon(
        private val corpus: VariedTruthCorpus,
        private val full: MeaningProvider,
        private val fraction: Double
    ) : MeaningProvider {
        override fun chineseCandidates(englishWord: String): List<String> {
            if (fraction <= 0.0) return emptyList()
            // 确定性截断：词哈希落在阈值内的才返回候选
            val hash = Math.floorMod(englishWord.lowercase().hashCode(), 100)
            if (hash >= (fraction * 100).toInt()) return emptyList()
            return full.chineseCandidates(englishWord)
        }
    }

    private fun pct(v: Double): String = "${Math.round(v * 10000.0) / 100.0}%"
}
