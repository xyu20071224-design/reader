package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **决定性实验**：用**真值配对**（golden-samples）检验 V1 的 `recall` 上限。
 *
 * 之前的探针用「同序号/比例位置」配对，那是**错配**——recall 当然为 0。
 * 这个探针只用金标准里 `approved.level != MISS` 且能定位到真值中文句的样本，
 * 在**正确配对**上算 recall。
 *
 * ## 这是回答「ECDICT 到底行不行」的唯一干净方式
 *
 * 若在正确配对上 recall 仍然很低，那说明 ECDICT 的释义确实对不上文学译本
 * （因为这是「送分题」：配对已经给对了，只剩词义匹配）。
 */
class OraclePairRecallProbe {

    @Test
    fun recallOnGoldenTruePairs() {
        val inputs = AlignmentCorpusInputs.requireCase("F10")
        val enChapters = inputs.english
        val zhChapters = inputs.chinese
        val ecdict = inputs.requireDictionary()
        val source = BookSide.build(SideKind.SOURCE, enChapters)
        val target = BookSide.build(SideKind.TARGET, zhChapters)
        CorpusExecution.requireSides(source, target)
        val density = DensityModel(source)
        val structural = BooleanArray(source.chapterCount) { source.isStructural(it) }
        density.totalRatioFromChapters(target.chapterChars, source.chapterWords, structural)
        val verify = VerifyLayer(source, target, density, ecdict)
        verify.build()
        println("bridge: ${verify.notes}")

        // 读金标准：取 approved.zh（真值译文），在 target 里定位它
        val json = inputs.requireGolden()
        val reviews = ReviewedGoldenEvaluator.reviews(json, source, target)

        println()
        println("=== 在真值配对上的 V1 recall（送分题）===")
        val recalls = ArrayList<Double>(128)
        var located = 0
        var termsEmpty = 0
        val examples = ArrayList<String>(10)

        for (review in reviews) {
            if (review.level == "MISS") continue
            val targetSpan = requireNotNull(review.target)
            val level = review.level
            val english = ReviewedSpanLocator.text(source, review.source)
            located++
            val score = scoreReviewed(review, verify::score)
            println("GOLDEN_PAIR sourceFrom=${review.source.from} sourceSpan=${review.source.size} " +
                "targetFrom=${targetSpan.from} targetSpan=${targetSpan.size} level=$level")
            if (!score.available) {
                termsEmpty++
                continue
            }
            recalls.add(score.recall)
            if (examples.size < 8) {
                examples.add(
                    "[$level] recall=${String.format("%.3f", score.recall)} " +
                        "prec=${String.format("%.3f", score.precision)} " +
                        "lift=${String.format("%.3f", score.lift)} " +
                        "en=\"${english.take(34)}\""
                )
            }
        }
        recalls.sort()
        println("  金标准样本总数=${reviews.size}（完整span唯一定位，无前缀降级）")
        println("  成功定位两侧的真值对=$located  terms(E)=∅ 的=$termsEmpty")
        check(located > 0 && recalls.isNotEmpty()) { "No evaluable golden pair: located=$located available=${recalls.size}" }
        println("  V1 available 的真值对=${recalls.size}")
        println(
            "  **recall 分布：p10=${f(q(recalls, 0.1))} p50=${f(q(recalls, 0.5))} " +
                "p90=${f(q(recalls, 0.9))} max=${f(recalls.last())}**"
        )
        println(
            "  recall ≥ ${AlignTuning.VERIFY_RECALL_MIN} 的比例：" +
                "${recalls.count { it >= AlignTuning.VERIFY_RECALL_MIN }}/${recalls.size}"
        )
        println("  recall > 0 的比例：${recalls.count { it > 0.0 }}/${recalls.size}")
        println()
        println("  样例：")
        examples.forEach { println("    $it") }
        println()
        println("  → 这是**正确配对**上的 recall，排除了配对错误的影响。")
        println("    若这个数仍很低，说明 ECDICT 释义与文学译本措辞确实对不上（词典能力上限）。")
    }

    internal fun <T> scoreReviewed(review: ReviewedGoldenEvaluator.Review,
        score: (Int, Int, Int, Int) -> T): T {
        val target = requireNotNull(review.target) { "MISS is not a scored oracle pair" }
        return score(review.source.from, review.source.size, target.from, target.size)
    }

    private fun q(sorted: List<Double>, p: Double): Double =
        sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]

    private fun f(v: Double): String = String.format("%.3f", v)

}
