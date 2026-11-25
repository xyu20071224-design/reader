package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import kotlin.test.Test

/**
 * **中文句数 9336 vs 9300 的差异溯源**（Lead 要求，数字必须能解释）。
 *
 * 三种口径逐个算，找出多出的句子来自哪里：
 * 1. 原始 `split()` 总数（所有切出的句子）
 * 2. `sentLangUnit = true` 的句子数（含字母或汉字）
 * 3. 纯 CJK 的句子数
 */
class SentenceCountProbe {

    @Test
    fun reconcileSentenceCount() {
        val inputs = AlignmentCorpusInputs.requireCase("F15")
        val zhChapters = inputs.chinese
        val enChapters = inputs.english
        println("=== 中文句数口径 ===")
        reconcile("zh", zhChapters)
        println()
        println("=== 英文句数口径（对照）===")
        reconcile("en", enChapters)
    }

    private fun reconcile(label: String, chapters: List<AlignmentChapter>) {
        var rawSplit = 0
        var langUnit = 0
        var pureCjk = 0
        var blank = 0
        var nonUnitSamples = ArrayList<String>(8)
        var pureCjkSamples = ArrayList<String>(8)
        for (chapter in chapters) {
            for (paragraph in chapter.paragraphs) {
                val sentences = SentenceSplitter.split(paragraph)
                rawSplit += sentences.size
                for (sentence in sentences) {
                    if (sentence.isBlank()) {
                        blank++
                        continue
                    }
                    val hasLetter = sentence.any { it in 'A'..'z' }
                    val hasCjk = sentence.any { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }
                    if (hasLetter || hasCjk) langUnit++ else if (nonUnitSamples.size < 8) {
                        nonUnitSamples.add(sentence)
                    }
                    if (hasCjk && !hasLetter) {
                        pureCjk++
                        if (pureCjkSamples.size < 5) pureCjkSamples.add(sentence.take(20))
                    }
                }
            }
        }
        println("  [$label] 章=${chapters.size} 段=${chapters.sumOf { it.paragraphs.size }}")
        println("    split() 总句数      = $rawSplit")
        println("    isLangUnit=true     = $langUnit")
        println("    其中纯 CJK（无拉丁）= $pureCjk")
        println("    空串                = $blank")
        println("    非语言单位样例：${nonUnitSamples}")
        if (pureCjkSamples.isNotEmpty()) println("    纯 CJK 样例：$pureCjkSamples")

        // BookSide 视角
        val side = BookSide.build(
            if (label == "zh") SideKind.TARGET else SideKind.SOURCE,
            chapters
        )
        println("    BookSide.sentCount  = ${side.sentCount}")
        val sideLangUnit = (0 until side.sentCount).count { side.sentLangUnit[it] }
        println("    BookSide 的有效句   = $sideLangUnit")
        val sidePureCjk = (0 until side.sentCount).count { s ->
            side.sentLangUnit[s] && side.sentText[s].none { it in 'A'..'z' }
        }
        println("    BookSide 纯 CJK     = $sidePureCjk")
        println("    → 差异：sentCount(${side.sentCount}) − 有效句($sideLangUnit) = " +
            "${side.sentCount - sideLangUnit}")
    }

}
