package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import org.junit.Test

/**
 * 分句缺陷的最小复现（诊断用，不做断言）。
 *
 * 目的：把「真实语料里观察到的切分错误」压缩成**可执行的最小用例**，
 * 这样规则表里的每一条都有可跑的证据，而不是转述。
 */
class SplitDefectReproProbe {

    private fun show(label: String, input: String) {
        val out = SentenceSplitter.split(input)
        println("[repro] $label")
        println("        in = ${input.replace("\n", "\\n")}")
        println("        out= $out  (n=${out.size})")
    }

    @Test
    fun reproduce() {
        println("=== A. 单字母缩写自带句点 + 后接大写词 ===")
        show("A1 'Neither can I.' 单独成段", "‘Neither can I.’")
        show("A2 'Neither can I.' 后有下一句", "‘Neither can I.’ The road goes on.")
        show("A3 人名首字母 A. Whitmore", "Its sign read only “A. Whitmore, Books” in faded gold letters.")
        show("A4 已有保护的双缩写 J. R. R.", "J. R. R. Tolkien wrote it.")
        show("A5 句末单字母 A.", "He got an A. Then he left.")

        println()
        println("=== B. 孤立引号 ===")
        show("B1 段落以右引号结尾", "Let me impress on you once more: don’t use it!’")
        show("B2 右引号独自收尾", "‘Go not to the Elves for counsel, for they will say both no and yes.’")
        show("B3 引号后无空格接内容", "‘He said.’‘She left.’")

        println()
        println("=== C. 嵌套引语中的说话人标签 ===")
        show(
            "C1 he says. 独立成句",
            "my dad, he says to me: Hallo, Sam! he says. I thought you were away."
        )
        show(
            "C2 says I to the Gaffer.",
            "Hissed at me, he did. It gave me quite a shudder. What sort of a fellow was he? says I to the Gaffer. I don’t know , says he; but he wasn’t a hobbit."
        )

        println()
        println("=== D. 对照：正常句子不应被切碎 ===")
        show("D1 普通两句", "He walked home. She stayed behind.")
        show("D2 缩略语前", "Mr. Smith arrived at 5 p.m. and left.")
    }
}
