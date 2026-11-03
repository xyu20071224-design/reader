package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * **P3 前置实验**：验证「AI 译文是否本来就已经按英文句数对应」。
 *
 * ## 为什么这个实验决定 P3 要不要做
 *
 * 现有 `TRANSLATION_SYSTEM_PROMPT` 已经明确要求：
 * 「直译为主；语序尽量贴近原文；**不要合并或拆分句子**」。
 *
 * 如果模型**真的遵守**，那：
 * - 中文句数应天然等于英文句数
 * - `SentenceSplitter` 重切后句数不等，纯粹是**切分器的问题**，不是模型的问题
 * - 那么 P3（让模型输出句界）就没必要——**改切分器或调整切分器输入就够了**
 *
 * 反之，如果模型经常合并/拆分句子，P3 才有价值。
 *
 * ## 判据
 *
 * 对每一段比较：
 * - `enN` = 英文句数（生产切分器）
 * - `zhN` = 中文句数（生产切分器重切）
 *
 * 如果 `zhN == enN` 占绝大多数 → 模型遵守了「不合并不拆分」，
 * 问题在切分器；反之 → 模型没遵守，P3 有价值。
 */
class P3SentenceCountExperiment {

    @Test
    fun measure() {
        var repo: File? = File(System.getProperty("user.dir"))
        while (repo != null &&
            !(File(repo, "artifacts/ai-ch1/ch1_truth.json").isFile &&
                File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").isFile)
        ) {
            repo = repo.parentFile
        }
        if (repo == null) { println("[p3exp] SKIP"); return }

        for ((name, rel) in listOf(
            "ch1" to "artifacts/ai-ch1/ch1_truth.json",
            "ch3" to "artifacts/ai-ch3/ch3_merged_partial.json"
        )) {
            val json = JSONObject(File(repo, rel).readText(Charsets.UTF_8))
            val arr = json.getJSONArray("paragraphs")
            var same = 0
            var diff = 0
            var diffTotalAbs = 0
            val diffSamples = ArrayList<String>()

            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val en = o.getJSONArray("en")
                val zh = o.getJSONArray("zh")
                // 真值：每段英文句数 vs 中文句数（翻译时就是逐句对应的）
                val enN = en.length()
                val zhN = zh.length()
                if (enN == zhN) same++ else {
                    diff++
                    diffTotalAbs += kotlin.math.abs(enN - zhN)
                    if (diffSamples.size < 5) diffSamples += "p$i: en=$enN zh=$zhN"
                }
            }
            println("=== $name ===")
            println("  段落总数 = ${arr.length()}")
            println("  **真值层面** enN == zhN 的段 = $same / ${arr.length()} " +
                "(${String.format("%.2f", same * 100.0 / arr.length())}%)")
            println("  不等 = $diff，平均差 ${if (diff > 0) String.format("%.2f", diffTotalAbs.toDouble() / diff) else "0"} 句")
            diffSamples.forEach { println("    $it") }

            // 再看：用生产切分器重切中文后，句数是否仍等于 enN
            var reSplitSame = 0
            var reSplitDiff = 0
            val reSplitSamples = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val en = o.getJSONArray("en")
                val zh = o.getJSONArray("zh")
                val zhText = (0 until zh.length()).joinToString("") { zh.getString(it) }
                val zhN2 = SentenceSplitter.split(zhText).size
                if (zhN2 == en.length()) reSplitSame++ else {
                    reSplitDiff++
                    if (reSplitSamples.size < 5) reSplitSamples += "p$i: en=${en.length()} resplit=$zhN2"
                }
            }
            println("  **重切后** zhN == enN 的段 = $reSplitSame / ${arr.length()} " +
                "(${String.format("%.2f", reSplitSame * 100.0 / arr.length())}%)")
            println("  不等 = $reSplitDiff")
            reSplitSamples.forEach { println("    $it") }
            println()
        }

        println("=== 判读 ===")
        println("  若「真值层面」几乎全等 → 模型遵守了「不合并不拆分」")
        println("  若「重切后」大量不等 → 问题在 SentenceSplitter 的句界判定，而非模型")
        println("  → 结论：P3（让模型输出句界）价值有限；应优先修切分器或让它接受构造性句界")
    }
}
