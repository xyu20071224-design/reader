package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **V3 在真实 DP 配对下的效果**（决定是否值得接入）。
 *
 * 前一个探针用「比例位置映射」估 V3 命中，但中英章节严重不对齐（48 vs 38 章），
 * 那个映射不准。这里用**引擎实际产出的 DP 配对**来测：
 *
 * 对每个已配对的单元，检查「该英文句的专名是否在它配对的中文句里精确命中」。
 * 这是 V3 作为见证通道的真实覆盖率上界。
 */
class V3OnDpPairsProbe {

    @Test
    fun measureV3OnActualPairs() {
        val inputs = AlignmentCorpusInputs.requireCase("F17")
        val enChapters = inputs.english
        val zhChapters = inputs.chinese
        val ecdict = inputs.requireDictionary()
        val document = IndependentAligner().align(enChapters, zhChapters, ecdict)
        CorpusExecution.requireDocument(document)
        println("=== 基线（无 V3）===")
        println("  ${document.stats.summaryLine()}")
        println("  三态：ALIGNED=${document.stats.alignedUnits} " +
            "UNCERTAIN=${document.stats.uncertainUnits} " +
            "UNALIGNED=${document.stats.unalignedUnits}")

        // 对每个「有配对」的单元，看它的中文句里是否含该英文句的专名
        var paired = 0
        var pairedWithTargetText = 0
        var v3Exact = 0
        val v3Samples = ArrayList<String>(10)
        val nameCounts = HashMap<String, Int>(64)
        for (unit in document.units) {
            if (unit.targetText.isEmpty()) continue
            pairedWithTargetText++
            val names = Transliteration.extractNames(unit.sourceText)
            if (names.isEmpty()) continue
            paired++
            var best = 0.0
            var bestName = ""
            for (name in names) {
                val score = Transliteration.scoreInSentence(name, unit.targetText)
                if (score > best) { best = score; bestName = name }
            }
            if (best >= 0.9) {
                v3Exact++
                nameCounts.merge(bestName, 1, Int::plus)
                if (v3Samples.size < 10) {
                    v3Samples += "[${unit.status}] name=\"$bestName\"(${String.format("%.2f", best)}) " +
                        "en=\"${unit.sourceText.take(34)}\" zh=\"${unit.targetText.take(26)}\""
                }
            }
        }
        println()
        println("=== V3 在真实 DP 配对上的命中 ===")
        println("  有展示译文的单元 = $pairedWithTargetText")
        println("  其中英文句含专名的 = $paired")
        println(
            "  **V3 精确命中（score >= 0.9）= $v3Exact** " +
                "(${pct(v3Exact.toDouble() / paired.coerceAtLeast(1))} of 含专名单元，" +
                "${pct(v3Exact.toDouble() / document.units.size)} of 全部单元)"
        )
        println("  命中专名 TOP 10：${nameCounts.entries.sortedByDescending { it.value }.take(10)}")
        println()
        println("  样例：")
        v3Samples.forEach { println("    $it") }

        // 关键：这些 V3 命中里，有多少是当前 ALIGNED？多少是 UNCERTAIN（可救）？
        var v3Aligned = 0
        var v3Uncertain = 0
        var v3Unaligned = 0
        for (unit in document.units) {
            if (unit.targetText.isEmpty()) continue
            val names = Transliteration.extractNames(unit.sourceText)
            if (names.isEmpty()) continue
            val best = names.maxOf { Transliteration.scoreInSentence(it, unit.targetText) }
            if (best < 0.9) continue
            when (unit.status) {
                AlignmentStatus.ALIGNED -> v3Aligned++
                AlignmentStatus.UNCERTAIN -> v3Uncertain++
                AlignmentStatus.UNALIGNED -> v3Unaligned++
            }
        }
        println()
        println("=== V3 命中单元的状态分布（决定能救多少）===")
        println("  ALIGNED（已覆盖，V3 无增量）= $v3Aligned")
        println("  **UNCERTAIN（V3 可救）= $v3Uncertain**")
        println("  UNALIGNED（无译文，V3 无法救）= $v3Unaligned")
        println()
        println(
            "  → 若接入 V3，coverage 上限 ≈ ${pct((document.stats.sourceSentencesAligned + v3Uncertain).toDouble() / document.stats.sourceSentenceTotal)}" +
                "（现在 ${pct(document.stats.coverage)}）"
        )
    }

    private fun pct(v: Double): String = "${Math.round(v * 10000.0) / 100.0}%"

}
