package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **词典桥内部诊断**（真实语料）：桥建起来了（1507 词条），为什么 `recall` 恒 0？
 *
 * 逐环节核对：
 * 1. 桥的词条长什么样（`terms` 的实际中文形）
 * 2. 中文句扫描命中了什么（`sentenceHits`）
 * 3. 英文句的 `terms(E)` 是什么
 * 4. 两侧命中的词条下标是否有交集
 *
 * 这是「同序号配对下 recall 恒 0」这个异常的唯一可信定位方式。
 */
class BridgeDiagnosisProbe {

    @Test
    fun traceBridgeOnRealCorpus() {
        val inputs = AlignmentCorpusInputs.requireCase("F08")
        val enChapters = inputs.english
        val zhChapters = inputs.chinese
        val ecdict = inputs.requireDictionary()
        val source = BookSide.build(SideKind.SOURCE, enChapters)
        val target = BookSide.build(SideKind.TARGET, zhChapters)
        CorpusExecution.requireSides(source, target)
        val density = DensityModel(source)
        val structural = BooleanArray(source.chapterCount) { source.isStructural(it) }
        density.totalRatioFromChapters(target.chapterChars, source.chapterWords, structural)
        val verify = VerifyLayer(source, target, density, ecdict)
        verify.build()

        println("=== 桥的构造结果 ===")
        println("  ${verify.notes}")

        // 用公开 API 逐句看：terms 是否为空、hits 是否为空、交集是否为 0
        println()
        println("=== 逐句诊断（找第一个「terms 非空但 recall=0」的句子）===")
        var printed = 0
        var scoreCalls = 0
        for (i in 0 until source.sentCount) {
            if (printed >= 6) break
            if (!source.sentLangUnit[i]) continue
            val t = i.coerceAtMost(target.sentCount - 1)
            if (!target.sentLangUnit[t]) continue
            scoreCalls++
            val score = verify.score(i, 1, t, 1)
            if (!score.available) continue
            val english = source.sentText[i]
            if (english.length < 40) continue
            println("  --- i=$i ---")
            println("    en=\"${english.take(90)}\"")
            println("    zh=\"${target.sentText[t].take(70)}\"")
            println(
                "    recall=${fmt(score.recall)} precision=${fmt(score.precision)} " +
                    "lift=${fmt(score.lift)}"
            )
            // 该句英文词 → 词典候选（直接问 provider）
            val words = englishWords(english)
            var withGloss = 0
            val sampleGlosses = ArrayList<String>()
            for (word in words) {
                val glosses = ecdict.chineseCandidates(word)
                if (glosses.isNotEmpty()) {
                    withGloss++
                    if (sampleGlosses.size < 5) sampleGlosses.add("$word→${glosses.take(3)}")
                }
            }
            println("    英文词数=${words.size}，其中有 ECDICT 释义=$withGloss")
            println("    样例：${sampleGlosses.joinToString("; ")}")
            // 这些释义在中文句里出现了吗？
            val zhText = target.sentNormText[t]
            var glossHitInSentence = 0
            val hitSamples = ArrayList<String>()
            for (word in words) {
                for (gloss in ecdict.chineseCandidates(word)) {
                    if (gloss.length in 2..4 && zhText.contains(gloss)) {
                        glossHitInSentence++
                        if (hitSamples.size < 5) hitSamples.add("$word→$gloss")
                        break
                    }
                }
            }
            println("    有释义且在中文句里出现的词=$glossHitInSentence")
            println("    命中样例：${hitSamples.joinToString("; ")}")
            printed++
        }
        check(scoreCalls > 0) { "No sentence was actually scored by the bridge probe" }
        println("BRIDGE_TRACE evaluated=$scoreCalls displayed=$printed")
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

    private fun fmt(v: Double): String = String.format("%.3f", v)

}
