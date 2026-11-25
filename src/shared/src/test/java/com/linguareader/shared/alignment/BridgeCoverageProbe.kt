package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **词义桥的覆盖死因量化**（真实语料）：为什么 recall 恒 0？
 *
 * 核心检验：ECDICT 的**释义字串**到底有没有出现在译本的**中文正文**里？
 * 若「释义字串覆盖率」本身就极低，那任何匹配粒度（2–4 字、单字）都救不了。
 */
class BridgeCoverageProbe {

    @Test
    fun measureGlossCoverageInTranslation() {
        val inputs = AlignmentCorpusInputs.requireCase("F07")
        val zhChapters = inputs.chinese
        val ecdict = inputs.requireDictionary()
        val target = BookSide.build(SideKind.TARGET, zhChapters)
        val corpusText = buildString {
            for (s in 0 until target.sentCount) append(target.sentNormText[s])
        }
        println("=== 语料 ===")
        println("  中文字符总数=${corpusText.length}  有效句=${target.sentCount}")

        // 取 Top-N 高频英文词（本书），看它们的 ECDICT 释义在译文里出现了几个
        val enChapters = inputs.english
        val source = BookSide.build(SideKind.SOURCE, enChapters)
        CorpusExecution.requireSides(source, target)
        val frequency = HashMap<Int, Int>(8192)
        for (s in 0 until source.sentCount) {
            for (h in source.sentWordHashes[s]) frequency.merge(h, 1, Int::plus)
        }
        val surface = HashMap<Int, String>(4096)
        for (s in 0 until source.sentCount) {
            val text = source.sentText[s]
            var i = 0
            while (i < text.length) {
                if (TextScan.isLetter(text[i])) {
                    var j = i + 1
                    while (j < text.length && (TextScan.isLetter(text[j]) ||
                            text[j] == '\'' || text[j] == '-')) j++
                    val hash = TextScan.hashLowerAscii(text, i, j)
                    if (frequency.containsKey(hash)) surface.putIfAbsent(hash, text.substring(i, j).lowercase())
                    i = j
                } else i++
            }
        }
        val ranked = frequency.entries.sortedByDescending { it.value }.take(300)

        var examined = 0
        var withGloss = 0
        var glossAppears = 0
        val examples = ArrayList<String>(10)
        for (entry in ranked) {
            val word = surface[entry.key] ?: continue
            examined++
            val glosses = ecdict.chineseCandidates(word)
            if (glosses.isEmpty()) continue
            withGloss++
            var appeared = false
            for (gloss in glosses) {
                if (corpusText.contains(gloss)) {
                    appeared = true
                    if (examples.size < 12) {
                        examples.add("$word (${entry.value}次) → \"$gloss\" ✅ 在译文中出现")
                    }
                    break
                }
            }
            if (appeared) glossAppears++
            else if (examples.size < 12) {
                examples.add("$word (${entry.value}次) → ${glosses.take(3)} ❌ 均未出现")
            }
        }
        println()
        println("=== Top-300 高频英文词的 ECDICT 释义是否出现在译文中 ===")
        println("  检查词数=$examined，有 ECDICT 释义=$withGloss")
        println(
            "  **释义字串出现在译文中的词：$glossAppears/$withGloss = " +
                "${pct(glossAppears.toDouble() / withGloss.coerceAtLeast(1))}**"
        )
        println()
        println("  样例：")
        examples.forEach { println("    $it") }

        // 单字级别的覆盖率
        println()
        println("=== 单字级：ECDICT 释义的组成字有多少出现在译文里 ===")
        val corpusChars = corpusText.toSet()
        var charExamined = 0
        var charPresent = 0
        val missingChars = ArrayList<Char>(32)
        for (entry in ranked.take(100)) {
            val word = surface[entry.key] ?: continue
            for (gloss in ecdict.chineseCandidates(word)) {
                for (c in gloss) {
                    charExamined++
                    if (c in corpusChars) charPresent++
                    else if (missingChars.size < 30 && c !in missingChars) missingChars.add(c)
                }
            }
        }
        println(
            "  释义用字总数=$charExamined，其中出现在译文里=$charPresent " +
                "(${pct(charPresent.toDouble() / charExamined.coerceAtLeast(1))})"
        )
        println("  未出现的字（前 30）：${missingChars.joinToString("")}")
        println()
        println("  → 若单字覆盖率高但整串覆盖率低，说明**译文用的是同一批字但组合方式完全不同**")
        println("    （例如 `戒指` vs `戒`），那单字匹配应当有效——但它需要有 IDF 权重区分")
    }

    private fun pct(v: Double): String = "${Math.round(v * 10000.0) / 100.0}%"

}
