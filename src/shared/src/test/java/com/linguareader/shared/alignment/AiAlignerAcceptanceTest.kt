package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **`ai-pair-v1` 验收台**：在 35 句 AI 译本真值上跑新引擎，并与 `independent-v1` 对照。
 *
 * ## 验收标准（新定义，显式声明适用范围）
 *
 * ```
 * 分母 = 段内英文句（章节/段落对应由构造给出，不计入算法成绩）
 * 同时报全书句分母作对照
 *
 * 目标（仅对「AI 译本 + 构造性段落对应」有效，不可外推到人工译本）：
 *   - falseAligned = 0（硬红线）
 *   - 段内覆盖率 >= 0.90
 *   - precision >= 0.98（有真值的语料上）
 * ```
 *
 * ## 两种输入形态都跑
 *
 * 1. **原始段落形态**（真实生产形态）：直接用 `enParagraphs`/`zhParagraphs` →
 *    两次 `SentenceSplitter` 分句。**含分句噪声。**
 * 2. **真值句对形态**（隔离分句）：用 `pairs` 的 `es`/`zs` 每句成段，减少段内分句干扰（仍会经过 `SentenceSplitter`）。
 *    分句输出须另外核对，不能当作已绕过分句器。
 *
 * 两个数字并列，差距就是「分句器对对齐准确率的损害」。
 */
class AiAlignerAcceptanceTest {

    @Test
    fun measureBothEnginesOnBothInputForms() {
        val inputs = AlignmentCorpusInputs.requireCase("F02")
        val ecdict = inputs.requireDictionary()
        val json = inputs.requireMemory()
        val enParagraphs = json.getJSONArray("enParagraphs").let { arr ->
            (0 until arr.length()).map { arr.optString(it, "") }
        }
        val zhParagraphs = json.getJSONArray("zhParagraphs").let { arr ->
            (0 until arr.length()).map { arr.optString(it, "") }
        }
        val pairs = json.getJSONArray("pairs")

        // 真值：pairs 的 es → zs
        val truthEn = ArrayList<String>(pairs.length())
        val truthZh = ArrayList<String>(pairs.length())
        for (i in 0 until pairs.length()) {
            val p = pairs.getJSONObject(i)
            val en = p.optString("es", "").trim()
            val zh = p.optString("zs", "").trim()
            truthEn.add(en)
            truthZh.add(zh)
        }
        // 真值：英文句 → 正确的中文句（用归一化文本匹配，支持合并句）
        val truthTargetOf = LinkedHashMap<String, String>()
        for (i in truthEn.indices) truthTargetOf[truthEn[i]] = truthZh[i]

        val targetMetrics = ArrayList<CorpusExecution.AiMetrics>()
        val ai = AiTranslationAligner()
        val legacy = IndependentAligner()

        println("=== 语料 ===")
        println("  原始形态：en 段=${enParagraphs.size} zh 段=${zhParagraphs.size}")
        println("  真值句对=${truthEn.size}")

        // ── 形态 1：原始段落（含分句噪声）──────────────────────────
        println()
        println("=== 形态 1：原始段落（走 SentenceSplitter，含分句噪声）===")
        val enChapters = listOf(AlignmentChapter("AI sample", enParagraphs))
        val zhChapters = listOf(AlignmentChapter("AI sample zh", zhParagraphs))
        targetMetrics += report("ai-pair-v1", ai.align(enChapters, zhChapters, ecdict), truthTargetOf, enChapters)
        report("independent-v1", legacy.align(enChapters, zhChapters, ecdict), truthTargetOf, enChapters)

        // ── 形态 2：真值句对（每句成段，仍须核对分句）────
        println()
        println("=== 形态 2：真值句对形态（每句成段，仍须核对分句）===")
        val pureEn = listOf(AlignmentChapter("pure", truthEn))
        val pureZh = listOf(AlignmentChapter("pure zh", truthZh))
        targetMetrics += report("ai-pair-v1", ai.align(pureEn, pureZh, ecdict), truthTargetOf, pureEn)
        report("independent-v1", legacy.align(pureEn, pureZh, ecdict), truthTargetOf, pureEn)
        val failures = targetMetrics.mapNotNull { metrics ->
            runCatching { metrics.assertApprovedAiTargets() }.exceptionOrNull()?.message
        }
        check(failures.isEmpty()) { failures.joinToString("\n") }
    }

    // ──────────────────────────────────────────────────────────────

    private fun report(
        label: String,
        document: AlignmentDocument,
        truthTargetOf: Map<String, String>,
        source: List<AlignmentChapter>
    ): CorpusExecution.AiMetrics {
        val metrics = CorpusExecution.aiMetrics(document, source, truthTargetOf.entries.map { it.key to it.value })
        println("--- $label ---")
        println("  ${document.stats.summaryLine()}")
        println("  ${metrics.line()}")
        return metrics
    }

}
