package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **修复 A 的并排对照 trace**（Lead 要求的最有说服力证据）。
 *
 * 同一句话，在两种 `terms(E)` 策略下并排显示：
 * - **现状**：Top-K 词频选词（含功能词）
 * - **修复 A**：功能词按绝对 df 排除 + 元信息释义丢弃
 *
 * 每行显示 `word(idf) → 候选 → 是否命中译文`，末尾给 `命中/总数 = recall`。
 */
class AbComparisonProbe {

    @Test
    fun sideBySideTermsComparison() {
        val inputs = AlignmentCorpusInputs.requireCase("F01")
        val enChapters = inputs.english
        val zhChapters = inputs.chinese
        val ecdict = inputs.requireDictionary()
        val source = BookSide.build(SideKind.SOURCE, enChapters)
        val target = BookSide.build(SideKind.TARGET, zhChapters)
        CorpusExecution.requireSides(source, target)

        // 英文词的 df（出现句数）
        val df = HashMap<Int, Int>(16384)
        for (s in 0 until source.sentCount) {
            for (h in source.sentWordHashes[s]) df.merge(h, 1, Int::plus)
        }
        val surface = HashMap<Int, String>(8192)
        for (s in 0 until source.sentCount) {
            val text = source.sentText[s]
            var i = 0
            while (i < text.length) {
                if (TextScan.isLetter(text[i])) {
                    var j = i + 1
                    while (j < text.length && (TextScan.isLetter(text[j]) ||
                            text[j] == '\'' || text[j] == '-')) j++
                    val hash = TextScan.hashLowerAscii(text, i, j)
                    if (df.containsKey(hash)) surface.putIfAbsent(hash, text.substring(i, j).lowercase())
                    i = j
                } else i++
            }
        }
        val dfLimit = AlignTuning.CONTENT_WORD_DF_RATIO * source.sentCount

        println("=== terms(E) 两种策略的并排对照 ===")
        println("  英文句数=${source.sentCount}  功能词排除线 df > ${String.format("%.1f", dfLimit)}")
        println()

        // 选 3 个有代表性的句子（长度不一、且有真值译文可查）
        val candidates = listOf(6, 54, 900)
        var compared = 0
        for (index in candidates) {
            if (index >= minOf(source.sentCount, target.sentCount)) continue
            val english = source.sentText[index]
            if (english.length < 30) continue
            // 用「中英段落密度推算的位置」找一个大概率相关的译文（仅用于演示命中效果，
            // 不是真值——真值在 golden-samples 里，这里只要「有内容重叠的译文」即可）
            compared++
            val zhIndex = (index * target.sentCount / source.sentCount)
                .coerceIn(0, target.sentCount - 1)
            val zhText = target.sentNormText[zhIndex]
            println("--- 句 i=$index（配对探测用 zh[$zhIndex]，仅演示命中效果）---")
            println("  en=\"${english.take(100)}\"")
            println("  zh=\"${target.sentText[zhIndex].take(80)}\"")
            println()
            println("  【现状】Top-K 选词（含功能词）：")
            printStrategy(english, zhText, ecdict, df, surface, dfLimit = Double.MAX_VALUE)
            println()
            println("  【修复 A】功能词排除（df > ${String.format("%.0f", dfLimit)}）+ 元信息丢弃：")
            printStrategy(english, zhText, ecdict, df, surface, dfLimit = dfLimit)
            println()
        }
        check(compared > 0) { "No representative sentence was actually compared" }
        println("COMPARISON_EXECUTED sentences=$compared strategies=${compared * 2}")
    }

    private fun printStrategy(
        english: String,
        zhText: String,
        ecdict: MeaningProvider,
        df: Map<Int, Int>,
        surface: Map<Int, String>,
        dfLimit: Double
    ) {
        val words = englishWords(english)
        var total = 0
        var hit = 0
        var excluded = 0
        var metaDropped = 0
        val keptLines = ArrayList<String>(16)
        for (word in words) {
            val hash = TextScan.hashLowerAscii(word, 0, word.length)
            val wordDf = df[hash] ?: 0
            if (wordDf > dfLimit) {
                excluded++
                continue
            }
            val glosses = ecdict.chineseCandidates(word)
            if (glosses.isEmpty()) continue
            val usable = ArrayList<String>(4)
            for (gloss in glosses) {
                if (gloss.length !in AlignTuning.TERM_MIN_CHARS..AlignTuning.TERM_MAX_CHARS) continue
                if (isMeta(gloss)) {
                    metaDropped++
                    continue
                }
                usable.add(gloss)
            }
            if (usable.isEmpty()) continue
            total++
            val matched = usable.firstOrNull { zhText.contains(it) }
            if (matched != null) hit++
            keptLines.add(
                "    $word(df=$wordDf) → ${usable.take(3)} " +
                    if (matched != null) "✅\"$matched\"" else "❌"
            )
        }
        keptLines.forEach { println(it) }
        val recall = if (total > 0) hit.toDouble() / total else 0.0
        println(
            "    命中 $hit/$total = ${String.format("%.3f", recall)}" +
                "（排除功能词 $excluded，丢弃元信息释义 $metaDropped）"
        )
    }

    private fun isMeta(gloss: String): Boolean {
        for (pattern in AlignTuning.META_GLOSS_PATTERNS) {
            if (gloss == pattern || gloss.endsWith(pattern)) return true
        }
        return false
    }

    private fun englishWords(sentence: String): List<String> {
        val result = ArrayList<String>(16)
        var i = 0
        while (i < sentence.length) {
            if (TextScan.isLetter(sentence[i])) {
                var j = i + 1
                while (j < sentence.length && (TextScan.isLetter(sentence[j]) ||
                        sentence[j] == '\'' || sentence[j] == '-')) j++
                val word = sentence.substring(i, j).lowercase()
                if (word.length >= 2) result.add(word)
                i = j
            } else i++
        }
        return result
    }

}
