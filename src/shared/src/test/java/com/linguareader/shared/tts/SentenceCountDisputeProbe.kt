package com.linguareader.shared.tts

import org.junit.Test

/**
 * 核实「英文句子数」在争议形态上的真实行为。
 *
 * translator-00 报告称 `J.R.R. TOLKIEN` 被 SentenceSplitter 切成 2 句。
 * 但 `initials` 正则保护的是 `([A-Za-z]\.){2,}` 的**最后一个**句点，
 * `J.R.R.` 的三个句点里前两个不protected，却因为「后面不是空白」而不切。
 * 到底切不切必须实测，不能靠推理——这决定 267 批译文的断行口径。
 */
class SentenceCountDisputeProbe {

    @Test
    fun measure() {
        val cases = listOf(
            "J.R.R. TOLKIEN",
            "Douglas A. Anderson May 2004",
            "Wayne G. Hammond & Christina Scull May 2004",
            "This is about the Dark Plague (S.R. 37) until the end.",
            "See J.R.R. Tolkien: A Descriptive Bibliography for details.",
            "Mr. Bilbo Baggins was rich.",
            "Dr. Watson arrived. He sat down.",
            "J.R.R. Tolkien died in 1973.",
            "He left. She stayed.",
            "‘Come here!’ he said. ‘Now.’",
            "It cost 4s. 6d. in those days.",
            "St. Louis is a city.",
            "i.e. the same thing.",
            "He was born in 1892. He died in 1973."
        )
        println("[dispute] === SentenceSplitter.split 真实结果 ===")
        for (c in cases) {
            val parts = SentenceSplitter.split(c)
            println("[dispute] count=${parts.size}  «$c»")
            parts.forEachIndexed { i, p -> println("[dispute]      [$i] «$p»") }
        }
    }
}
