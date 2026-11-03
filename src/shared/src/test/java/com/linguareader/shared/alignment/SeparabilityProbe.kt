package com.linguareader.shared.alignment

/**
 * **语料内禀可分性诊断**（Lead 要求的关键诊断）。
 *
 * ## 要回答的问题
 *
 * 覆盖率只有 0.10–0.38，离 0.95 很远。这到底是：
 *
 * - **算法太保守**（语料可分，只是决策门把它们拒了）——那就该调算法；还是
 * - **语料本身不可分**（任何完美算法都区分不开）——那就该改语料。
 *
 * ## 判据：区分度（discrimination）
 *
 * 对每个英文句 `s`，真值中文句 `t_true`，其余中文句 `t_other`：
 *
 * ```
 * discrimination(s) = score(s, t_true) − max_{t_other} score(s, t_other)
 * ```
 *
 * `≤ 0` 意味着**真值配对的得分不高于某个错误配对**——在内容通道上，这句根本没法
 * 靠词典命中模式辨认出来。这种句子再多，算法也只能靠猜。
 *
 * ## 为什么用 V1 通道自己的打分，不用 DP 代价
 *
 * 用算法内部的 DP 代价算区分度是**自证**：算法觉得可分的，当然可分；算法觉得不可分的，
 * 也只是它自己的偏好。这里直接用 [VerifyLayer.score]（IDF 加权 recall/precision），
 * 那是**内容证据本身**，独立于任何决策门与 DP 路径。
 *
 * ## 注意一处保守取值
 *
 * V1 打分需要「配对关系」作为输入（`sourceFrom/targetFrom`）。这里枚举的是
 * **句级 1:1** 全部组合，不做合并跨度——所以对 `sentenceMerge` 这类变体，这个诊断
 * 只反映 1:1 视角的可分性，不反映合并视角。`base` 变体是纯 1:1，适用性最好
 * （Lead 也指定了 `base`）。
 */
internal class SeparabilityProbe(
    private val book: SyntheticBilingualFixture.FixtureBook,
    private val lexicon: Map<String, List<String>>
) {

    /**
     * 一个变体的可分性统计。
     *
     * @param perSentence 每句的区分度（按真值英文句顺序）
     */
    class Result(
        val variant: String,
        val perSentence: List<SentenceDiscrimination>,
        /** 可算分的句数（有些句子 V1 无词条可用，无法打分）。 */
        val scoredSentences: Int,
        val totalSentences: Int
    ) {
        val values: List<Double> = perSentence.map { it.discrimination }.sorted()

        fun percentile(p: Double): Double {
            if (values.isEmpty()) return Double.NaN
            val index = ((values.size - 1) * p).toInt().coerceIn(0, values.size - 1)
            return values[index]
        }

        /** 区分度 ≤ 0 的句子占比——**核心指标**：真值配对并不比错误配对得分高。 */
        val nonPositiveRate: Double =
            if (values.isEmpty()) 0.0 else values.count { it <= 0.0 }.toDouble() / values.size

        fun line(): String = buildString {
            append("SEPARABILITY variant=").append(variant)
            append(" scored=").append(scoredSentences).append('/').append(totalSentences)
            append(" p10=").append(fmt(percentile(0.10)))
            append(" p50=").append(fmt(percentile(0.50)))
            append(" p90=").append(fmt(percentile(0.90)))
            append(" min=").append(fmt(values.minOrNull() ?: Double.NaN))
            append(" max=").append(fmt(values.maxOrNull() ?: Double.NaN))
            append(" nonPositiveRate=").append(fmt(nonPositiveRate))
            append('(').append(values.count { it <= 0.0 }).append('/').append(values.size).append(')')
        }

        /** 人话结论：这套语料在内容通道上到底分不分得开。 */
        fun verdict(): String = when {
            values.isEmpty() -> "无法判定：没有可打分的句子"
            nonPositiveRate > 0.40 -> "**语料不可分**：${
                Math.round(nonPositiveRate * 10000.0) / 100.0
            }% 的句子真值与错误配对打平或更差 → 覆盖率低是语料问题，不是算法保守"
            nonPositiveRate > 0.20 -> "**语料边缘可分**：${
                Math.round(nonPositiveRate * 10000.0) / 100.0
            }% 打平 → 语料区分度不足，是覆盖率偏低的部分原因"
            else -> "**语料可分**：仅 ${
                Math.round(nonPositiveRate * 10000.0) / 100.0
            }% 打平 → 覆盖率低的主因在算法侧（决策门过严或证据利用不足）"
        }

        private fun fmt(v: Double): String =
            if (v.isNaN()) "NaN" else (Math.round(v * 10000.0) / 10000.0).toString()
    }

    /** 单句的区分度明细（用于打印「最不可分的那几句」）。 */
    class SentenceDiscrimination(
        val sourceKey: String,
        val sourceText: String,
        /** 真值中文句的 V1 得分。 */
        val trueScore: Double,
        /** 最好的错误配对的 V1 得分。 */
        val bestOtherScore: Double,
        val bestOtherTarget: String
    ) {
        val discrimination: Double = trueScore - bestOtherScore

        fun line(): String = buildString {
            append("  NONPOSITIVE discrimination=").append(fmt(discrimination))
            append(" true=").append(fmt(trueScore))
            append(" bestOther=").append(fmt(bestOtherScore))
            append(" src=\"").append(sourceText.take(76)).append('"')
            append(" bestOtherTarget=\"").append(bestOtherTarget.take(60)).append('"')
        }

        private fun fmt(v: Double): String = (Math.round(v * 10000.0) / 10000.0).toString()
    }

    /**
     * 计算指定变体的可分性。
     *
     * 实现要点：**直接复用引擎自己的 `TextPipeline` + `VerifyLayer`**，不另写一套打分。
     * 这样量到的是「引擎在内容通道上能拿到多少可用信号」，而不是我重写的近似物。
     */
    fun run(variant: SyntheticBilingualFixture.Variant): Result {
        val source = book.source
        val translation = book.translation
        val sourceSide = BookSide.build(SideKind.SOURCE, source)
        val targetSide = BookSide.build(SideKind.TARGET, translation)

        // 密度模型只影响长度先验，这里给一个「假设 1:1 章节配对」的估计即可——
        // 可分性诊断用的是**内容通道**（V1），不依赖章节对齐的质量。
        val pairCount = minOf(sourceSide.chapterCount, targetSide.chapterCount)
        val pairWords = LongArray(pairCount) { i ->
            translation[i].paragraphs.sumOf { it.split(' ').size.toLong() }
        }
        val pairChars = LongArray(pairCount) { i ->
            translation[i].paragraphs.sumOf { p -> p.count { c -> c in '\u3400'..'\u9FFF' }.toLong() }
        }
        val density = DensityModel(sourceSide)
        density.computeBook(pairChars, pairWords)

        val provider = MeaningProvider { word -> lexicon[word.lowercase()] ?: emptyList() }
        val verify = VerifyLayer(sourceSide, targetSide, density, provider)
        verify.build()

        // 真值：英文句 i 的中文句下标。base 变体是纯 1:1，按写顺序对齐。
        // ── 真值定位：**按编码查表，不按位置** ─────────────────────────
        //
        // 初版这里用「真值单元下标 == 中文句下标」的位置假设，实测把对照语料的区分度
        // 全算成 0.0。根因：真值单元是**生成时的句**，而 `targetSide` 的句下标是
        // **分句器切出来的句**——两者在中文含 `，`/嵌套引号时并不一一对应（相邻句会
        // 被并进一句或被拆开）。位置假设一错，比的就是错句，区分度自然恒为 0。
        //
        // 现在直接用编码在中文句里**反查**：哪一句含这个编码，哪一句就是真值译文。
        // 这与 [AlignmentProjection] 识别真值的方式完全一致，不依赖任何位置假设。
        val truth = book.truth
        val targetSentenceByMarker = HashMap<String, Int>()
        for (t in 0 until targetSide.sentCount) {
            val markers = AlignmentProjection.targetMarkersIn(
                targetSide.sentText[t], truth.targetMarkerUniverse
            )
            markers.forEach { marker -> targetSentenceByMarker.putIfAbsent(marker, t) }
        }

        val details = ArrayList<SentenceDiscrimination>()
        var scored = 0
        for (s in 0 until sourceSide.sentCount) {
            val sentenceText = sourceSide.sentText[s]
            val key = AlignmentProjection
                .sourceKeysIn(sentenceText, truth.sourceMarkerUniverse)
                .firstOrNull() ?: continue
            val trueUnits = truth.trueTargetUnitsOf(key)
            if (trueUnits.isEmpty()) continue
            // 用该单元的第一个编码反查中文句下标。
            val marker = truth.targetUnits[trueUnits.first()].markers.firstOrNull() ?: continue
            val trueTarget = targetSentenceByMarker[marker] ?: continue
            if (trueTarget >= targetSide.sentCount) continue

            // 只对 1:1 句级组合打分；V1 无词条可用时 score 返回 UNAVAILABLE。
            val trueScore = verify.score(s, 1, trueTarget, 1)
            if (!trueScore.available) continue
            scored++

            var bestOther = Double.NEGATIVE_INFINITY
            var bestOtherText = ""
            for (t in 0 until targetSide.sentCount) {
                if (t == trueTarget) continue
                val other = verify.score(s, 1, t, 1)
                if (!other.available) continue
                // 用 recall 作主序（内容命中比例），precision 作次序。
                val value = other.recall * 2.0 + other.precision
                if (value > bestOther) {
                    bestOther = value
                    bestOtherText = targetSide.sentText[t]
                }
            }
            if (bestOther == Double.NEGATIVE_INFINITY) bestOther = 0.0
            val trueValue = trueScore.recall * 2.0 + trueScore.precision
            details += SentenceDiscrimination(
                sourceKey = key,
                sourceText = sentenceText,
                trueScore = trueValue,
                bestOtherScore = bestOther,
                bestOtherTarget = bestOtherText
            )
        }
        return Result(variant.label, details, scored, sourceSide.sentCount)
    }
}
