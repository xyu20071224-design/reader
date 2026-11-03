package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **UNCERTAIN 阻断原因直方图**（Lead 要求，纯诊断，不改判定逻辑）。
 *
 * 对 `base` 变体每条非 ALIGNED 单元，逐条重放 `decide()` 的判定顺序，记录**第一个**
 * 阻断它的门。这份直方图决定下一步改哪个门，不许凭猜测改。
 *
 * 同时回答 Lead 的三个核对点：
 * - `hits` 的语义
 * - V1 `recall` 的分母里是否含「词典查不到的词」
 * - `anchors=0` 时 σ 的路径是否与有锚点路径共用同一套代码
 */
class BlockerHistogramProbe {

    @Test
    fun uncertainBlockerHistogram() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val engine = IndependentAligner()
        val document = engine.align(book.source, book.translation, OracleLexicon(book))

        println("=== ${book.variant.label}: ${document.stats.summaryLine()} ===")
        document.notes.forEach { println("  note: $it") }

        val histogram = LinkedHashMap<String, Int>()
        var aligned = 0
        document.units.forEach { unit ->
            when (unit.status) {
                AlignmentStatus.ALIGNED -> aligned++
                AlignmentStatus.UNALIGNED ->
                    histogram.merge("UNALIGNED:${unit.skipReason}", 1, Int::plus)
                AlignmentStatus.UNCERTAIN ->
                    histogram.merge(unit.evidence.reasons.firstOrNull() ?: "unknown", 1, Int::plus)
            }
        }
        println()
        println("=== 阻断原因直方图（ALIGNED=$aligned / 共 ${document.units.size}）===")
        histogram.entries.sortedByDescending { it.value }.forEach { (why, count) ->
            val pct = Math.round(count * 1000.0 / document.units.size) / 10.0
            println("  ${why.padEnd(42)} : ${count.toString().padStart(3)}  (${pct}%)")
        }
        println()
        println("=== 验证层与 V1 分母诊断 ===")
        println("  " + DiagnosticHooks.v1Diagnostics(book).joinToString("\n  "))
    }

    private fun contentWords(source: BookSide, sentence: Int): List<String> {
        val text = source.sentText[sentence]
        val result = ArrayList<String>(8)
        var i = 0
        while (i < text.length) {
            if (TextScan.isLetter(text[i])) {
                var j = i + 1
                while (j < text.length && (TextScan.isLetter(text[j]) || text[j] == '\'' || text[j] == '-')) j++
                val word = text.substring(i, j).lowercase()
                if (word.length >= 2) result.add(word)
                i = j
            } else i++
        }
        return result
    }

    private fun fmt(v: Double): String =
        if (v == Double.MAX_VALUE) "inf" else (Math.round(v * 1000.0) / 1000.0).toString()
}
