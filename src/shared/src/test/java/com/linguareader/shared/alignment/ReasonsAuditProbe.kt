package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **`reasons` 可复核性审计**（Lead 指定的最优先事项）。
 *
 * ## 要回答什么
 *
 * `base` 有 45 个非 ALIGNED 单元，其中 28 个的 reasons 含裸 `margin` 字样，但连我们都
 * 无法确认它指向哪句代码。**一份说不出「为什么拒绝」的报告，恰好违反本项目的核心目标
 * ——正确报告对齐与否。** 如果 reasons 含糊，`evidence` 就是摆设。
 *
 * ## 本审计做三件事，全部只读
 *
 * 1. 打印这些单元的**完整 reasons 原文**（不归桶、不截断）；
 * 2. 在 `Decide.kt` 里枚举所有拼出 `margin` 字样的文案模板（人工核对）；
 * 3. 把每条单元匹配到具体模板，产出「原文 → 分支 → 计数」表。
 *
 * ## 判据：一个 reason 串必须唯一对应一个判定原因
 *
 * 若同一文案在多个分支共用，或文案不含足够参数（只说 `margin` 不说数值），
 * 它就不能作为证据——那是 [AlignmentEvidence] 的设计缺陷。
 */
internal class ReasonsAuditProbe(private val book: SyntheticBilingualFixture.FixtureBook) {

    /** 一条拒绝单元的完整信息。 */
    class RejectedUnit(
        val sourceKey: String,
        val sourceText: String,
        val status: AlignmentStatus,
        val skipReason: AlignmentSkipReason?,
        /** **原文完整 reasons**，不归桶、不截断。 */
        val reasons: List<String>,
        val verifyMethod: String,
        val verifyScore: Double,
        /** reasons 里提到 margin 吗（裸字面匹配）。 */
        val mentionsMargin: Boolean
    ) {
        fun line(): String = buildString {
            append("REJECTED key=").append(sourceKey)
            append(" status=").append(status)
            if (skipReason != null) append(" skip=").append(skipReason)
            append(" witness=").append(verifyMethod.ifEmpty { "<none>" }).append(':').append(verifyScore)
            append("\n    reasons=").append(reasons)
            append("\n    src=\"").append(sourceText.take(88)).append('"')
        }
    }

    fun audit(): List<RejectedUnit> {
        val truth = book.truth
        val sourceSide = BookSide.build(SideKind.SOURCE, book.source)
        val targetSide = BookSide.build(SideKind.TARGET, book.translation)
        val pairCount = minOf(sourceSide.chapterCount, targetSide.chapterCount)
        val pairWords = LongArray(pairCount) { i ->
            book.translation[i].paragraphs.sumOf { it.split(' ').size.toLong() }
        }
        val pairChars = LongArray(pairCount) { i ->
            book.translation[i].paragraphs.sumOf { p ->
                p.count { c -> c in '\u3400'..'\u9FFF' }.toLong()
            }
        }
        val density = DensityModel(sourceSide)
        density.computeBook(pairChars, pairWords)
        val lexicon = SyntheticBilingualFixture.truthLexicon()
        val verify = VerifyLayer(
            sourceSide, targetSide, density,
            MeaningProvider { word -> lexicon[word.lowercase()] ?: emptyList() }
        )
        verify.build()

        // 用引擎真实输出（不重算判定），只做 reasons 原文采集。
        val document = AlignmentEngines.current.align(
            book.source, book.translation,
            MeaningProvider { word -> lexicon[word.lowercase()] ?: emptyList() }
        )
        val projection = AlignmentProjection.project(document, truth)

        val out = ArrayList<RejectedUnit>()
        projection.bySourceKey.forEach { (key, prediction) ->
            if (prediction.status == AlignmentStatus.ALIGNED) return@forEach
            val reasonsText = prediction.reasons.joinToString(" ")
            out += RejectedUnit(
                sourceKey = key,
                sourceText = prediction.sourceText,
                status = prediction.status,
                skipReason = prediction.skipReason,
                reasons = prediction.reasons,
                verifyMethod = prediction.verifyMethod,
                verifyScore = prediction.verifyScore,
                mentionsMargin = "margin" in reasonsText
            )
        }
        return out
    }
}
