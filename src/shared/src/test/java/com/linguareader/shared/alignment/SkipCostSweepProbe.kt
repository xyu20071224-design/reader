package com.linguareader.shared.alignment

import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.ln

/**
 * 参数扫描：`SKIP_SENTENCE_COST` 对最终用户体验的影响。
 *
 * 目的不是「调到最好看」，而是找出**是否存在一个值让结果优于原始版本**。
 * 原始版本（无跳过能力）的基线：
 *   UNCERTAIN 318 / UNALIGNED 103 / A-wrong 0 / 无答案 13.13%
 *
 * 由于 `SKIP_SENTENCE_COST` 是 `private const`，这里用**反射**读取不到不同值；
 * 因此改为直接对比「有跳过 vs 无跳过」两种实现的产出，并报告
 * 当前常量下的完整分布。
 */
class SkipCostSweepProbe {

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    @Test
    fun sweep() {
        var repo: File? = File(System.getProperty("user.dir"))
        while (repo != null && !File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").isFile) {
            repo = repo.parentFile
        }
        if (repo == null) { println("[sweep] SKIP"); return }

        val json = JSONObject(File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")
        val enParas = ArrayList<String>()
        val zhParas = ArrayList<String>()
        data class P(val en: String, val zh: String)
        val truth = ArrayList<P>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            enParas += (0 until en.length()).joinToString(" ") { en.getString(it) }
            zhParas += (0 until zh.length()).joinToString("") { zh.getString(it) }
            for (k in 0 until en.length()) truth += P(en.getString(k), zh.getString(k))
        }

        val provider = EcdictProvider.load(repo)
        val doc = AiTranslationAligner().align(
            listOf(AlignmentChapter("c", enParas)),
            listOf(AlignmentChapter("c", zhParas)),
            provider
        )

        val truthSet = HashMap<String, MutableSet<String>>()
        for (t in truth) truthSet.getOrPut(norm(t.en)) { HashSet() }.add(norm(t.zh))
        val unitOf = HashMap<String, AlignedUnit>()
        for (u in doc.units) unitOf[norm(u.sourceText)] = u

        var aq = 0; var aw = 0; var uc = 0; var uw = 0; var nn = 0
        for (t in truth) {
            val u = unitOf[norm(t.en)] ?: continue
            val got = norm(u.targetText)
            val correct = got.isNotEmpty() && truthSet[norm(t.en)]?.contains(got) == true
            when (u.status) {
                AlignmentStatus.ALIGNED -> if (correct) aq++ else aw++
                AlignmentStatus.UNCERTAIN -> if (correct) uc++ else uw++
                AlignmentStatus.UNALIGNED -> nn++
            }
        }
        val total = truth.size
        println("=== 当前实现（SKIP_SENTENCE_COST 见源码常量）===")
        println("  句级：ALIGNED 对=$aq 错=$aw | UNCERTAIN 对=$uc 错=$uw | UNALIGNED=$nn  合计=$total")
        println("  无答案率 = %.2f%%".format(nn * 100.0 / total))
        println("  静默错率 = %.2f%%  ← 红线，必须为 0".format(aw * 100.0 / total))
        println("  告警错率 = %.2f%%".format(uw * 100.0 / total))

        println()
        println("=== 与历史基线对比 ===")
        println("  版本               UNCERTAIN  UNALIGNED  A-wrong  无答案")
        println("  原始（无跳过）        318        103        0     13.13%")
        println("  跳过=0.12           235        189        5      ~14%")
        println("  跳过=0.60（当前）    $uc        $nn        $aw     %.2f%%".format(nn * 100.0 / total))
        println()
        if (aw > 0) {
            println("  ❌ 当前版本有静默错（$aw 条），**不如原始版本**，应回退跳过能力。")
        } else if (nn < 103) {
            println("  ✅ 无静默错，且 UNALIGNED 少于原始（$nn < 103），方向正确。")
        } else {
            println("  ⚠️ 无静默错，但 UNALIGNED 未减少（$nn >= 103），跳过能力没带来收益。")
        }
    }
}
