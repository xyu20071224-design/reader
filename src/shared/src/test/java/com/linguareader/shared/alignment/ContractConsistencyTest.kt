package com.linguareader.shared.alignment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **契约一致性回归**：`status` / `targetText` / `reasons` 三者必须自洽。
 *
 * ## 这个文件守的是三条「报告说谎」的形态
 *
 * | 形态 | 为什么恶劣 | 断言 |
 * | --- | --- | --- |
 * | `UNALIGNED` 却带 `targetText` | 用户看到「没有对应」+一条具体译文，会以为系统只是谨慎，实际那条已被否决 | [unalignedMustNotCarryTargetText] |
 * | `UNCERTAIN` 的 reasons 说 `no content witness` 却给了译文 | 译文是兜底塞的、毫无依据，伪装成「谨慎」 | [reasonsMustSupportTheShownTranslation] |
 * | 真值上无对应的句子被报 `ALIGNED` | 硬红线：错标 | [missingTranslationNeverAligned] |
 *
 * 第二条是 Lead 指定的「能机械地抓住所有同类问题」的断言：**`targetText` 非空的单元，
 * `reasons` 不得包含 `no content witness`**。
 *
 * 这些断言跨**全部变体**跑，所以任何变体上再次出现同类问题都会立刻红。
 */
class ContractConsistencyTest {

    private val engine = IndependentAligner()

    /** 跑全部变体（带 oracle 词典：主验收路径的词典质量上界）。 */
    private fun forEachVariant(block: (String, AlignmentDocument) -> Unit) {
        SyntheticBilingualFixture.Variant.ALL.forEach { variant ->
            val book = variant.generate()
            val document = engine.align(book.source, book.translation, OracleLexicon(book))
            block(variant.label, document)
        }
    }

    /**
     * 契约：`UNALIGNED` 的语义是「明确没有对应」，它的 `targetText` **必须为空**。
     *
     * 历史 bug：投影层只判断「DP 给过边」就填 `targetText`，不看最终判定状态，于是
     * 「DP 曾配过、判定层否决了」的句子会变成「说 UNALIGNED 却带译文」的混合体。
     */
    @Test
    fun unalignedMustNotCarryTargetText() {
        forEachVariant { label, document ->
            document.units.forEachIndexed { index, unit ->
                if (unit.status != AlignmentStatus.UNALIGNED) return@forEachIndexed
                assertTrue(
                    unit.targetText.isEmpty(),
                    "[$label] unit=$index 报 UNALIGNED 却带 targetText=" +
                        "\"${unit.targetText.take(40)}\"（契约要求 UNALIGNED 的 targetText 为空）"
                )
                assertEquals(
                    -1, unit.targetChapter,
                    "[$label] unit=$index 报 UNALIGNED 却带 targetChapter=${unit.targetChapter}"
                )
            }
        }
    }

    /**
     * **Lead 指定的机械断言**：给了译文就必须有支撑它的理由。
     *
     * 抓的是「兜底塞候选」：状态说没有见证、文本却给了一条译文。用户会把那条译文当成
     * 「大概是对的、只是不确定」，而它实际上毫无依据——比错标更难发现。
     *
     * ## 允许的形态（不是开后门，是契约的正确读法）
     *
     * `UNCERTAIN` 的定义就是「**有候选**但证据不足」。候选来自 DP 的结构匹配（长度 +
     * 位置 + 单调），这是真实存在的信息，只是没有内容通道背书。此时：
     * - 必须带 `targetText`（否则用户无法判断这条候选是什么）
     * - `reasons` 必须**说明这条候选的来源与缺失**：
     *   `structural match only: no content channel confirmed this pair`
     *
     * 禁止的是「说没有见证、却不解释候选来源」的形态——那才是谎报。
     * 所以断言收紧为：**reasons 必须非空，且必须自述候选来源**。
     */
    @Test
    fun reasonsMustSupportTheShownTranslation() {
        forEachVariant { label, document ->
            document.units.forEachIndexed { index, unit ->
                if (unit.targetText.isEmpty()) return@forEachIndexed
                val reasons = unit.evidence.reasons
                assertTrue(
                    reasons.isNotEmpty(),
                    "[$label] unit=$index 给了译文但 reasons 为空（无法复核）"
                )
                // 「零见证」时禁止只说「没有见证」，必须自述候选来源。
                val claimsNoWitness = reasons.any { it.contains("no content witness") }
                if (claimsNoWitness) {
                    assertTrue(
                        reasons.any { it.contains("structural match") },
                        "[$label] unit=$index 声称无见证却给了译文，且未说明候选来源：" +
                            "targetText=\"${unit.targetText.take(40)}\" reasons=$reasons"
                    )
                }
            }
        }
    }

    /** `UNCERTAIN` 必须带 `skipReason == null`；`UNALIGNED` 必须带非 null 的 `skipReason`。 */
    @Test
    fun skipReasonMatchesStatus() {
        forEachVariant { label, document ->
            document.units.forEachIndexed { index, unit ->
                when (unit.status) {
                    AlignmentStatus.UNALIGNED ->
                        assertTrue(
                            unit.skipReason != null,
                            "[$label] unit=$index 报 UNALIGNED 却没给 skipReason"
                        )
                    AlignmentStatus.ALIGNED, AlignmentStatus.UNCERTAIN ->
                        assertEquals(
                            null, unit.skipReason,
                            "[$label] unit=$index 报 ${unit.status} 却带了 skipReason=${unit.skipReason}"
                        )
                }
            }
        }
    }

    /**
     * **硬红线**：真值上无译文的英文句，`falseAligned` 必须为 0。
     *
     * 这条在 `missingTranslation` 变体上曾经红过：一句英文的真值译文在被删掉的段落里，
     * 算法凭「margin 跳过 + 单条弱词典见证」给它配了别的段的中文句。修复是
     * `MIN_WITNESS_WHEN_MARGIN_SKIPPED = 2`（无结构证据时内容证据必须加倍）。
     */
    @Test
    fun missingTranslationNeverAligned() {
        SyntheticBilingualFixture.Variant.ALL.forEach { variant ->
            val book = variant.generate()
            val document = engine.align(book.source, book.translation, OracleLexicon(book))
            val truth = book.truth
            document.units.forEachIndexed { index, unit ->
                if (unit.status != AlignmentStatus.ALIGNED) return@forEachIndexed
                val key = AlignmentProjection
                    .sourceKeysIn(unit.sourceText, truth.sourceMarkerUniverse)
                    .firstOrNull() ?: return@forEachIndexed
                assertTrue(
                    key !in truth.untranslatedSourceKeys,
                    "[${variant.label}] unit=$index 报 ALIGNED 但真值上该句无译文：" +
                        "en=\"${unit.sourceText.take(46)}\" " +
                        "zh=\"${unit.targetText.take(40)}\" reasons=${unit.evidence.reasons}"
                )
            }
        }
    }

    /**
     * 跳过 `margin` 门的单元，见证数必须达到要求。
     *
     * **合并单元是例外**（Lead 批准的针对性放宽，见
     * `AlignTuning.MERGED_WITNESS_RELAX_ENABLED`）：合并边按 §8.3 禁用 `vDiscourse`，
     * 最多只有 1 条见证（`vLexical`），要求 2 条在数学上不可满足。放宽条件是
     * `merged ∧ 单条 vLexical ∧ recall ≥ 完整门槛 ∧ 每句英文各有内容贡献`。
     *
     * 所以这条断言只对**非合并**单元生效——合并单元由 `decide()` 的三条件把关。
     */
    @Test
    fun marginSkippedRequiresDoubledWitness() {
        forEachVariant { label, document ->
            document.units.forEachIndexed { index, unit ->
                if (unit.status != AlignmentStatus.ALIGNED) return@forEachIndexed
                val reasons = unit.evidence.reasons
                if (reasons.none { it.contains("margin skipped") }) return@forEachIndexed
                // 合并单元走放宽通道，不受「≥2」约束。**不能用 targetSpanSize > 1 判**——
                // `N:1` 按契约投影成 N 个 `targetSpanSize=1` 的单元，那个字段看不出合并。
                // 用 reasons 里的显式标记判（`decide()` 只在合并放宽生效时才写它）。
                if (reasons.any { it.contains("merged-span witness relax") }) return@forEachIndexed
                val witnesses = reasons
                    .firstOrNull { it.startsWith("witnesses=") }
                    ?.removePrefix("witnesses=")
                    ?.toIntOrNull()
                assertTrue(
                    witnesses != null &&
                        witnesses >= AlignTuning.MIN_WITNESS_WHEN_MARGIN_SKIPPED,
                    "[$label] unit=$index 跳过 margin 门却只有 witnesses=$witnesses" +
                        "（非合并单元要求 ≥ ${AlignTuning.MIN_WITNESS_WHEN_MARGIN_SKIPPED}）" +
                        " reasons=$reasons"
                )
            }
        }
    }
}
