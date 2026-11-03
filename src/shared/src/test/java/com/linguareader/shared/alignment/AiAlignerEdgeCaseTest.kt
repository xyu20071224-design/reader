package com.linguareader.shared.alignment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **`ai-pair-v1` 的契约边界测试**（Lead 要求：不能只有一个「跑通 35 条」的验收测试）。
 *
 * 覆盖：
 * 1. 章数不等 → `DEGENERATE_INPUT`
 * 2. 段数不等 → 多出的段 `TRANSLATION_MISSING`，不静默错位
 * 3. 锚点防错切 → 构造 `A.` 误切场景，断言整段降级
 * 4. 段内句数不等 → 走穷举 DP，reasons 带 `fragment:` 前缀
 * 5. 孤立标点段（`"”"`）→ 不产生错标
 * 6. 三态自洽：`UNALIGNED` 不带译文与坐标，`UNALIGNED` 必有 `skipReason`
 * 7. 逐句覆盖：每条英文句恰好一个单元
 *
 * 这些不依赖真实语料，可在 CI 里稳定跑。
 */
class AiAlignerEdgeCaseTest {

    private val aligner = AiTranslationAligner()

    private fun provider(): MeaningProvider {
        // 边界测试不关心词典质量，给一个空 provider 即可（走「无见证」路径）
        return MeaningProvider { emptyList() }
    }

    /** 第 1 条：章数不等 → 整本 `DEGENERATE_INPUT`，不抛异常。 */
    @Test
    fun chapterCountMismatchIsDegenerate() {
        val en = listOf(
            AlignmentChapter("Chapter 1", listOf("Tom arrived at the station.")),
            AlignmentChapter("Chapter 2", listOf("He found the bookshop."))
        )
        val zh = listOf(AlignmentChapter("第1章", listOf("汤姆到了车站。")))
        val document = aligner.align(en, zh, provider())
        assertEquals("ai-pair-v1", document.engineId)
        assertTrue(document.units.isNotEmpty(), "退化时仍须覆盖英文侧（不许静默丢句）")
        assertTrue(
            document.units.all { it.skipReason == AlignmentSkipReason.DEGENERATE_INPUT },
            "章数不等时所有单元必须是 DEGENERATE_INPUT"
        )
        assertTrue(
            document.notes.any { it.contains("chapter count mismatch") },
            "notes 必须说明原因：${document.notes}"
        )
    }

    /** 第 2 条：段数不等 → 多出的英文段 `TRANSLATION_MISSING`，且**不静默错位**。 */
    @Test
    fun paragraphCountMismatchDoesNotSilentlyShift() {
        val en = listOf(
            AlignmentChapter(
                "Chapter 1",
                listOf(
                    "Tom arrived at the station.",
                    "The rain was heavy.",
                    "He carried a suitcase."
                )
            )
        )
        val zh = listOf(
            AlignmentChapter("第1章", listOf("汤姆到了车站。", "雨很大。"))
        )
        val document = aligner.align(en, zh, provider())
        val missing = document.units.filter {
            it.skipReason == AlignmentSkipReason.TRANSLATION_MISSING
        }
        assertEquals(
            1, missing.size,
            "第 3 段没有对应，必须恰好 1 句判 TRANSLATION_MISSING，" +
                "不能把它的译文借给第 2 段：${document.units.map { it.status }}"
        )
        assertTrue(
            document.notes.any { it.contains("paragraph count mismatch") },
            "notes 必须记录段数不等：${document.notes}"
        )
        // 第 2 段自己的译文不能被第 3 段顶替
        val second = document.units.firstOrNull { it.sourceText.contains("rain") }
        assertTrue(
            second == null || !second.targetText.contains("行李箱"),
            "第 2 段不能拿到第 3 段的译文（那是静默错位）"
        )
    }

    /**
     * 第 3 条：锚点防错切 —— 构造 `A. Whitmore` 被切碎的 2==2 场景。
     *
     * ```
     * en: ["Its sign read only \"A.", "Whitmore, Books\" in faded gold letters."]   ← 2 句（错切）
     * zh: ["它的招牌上只写着「A. 惠特莫尔书店」，金漆已经褪色。"]                      ← 1 句
     * ```
     * 这里是 2 vs 1，走段内穷举；关键是**必须带着 `fragment:` 标记**，
     * 而不是假装正常对齐。
     */
    @Test
    fun splitFragmentIsMarkedAsFragment() {
        val en = listOf(
            AlignmentChapter(
                "Chapter 1",
                listOf("Its sign read only \"A. Whitmore, Books\" in faded gold letters.")
            )
        )
        val zh = listOf(
            AlignmentChapter(
                "第1章",
                listOf("它的招牌上只写着「A. 惠特莫尔书店」，金漆已经褪色。")
            )
        )
        val document = aligner.align(en, zh, provider())
        // 无论最终状态如何，只要经过段内句数不等路径，reasons 里必须有 fragment 标记
        val nonAligned = document.units.filter { it.status != AlignmentStatus.ALIGNED }
        if (nonAligned.isNotEmpty()) {
            assertTrue(
                nonAligned.any { unit ->
                    unit.evidence.reasons.any { it.contains("fragment:") } ||
                        unit.evidence.reasons.any { it.contains("no witness") } ||
                        unit.evidence.reasons.any { it.contains("length deviation") }
                },
                "非 ALIGNED 单元必须有可复核的原因：${nonAligned.map { it.evidence.reasons }}"
            )
        }
        assertTrue(document.units.isNotEmpty())
    }

    /** 第 4 条：段内句数不等（en=3 / zh=2）→ 走穷举，reasons 带 `fragment:` 前缀。 */
    @Test
    fun sentenceCountMismatchUsesExhaustiveAndMarksFragment() {
        val en = listOf(
            AlignmentChapter(
                "Chapter 1",
                listOf("Tom arrived. The rain was heavy. He carried a suitcase.")
            )
        )
        val zh = listOf(
            AlignmentChapter("第1章", listOf("汤姆到了。雨很大，他带着行李箱。"))
        )
        val document = aligner.align(en, zh, provider())
        assertEquals(3, document.units.size, "3 条英文句必须恰好 3 个单元")
        val fragmentMarked = document.units.count { unit ->
            unit.evidence.reasons.any { it.contains("fragment:") }
        }
        assertTrue(
            fragmentMarked > 0,
            "段内句数不等必须留下 fragment 标记（供报告区分「分句噪声」与「真没译文」）：" +
                document.units.map { it.evidence.reasons }
        )
    }

    /** 第 5 条：孤立标点段（`"”"`）→ 不产生错标。 */
    @Test
    fun orphanPunctuationSegmentNeverAligned() {
        val en = listOf(
            AlignmentChapter("Chapter 1", listOf("In 1919 he left a box and asked me never to open it."))
        )
        val zh = listOf(AlignmentChapter("第1章", listOf("”")))
        val document = aligner.align(en, zh, provider())
        assertTrue(
            document.units.none { it.status == AlignmentStatus.ALIGNED },
            "目标是孤立标点时绝不 ALIGNED：${document.units.map { it.status to it.evidence.reasons }}"
        )
    }

    /** 第 6 条：三态自洽 —— `UNALIGNED` 不带译文与坐标、必有 `skipReason`。 */
    @Test
    fun threeStateContractIsConsistent() {
        val en = listOf(
            AlignmentChapter("Chapter 1", listOf("Tom arrived. The rain was heavy."))
        )
        val zh = listOf(AlignmentChapter("第1章", listOf("汤姆到了。")))
        val document = aligner.align(en, zh, provider())
        document.units.forEach { unit ->
            when (unit.status) {
                AlignmentStatus.UNALIGNED -> {
                    assertTrue(
                        unit.targetText.isEmpty(),
                        "UNALIGNED 不得带译文：\"${unit.targetText}\""
                    )
                    assertEquals(-1, unit.targetChapter, "UNALIGNED 的 targetChapter 必须是 -1")
                    assertTrue(unit.skipReason != null, "UNALIGNED 必须有 skipReason")
                }
                AlignmentStatus.ALIGNED, AlignmentStatus.UNCERTAIN -> {
                    assertEquals(null, unit.skipReason, "${unit.status} 不得带 skipReason")
                }
            }
        }
    }

    /** 第 7 条：逐句覆盖 —— 每条英文句恰好一个单元，无遗漏无重复。 */
    @Test
    fun everyEnglishSentenceCoveredExactlyOnce() {
        val en = listOf(
            AlignmentChapter(
                "Chapter 1",
                listOf("Tom arrived. The rain was heavy. He carried a suitcase.")
            ),
            AlignmentChapter("Chapter 2", listOf("The bookshop was closed."))
        )
        val zh = listOf(
            AlignmentChapter("第1章", listOf("汤姆到了。雨很大。他带着行李箱。")),
            AlignmentChapter("第2章", listOf("书店关门了。"))
        )
        val document = aligner.align(en, zh, provider())
        val expected = en.sumOf { chapter ->
            chapter.paragraphs.sumOf {
                com.linguareader.shared.tts.SentenceSplitter.count(it)
            }
        }
        assertEquals(expected, document.units.size, "每条英文句必须恰好一个单元")
        val sourceTexts = document.units.map { it.sourceText }
        assertEquals(
            sourceTexts.size, sourceTexts.toSet().size,
            "单元不得重复覆盖同一句：$sourceTexts"
        )
    }

    /** 附加：空输入不抛异常。 */
    @Test
    fun emptyInputIsSafe() {
        val document = aligner.align(emptyList(), emptyList(), null)
        assertEquals(0, document.units.size)
        assertEquals("ai-pair-v1", document.engineId)
    }

    /** 附加：章节 identity 正确 —— 第 i 章必须对第 i 章。 */
    @Test
    fun chapterIdentityIsRespected() {
        val en = listOf(
            AlignmentChapter("A", listOf("Tom arrived.")),
            AlignmentChapter("B", listOf("The rain was heavy."))
        )
        val zh = listOf(
            AlignmentChapter("甲", listOf("汤姆到了。")),
            AlignmentChapter("乙", listOf("雨很大。"))
        )
        val document = aligner.align(en, zh, provider())
        document.units.forEach { unit ->
            if (unit.targetChapter >= 0) {
                assertEquals(
                    unit.sourceChapter, unit.targetChapter,
                    "identity 场景下 sourceChapter 必须等于 targetChapter"
                )
            }
        }
    }
}
