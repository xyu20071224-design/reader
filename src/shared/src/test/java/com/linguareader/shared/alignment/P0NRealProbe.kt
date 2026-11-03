package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 诊断：为什么 N-real 涨到 14.19%（646 次点击）？这些句子真的没对应吗？
 */
class P0NRealProbe {

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    @Test
    fun diagnose() {
        var repo: File? = File(System.getProperty("user.dir"))
        while (repo != null && !File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").isFile) {
            repo = repo.parentFile
        }
        if (repo == null) { println("[nreal] SKIP"); return }

        val json = JSONObject(File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")
        val enParas = ArrayList<String>()
        val zhParas = ArrayList<String>()
        data class P(val en: String, val zh: String, val para: Int)
        val truth = ArrayList<P>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            enParas += (0 until en.length()).joinToString(" ") { en.getString(it) }
            zhParas += (0 until zh.length()).joinToString("") { zh.getString(it) }
            for (k in 0 until en.length()) truth += P(en.getString(k), zh.getString(k), i)
        }

        val provider = EcdictProvider.load(repo)
        val doc = AiTranslationAligner().align(
            listOf(AlignmentChapter("c", enParas)),
            listOf(AlignmentChapter("c", zhParas)),
            provider
        )
        val unitOf = HashMap<String, AlignedUnit>()
        for (u in doc.units) unitOf[norm(u.sourceText)] = u

        val un = doc.units.filter { it.status == AlignmentStatus.UNALIGNED }
        println("=== UNALIGNED 总数 = ${un.size} ===")
        println("理由分布 = " + un.groupingBy { it.skipReason?.name ?: "null" }.eachCount())
        println()
        println("首因文案分布:")
        un.groupingBy { (it.evidence.reasons.firstOrNull() ?: "(none)").substringBefore("(").take(60) }
            .eachCount().entries.sortedByDescending { it.value }.take(8)
            .forEach { println("  ${it.value}  ${it.key}") }

        println()
        println("=== 抽样 12 条 UNALIGNED（含真值看是否真无对应）===")
        un.take(12).forEach { u ->
            val t = truth.firstOrNull { norm(it.en) == norm(u.sourceText) }
            println("  en=${u.sourceText.take(58)}")
            println("    真值=${t?.zh?.take(45) ?: "(未找到)"}")
            println("    reason=${u.evidence.reasons.take(2)}")
        }

        // 统计：UNALIGNED 里「真值非空」的比例（说明其实有译文）
        val withTruth = un.count { u ->
            val t = truth.firstOrNull { norm(it.en) == norm(u.sourceText) }
            t != null && t.zh.isNotBlank()
        }
        println()
        println("=== 结论数据 ===")
        println("  UNALIGNED 中「真值有译文」的句数 = $withTruth / ${un.size}")
        println("  → 若接近 100%，说明大量句子被误判为无对应")

        // 检查 A-wrong（静默错）
        val truthSet = HashMap<String, MutableSet<String>>()
        for (t in truth) truthSet.getOrPut(norm(t.en)) { HashSet() }.add(norm(t.zh))
        val aWrong = doc.units.filter { u ->
            if (u.status != AlignmentStatus.ALIGNED) return@filter false
            val got = norm(u.targetText)
            val cands = truthSet[norm(u.sourceText)] ?: emptySet()
            got.isNotEmpty() && got !in cands
        }
        println()
        println("=== A-wrong（静默错，红线）= ${aWrong.size} ===")
        aWrong.take(8).forEach { u ->
            val t = truth.firstOrNull { norm(it.en) == norm(u.sourceText) }
            println("  en=${u.sourceText.take(55)}")
            println("    真值=${t?.zh?.take(40)}")
            println("    引擎=${u.targetText.take(40)}")
            println("    reasons=${u.evidence.reasons.take(2)}")
        }
    }
}
