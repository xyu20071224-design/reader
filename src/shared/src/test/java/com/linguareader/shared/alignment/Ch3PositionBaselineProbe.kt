package com.linguareader.shared.alignment

import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 诊断：本次「730 句真实 AI 译本」验收**是否过于容易**。
 *
 * 背景：翻译时强制了「逐段句数 1:1」。如果中英文句数天然相等，那么「第 k 句配第 k 句」
 * 按位置硬映射就可能是对的——那样 100% precision 主要证明「引擎没打乱顺序」，
 * 而不是「引擎能靠内容找出对应」。
 *
 * 本探针回答三个问题（只打印，不断言）：
 *  1. 引擎的 ALIGNED 单元里，`targetIndexInParagraph == sourceIndexInParagraph` 的比例
 *     —— 接近 100% 说明成功基本由位置给出。
 *  2. 每个段落在引擎切分下 enCount 与 zhCount 是否相等（相等 = 位置映射可用）。
 *  3. 纯位置基线（不给任何内容信号）的 precision/coverage 是多少
 *     —— 与引擎对比，差值才是内容通道的真实贡献。
 */
class Ch3PositionBaselineProbe {

    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "artifacts/ai-ch3/ch3_merged_partial.json").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun probe() {
        val repo = repoRoot() ?: run { println("[baseline] SKIP"); return }
        val json = JSONObject(File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").readText(Charsets.UTF_8))
        val paras = json.getJSONArray("paragraphs")

        val enParagraphs = ArrayList<String>()
        val zhParagraphs = ArrayList<String>()
        val truth = ArrayList<List<Pair<String, String>>>()
        for (i in 0 until paras.length()) {
            val p = paras.getJSONObject(i)
            val en = p.getJSONArray("en")
            val zh = p.getJSONArray("zh")
            val enText = StringBuilder()
            val zhText = StringBuilder()
            val pairs = ArrayList<Pair<String, String>>(en.length())
            for (k in 0 until en.length()) {
                if (enText.isNotEmpty()) enText.append(' ')
                enText.append(en.getString(k))
                zhText.append(zh.getString(k))
                pairs.add(en.getString(k) to zh.getString(k))
            }
            enParagraphs.add(enText.toString())
            zhParagraphs.add(zhText.toString())
            truth.add(pairs)
        }

        fun norm(s: String) = s.filterNot { it.isWhitespace() }

        // ── 1. 纯位置基线：段内第 k 句英文 ↔ 第 k 句中文（不经过任何引擎）
        //        前提：用与引擎同源的分句器切中文，句数相等才可用
        var posAligned = 0
        var posCorrect = 0
        var posParaUsable = 0
        var posParaTotal = 0
        var totalEn = 0
        for (i in enParagraphs.indices) {
            val enSents = com.linguareader.shared.tts.SentenceSplitter.split(enParagraphs[i])
            val zhSents = com.linguareader.shared.tts.SentenceSplitter.split(zhParagraphs[i])
            totalEn += enSents.size
            posParaTotal++
            if (enSents.size != zhSents.size) continue
            posParaUsable++
            val truthMap = HashMap<String, String>()
            for ((e, z) in truth[i]) truthMap[norm(e)] = norm(z)
            for (k in enSents.indices) {
                posAligned++
                val want = truthMap[norm(enSents[k])]
                if (want != null && want == norm(zhSents[k])) posCorrect++
            }
        }
        println("=== 纯位置基线（不经过引擎）===")
        println("  paragraphs=${posParaTotal}  句数相等的段=${posParaUsable}")
        println("  可位置映射的英文句=${posAligned} / 总英文句=${totalEn}")
        println("  其中正确=${posCorrect}")
        println("  position-baseline precision = %.2f%%".format(
            if (posAligned == 0) 0.0 else posCorrect * 100.0 / posAligned))
        println("  position-baseline coverage  = %.2f%%".format(posCorrect * 100.0 / totalEn))

        // ── 2. 引擎 ALIGNED 单元里，位置是否就是答案
        val provider = EcdictProvider.load(repo)
        val doc = AiTranslationAligner().align(
            listOf(AlignmentChapter("Chapter 3", enParagraphs)),
            listOf(AlignmentChapter("第3章", zhParagraphs)),
            provider
        )
        val truthMapAll = HashMap<String, String>()
        for (pairs in truth) for ((e, z) in pairs) truthMapAll[norm(e)] = norm(z)

        var aligned = 0
        var sameIndex = 0
        var differentIndex = 0
        val driftExamples = ArrayList<String>()
        for (u in doc.units) {
            if (u.status != AlignmentStatus.ALIGNED) continue
            aligned++
            if (u.targetIndexInParagraph == u.sourceIndexInParagraph) {
                sameIndex++
            } else {
                differentIndex++
                if (driftExamples.size < 5) {
                    driftExamples.add(
                        "p${u.sourceParagraph.take(18)} | srcIdx=${u.sourceIndexInParagraph} " +
                            "tgtIdx=${u.targetIndexInParagraph}"
                    )
                }
            }
        }
        println()
        println("=== 引擎 ALIGNED 单元的位置一致性 ===")
        println("  ALIGNED=$aligned")
        println("  targetIndex == sourceIndex : $sameIndex  (%.2f%%)".format(sameIndex * 100.0 / aligned))
        println("  targetIndex != sourceIndex : $differentIndex")
        driftExamples.forEach { println("    $it") }

        // ── 3. 结论提示
        println()
        println("=== 判读 ===")
        val posPct = posCorrect * 100.0 / posAligned.coerceAtLeast(1)
        println("  纯位置基线 precision = %.2f%%；引擎 precision = 100.00%%".format(posPct))
        if (posPct >= 99.0) {
            println("  ⚠️ 位置基线本身已达 %.2f%% —— 本测试的 1:1 强制使位置映射几乎必然正确。".format(posPct))
            println("     引擎的 100%% precision 主要证明「没打乱顺序」，不是「能靠内容找对应」。")
        } else {
            println("  位置基线 %.2f%% 明显低于引擎，说明内容通道确有贡献。".format(posPct))
        }
    }
}
