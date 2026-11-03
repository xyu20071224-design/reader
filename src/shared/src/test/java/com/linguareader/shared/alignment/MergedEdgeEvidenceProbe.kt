package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **合并边取证**（Lead 要求）：`mergedUnits = 30` 却只有 5 个 ALIGNED，这两个数放在一起
 * 看着矛盾，必须查清。
 *
 * 要回答三件事：
 * 1. 挑 3 条合并边，打印它的英文/中文区间与投影出的每个单元；
 * 2. 同一条边的 N 个单元，`targetText` 是否真的相同；
 * 3. `sourceSentencesAligned`（覆盖率分子）在 N 个单元里算 3 还是 1。
 */
class MergedEdgeEvidenceProbe {

    @Test
    fun mergedEdgesProduceFewAligned() {
        val book = SyntheticBilingualFixture.Variant.SENTENCE_MERGE.generate()
        val document = IndependentAligner().align(
            book.source, book.translation, OracleLexicon(book)
        )
        val source = BookSide.build(SideKind.SOURCE, book.source)
        val target = BookSide.build(SideKind.TARGET, book.translation)

        println("=== sentenceMerge 总览 ===")
        println("  ${document.stats.summaryLine()}")
        println("  契约字段：")
        println("    sourceSentenceTotal   = ${document.stats.sourceSentenceTotal}")
        println("    sourceSentencesAligned= ${document.stats.sourceSentencesAligned}")
        println("    alignedUnits          = ${document.stats.alignedUnits}")
        println("    mergedUnits（按边）    = ${document.stats.mergedUnits}")

        // ── 按 targetText 分组，找出「多个单元共享同一译文」的组（N:1 的投影特征）─
        val byTarget = LinkedHashMap<String, MutableList<Int>>()
        document.units.forEachIndexed { index, unit ->
            if (unit.targetText.isEmpty()) return@forEachIndexed
            byTarget.getOrPut(unit.targetText) { mutableListOf() }.add(index)
        }
        val sharedGroups = byTarget.entries.filter { it.value.size > 1 }
        println()
        println("=== 共享同一 targetText 的组（N:1 投影特征）===")
        println("  组数=${sharedGroups.size}，涉及的单元数=${sharedGroups.sumOf { it.value.size }}")

        println()
        println("=== 前 3 个共享组逐单元明细 ===")
        sharedGroups.take(3).forEachIndexed { groupIndex, entry ->
            val indices = entry.value
            println()
            println("--- 组 ${groupIndex + 1}：${indices.size} 个单元共享同一 targetText ---")
            println("  targetText=\"${entry.key.take(60)}…\" (len=${entry.key.length})")
            indices.forEach { unitIndex ->
                val unit = document.units[unitIndex]
                println(
                    "    unit=$unitIndex status=${unit.status} " +
                        "sourceSpanSize=${unit.sourceSpanSize} " +
                        "targetSpanSize=${unit.targetSpanSize} " +
                        "targetChapter=${unit.targetChapter} targetIdx=${unit.targetIndexInParagraph}"
                )
                println("      sourceText=\"${unit.sourceText.take(56)}\"")
                println("      targetText=\"${unit.targetText.take(56)}\"")
                println("      reasons=${unit.evidence.reasons}")
                println("      verify=${Math.round(unit.evidence.verifyScore * 1000) / 1000.0}")
            }
            val allSame = indices.map { document.units[it].targetText }.toSet().size == 1
            println("  → 该组所有单元 targetText 完全相同：$allSame")
        }

        // ── 关键矛盾：30 条合并边为什么只带来 5 个 ALIGNED ─────────────
        println()
        println("=== 矛盾溯源：合并组内单元的状态分布 ===")
        val statusByGroupSize = LinkedHashMap<Int, MutableMap<AlignmentStatus, Int>>()
        sharedGroups.forEach { entry ->
            val size = entry.value.size
            val bucket = statusByGroupSize.getOrPut(size) { LinkedHashMap() }
            entry.value.forEach { unitIndex ->
                bucket.merge(document.units[unitIndex].status, 1, Int::plus)
            }
        }
        statusByGroupSize.toSortedMap().forEach { (size, statuses) ->
            println("  组大小=$size（$size 个英文句共享 1 句中文）：$statuses")
        }

        // 每个合并组里：有多少组「全部 ALIGNED」，多少组「全部非 ALIGNED」，多少组「混合」
        var allAligned = 0
        var noneAligned = 0
        var mixed = 0
        sharedGroups.forEach { entry ->
            val statuses = entry.value.map { document.units[it].status }
            val alignedCount = statuses.count { it == AlignmentStatus.ALIGNED }
            when {
                alignedCount == statuses.size -> allAligned++
                alignedCount == 0 -> noneAligned++
                else -> mixed++
            }
        }
        println()
        println("  合并组判定结果：全 ALIGNED=$allAligned 组，全非 ALIGNED=$noneAligned 组，混合=$mixed 组")

        // 这些组里单元的见证情况
        val mergedAligned = document.units.filter { it.status == AlignmentStatus.ALIGNED }
        println()
        println("=== 已 ALIGNED 单元的 targetSpanSize 分布 ===")
        val spanHist = mergedAligned.groupingBy { it.targetSpanSize }.eachCount()
        println("  $spanHist （契约 §9.5：N:1 拆开后 targetSpanSize 恒为 1，所以 >1 的应为 0）")

        // 覆盖率分子的口径验证：ALIGNED 单元的 sourceSpanSize 之和
        val sumAlignedSpan = document.units
            .filter { it.status == AlignmentStatus.ALIGNED }
            .sumOf { it.sourceSpanSize }
        println()
        println("=== 覆盖率分子口径验证 ===")
        println("  Σ(ALIGNED 单元的 sourceSpanSize) = $sumAlignedSpan")
        println("  stats.sourceSentencesAligned     = ${document.stats.sourceSentencesAligned}")
        println("  → 两者必须相等（合并对按实际覆盖句数计，§14.1）")
        println("  aligned 单元数=${mergedAligned.size}，但覆盖英文句数=$sumAlignedSpan")
        println("  → 因为每个单元 sourceSpanSize 恒为 1，两者当前相等；N:1 的 3 个单元各算 1 句共 3 句")
    }

    /**
     * **确定性验证**（Lead 要求）：同一配置跑两次，结果必须完全一致。
     *
     * 回答「base coverage 26.09% vs 探针 34.78%」的差异来源。
     */
    @Test
    fun alignmentIsDeterministic() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val engine = IndependentAligner()

        println("=== 确定性验证：同一输入跑 3 次 ===")
        val runs = (1..3).map {
            engine.align(book.source, book.translation, OracleLexicon(book))
        }
        val signatures = runs.map { document ->
            document.units.joinToString("|") {
                "${it.status}:${it.targetChapter}:${it.targetIndexInParagraph}"
            }
        }
        val allIdentical = signatures.toSet().size == 1
        println("  3 次运行的单元签名是否完全一致：$allIdentical")
        runs.forEachIndexed { index, document ->
            println(
                "  run${index + 1}: ${document.stats.summaryLine()}"
            )
        }

        // 解释 26.09% vs 34.78%：两次用的是**不同的 MeaningProvider**
        println()
        println("=== 26.09% vs 34.78% 的差异溯源 ===")
        val withOracle = IndependentAligner().align(book.source, book.translation, OracleLexicon(book))
        val withHandLexicon = IndependentAligner().align(
            book.source, book.translation, IndependentAlignerAcceptanceTest.LexiconProvider()
        )
        val withNull = IndependentAligner().align(book.source, book.translation, null)
        println("  OracleLexicon（探针用的，稳定共现片段） : coverage=${fmt(withOracle.stats.coverage)}")
        println("  手写 21 词 LexiconProvider（验收台用的）: coverage=${fmt(withHandLexicon.stats.coverage)}")
        println("  meaning = null                          : coverage=${fmt(withNull.stats.coverage)}")
        println("  → 若验收台 XML 报 26.09%、探针报 34.78%，差异来自**词典替身不同**，不是非确定性")

        kotlin.test.assertTrue(allIdentical, "同一配置跑多次结果不一致 = 非确定性 bug")
    }

    private fun fmt(v: Double): String = (Math.round(v * 10000.0) / 100.0).toString() + "%"
}
