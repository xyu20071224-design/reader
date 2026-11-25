package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **真实语料验收台**：在魔戒英中语料上跑本引擎，报覆盖率与金标准召回。
 *
 * ## 为什么必须有这个文件
 *
 * 合成语料只能证明「不产生错标」（安全性），证明不了「能用」。真实语料（13611 英文句 /
 * 9336 中文句 / 48 英文章 vs 38 中文章 / 全繁体）才是可用性证据。
 *
 * ## 指标
 *
 * | 指标 | 定义 |
 * | --- | --- |
 * | `coverage` | ALIGNED 覆盖的英文有效句 / 英文有效句总数（契约口径，用 `stats`） |
 * | `goldRecall` | 金标准 100 条里 `approved.level != MISS` 的句子中，被报 ALIGNED 的比例 |
 * | `falseAligned` | 金标准里 `verdict == bad` 却被报 ALIGNED 的条数（**红线**） |
 *
 * 金标准口径说明：`golden-samples.json` 的 `approved.level` 为 `MISS` = 人工确认无对照；
 * `SENTENCE` = 有句级对照；`PARAGRAPH` = 只有段级对照（可接受 ALIGNED 但不算精确召回）。
 */
class RealCorpusAcceptanceTest {

    @Test
    fun measureOnLordsOfTheRings() {
        val inputs = AlignmentCorpusInputs.requireCase("F11")
        val enChapters = inputs.english
        val zhChapters = inputs.chinese
        val ecdict = inputs.requireDictionary()
        println("=== 真实语料 ===")
        println("  en: ${enChapters.size} 章 / ${enChapters.sumOf { it.paragraphs.size }} 段")
        println("  zh: ${zhChapters.size} 章 / ${zhChapters.sumOf { it.paragraphs.size }} 段")
        println("  真 ECDICT（${ecdict.entryCount} 词条 / ${ecdict.lemmaCount} 词形）")

        val started = System.nanoTime()
        val document = IndependentAligner().align(enChapters, zhChapters, ecdict)
        CorpusExecution.requireDocument(document)
        val elapsed = (System.nanoTime() - started) / 1_000_000

        println()
        println("=== 结果（真 ECDICT）===")
        println("  stats: ${document.stats.summaryLine()}")
        println("  elapsed = ${elapsed}ms")
        println("  三态：ALIGNED=${document.stats.alignedUnits} " +
            "UNCERTAIN=${document.stats.uncertainUnits} " +
            "UNALIGNED=${document.stats.unalignedUnits}")
        val skipReasons = document.units
            .filter { it.skipReason != null }
            .groupingBy { it.skipReason!! }
            .eachCount()
        println("  skipReason: $skipReasons")
        println()
        println("  --- notes（前 12 条）---")
        document.notes.take(12).forEach { println("    $it") }

        println()
        println("=== 金标准重放 ===")
        replayGolden(inputs.requireGolden(), document)

        // 非空执行与金标准错标红线均须通过；真实书覆盖率尚无已批准阈值。
        kotlin.test.assertTrue(
            document.units.isNotEmpty(),
            "真实语料上必须产出单元（不许静默丢句）"
        )
    }

    // ──────────────────────────────────────────────────────────────

    private fun replayGolden(json: org.json.JSONObject, document: AlignmentDocument) {
        val samples = json.getJSONArray("samples")

        // 用英文原句文本索引：单元 sourceText 与样本 en 做**归一化包含**匹配
        val bySource = document.units.groupBy { normalize(it.sourceText) }

        var positive = 0
        var positiveAligned = 0
        var positiveSentence = 0
        var positiveSentenceAligned = 0
        var badTotal = 0
        var badAligned = 0
        var matched = 0
        val misses = ArrayList<String>()
        // **产品形态决策指标**（Lead 要求）：ALIGNED + 带候选的 UNCERTAIN 的可用覆盖率。
        // 若这个数足够高且精确率可接受，产品可以做成「默认展示 + 标注把握不足」。
        var usableAligned = 0
        var usableUncertain = 0
        var usableBad = 0
        var usablePositive = 0
        var uncertainWithText = 0

        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            val english = sample.optString("en", "")
            val verdict = sample.optString("verdict", "")
            val level = sample.optJSONObject("approved")?.optString("level", "") ?: "MISS"
            val unit = findUnit(bySource, english)
            if (unit != null) matched++

            val isPositive = level != "MISS"
            val isUsable = unit != null &&
                (unit.status == AlignmentStatus.ALIGNED ||
                    (unit.status == AlignmentStatus.UNCERTAIN && unit.targetText.isNotEmpty()))
            if (isUsable) {
                usableAligned += if (unit!!.status == AlignmentStatus.ALIGNED) 1 else 0
                usableUncertain += if (unit!!.status == AlignmentStatus.UNCERTAIN) 1 else 0
                if (verdict == "bad") usableBad++
            }
            if (isPositive) {
                positive++
                usablePositive += if (isUsable) 1 else 0
                if (unit?.status == AlignmentStatus.ALIGNED) {
                    positiveAligned++
                    if (level == "SENTENCE") positiveSentenceAligned++
                } else if (misses.size < 8) {
                    misses += "[$level] en=\"${english.take(46)}\" " +
                        "→ ${unit?.status ?: "NOT_FOUND"} ${unit?.skipReason ?: ""}"
                }
                if (level == "SENTENCE") positiveSentence++
            }
            if (verdict == "bad") {
                badTotal++
                if (unit?.status == AlignmentStatus.ALIGNED) {
                    badAligned++
                    println(
                        "  !! FALSE-ALIGN: en=\"${english.take(46)}\" " +
                            "→ zh=\"${unit.targetText.take(32)}\""
                    )
                }
            }
        }

        // 全书范围：UNCERTAIN 里有多少带候选译文（产品可用性基数）
        document.units.forEach { unit ->
            if (unit.status == AlignmentStatus.UNCERTAIN && unit.targetText.isNotEmpty()) {
                uncertainWithText++
            }
        }

        check(matched == samples.length()) { "Golden samples not fully replayed: $matched/${samples.length()}" }
        check(badAligned == 0) { "Golden red line falseAligned=$badAligned (must be 0)" }
        println("  样本匹配：$matched/${samples.length()}")
        println("  人工正样本（level != MISS）：$positive，其中报 ALIGNED = $positiveAligned " +
            "(${pct(positiveAligned.toDouble() / positive.coerceAtLeast(1))})")
        println("  其中句级对照（level == SENTENCE）：$positiveSentence，" +
            "报 ALIGNED = $positiveSentenceAligned " +
            "(${pct(positiveSentenceAligned.toDouble() / positiveSentence.coerceAtLeast(1))})")
        println("  人工判定 bad 的样本：$badTotal，其中报 ALIGNED = $badAligned（红线应为 0）")
        println()
        println("  === 产品形态决策指标（ALIGNED + 带候选 UNCERTAIN）===")
        println("  全书 UNCERTAIN 且带候选译文 = $uncertainWithText 条")
        println(
            "  金标准人工正样本中被「ALIGNED 或 带候选 UNCERTAIN」覆盖 = " +
                "$usablePositive/$positive (${pct(usablePositive.toDouble() / positive.coerceAtLeast(1))})"
        )
        println("    其中 ALIGNED = $usableAligned，带候选 UNCERTAIN = $usableUncertain")
        println(
            "  这些可展示条目里人工判定 bad 的 = $usableBad " +
                "（可用精确率 = ${pct(1.0 - usableBad.toDouble() / (usableAligned + usableUncertain).coerceAtLeast(1))}）"
        )
        if (misses.isNotEmpty()) {
            println("  漏掉的样例（前 8 条）：")
            misses.forEach { println("    $it") }
        }
    }

    /** 在单元里找对应的英文句（样本 en 是片段/整句，做归一化后双向包含匹配）。 */
    private fun findUnit(bySource: Map<String, List<AlignedUnit>>, english: String): AlignedUnit? {
        val needle = normalize(english)
        if (needle.isEmpty()) return null
        bySource[needle]?.let { units ->
            check(units.size == 1) { "Ambiguous repeated golden source; independent location is required" }
            return units.single()
        }
        // 片段匹配：样本 en 可能只是单元文本的一部分
        val candidates = bySource.filterKeys { key -> key.contains(needle) || needle.contains(key) }.values.flatten()
        check(candidates.size <= 1) { "Ambiguous golden source fragment; supply a uniquely locatable reviewed source" }
        return candidates.singleOrNull()
    }

    private fun normalize(text: String): String {
        val builder = StringBuilder(text.length)
        for (c in text) {
            when {
                c in 'A'..'Z' -> builder.append(c + 32)
                c in 'a'..'z' || c in '0'..'9' -> builder.append(c)
                c in '\u4E00'..'\u9FFF' -> builder.append(c)
            }
        }
        return builder.toString()
    }

    private fun pct(v: Double): String = "${Math.round(v * 10000.0) / 100.0}%"

}
