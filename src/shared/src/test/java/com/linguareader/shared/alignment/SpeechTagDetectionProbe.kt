package com.linguareader.shared.alignment

import org.junit.Test

/**
 * 验证「切点是否紧跟说话人引导语」的判定函数行为。
 *
 * 这个判定是 [com.linguareader.shared.translation.TranslationAligner] 里
 * `opensSpeechTag` 的镜像实现——翻译器内部方法不可见，这里复刻同一逻辑单独测形态。
 */
class SpeechTagDetectionProbe {

    private val speechVerb = Regex("说|道|问|答|喊|叫|嚷|叹|嘟囔|咕哝|低语|耳语|回答|补充")
    private val closers = setOf('」', '』', '”', '’', '"', '\'', '）', '】', '］', ')', ']')
    private val breaks = setOf('。', '！', '？', '!', '?', '；', ';', '…')

    private fun looksLikeSpeechTag(rest: String, maxChars: Int): Boolean {
        if (rest.length > maxChars) return false
        if (rest.any { it in "，,；;" }) return false
        return speechVerb.containsMatchIn(rest)
    }

    private fun opensSpeechTag(text: String, pos: Int): Boolean {
        var k = pos
        while (k < text.length && (text[k].isWhitespace() || text[k] in closers)) k++
        if (k >= text.length) return false
        var end = k
        while (end < text.length && text[end] !in breaks) end++
        val head = text.substring(k, end).trim()
        if (head.isEmpty()) return false
        if (head.firstOrNull() in closers) return false
        return looksLikeSpeechTag(head, maxChars = 12)
    }

    @Test
    fun measure() {
        val cases = listOf(
            // 真实金标准样本里的形态
            "「他們可以往北走二十哩，從烈酒橋過河，或者他們也可以游過來。」梅里回答：「不過我想…」" to
                "s5：期望「梅里回答」被判为说话人引导语",
            "「你在愛隆的屋子裡，現在是早上十點。」一個聲音說：「如果你想知道得更清楚一點…」" to
                "s28：期望「一個聲音說」被判为说话人引导语",
            "「您讓我受寵若驚了，林德。」他說：「但我實在沒力氣從頭再朗誦一次。」" to
                "s34：期望「他說」被判为说话人引导语",
            // 对照：真正的句子边界
            "「醒醒，霍比特人們！」他喊道。「山姆！」" to
                "对照组：两个独立引语间，应可切",
            "他走了。她哭了。" to "无引号普通两句，应可切"
        )

        for ((text, note) in cases) {
            println("=== $note ===")
            println("  文本: ${text.take(70)}")
            // 找出所有句读点，逐个判定
            var i = 0
            while (i < text.length) {
                if (text[i] in breaks) {
                    var j = i + 1
                    while (j < text.length && text[j] in closers) j++
                    if (j < text.length) {
                        val headStart = j
                        var end = headStart
                        while (end < text.length && (text[end].isWhitespace() || text[end] in closers)) end++
                        var stop = end
                        while (stop < text.length && text[stop] !in breaks) stop++
                        val head = text.substring(end, stop).trim()
                        val isTag = opensSpeechTag(text, j)
                        println(
                            "    切点@$j  后续=«${head.take(24)}»  " +
                                "判为说话人引导语=$isTag"
                        )
                    }
                    i = j
                } else i++
            }
            println()
        }
    }
}
