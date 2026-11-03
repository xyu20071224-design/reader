package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 诊断 P0 修复为何未生效：找出「句数不等」段落的实际句数与触发路径。
 */
class P0DiagnosisProbe {

    @Test
    fun diagnose() {
        var repo: File? = File(System.getProperty("user.dir"))
        while (repo != null && !File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").isFile) {
            repo = repo.parentFile
        }
        if (repo == null) { println("[p0] SKIP"); return }

        val json = JSONObject(File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")
        val enParas = ArrayList<String>()
        val zhParas = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            enParas += (0 until en.length()).joinToString(" ") { en.getString(it) }
            zhParas += (0 until zh.length()).joinToString("") { zh.getString(it) }
        }

        println("=== 段内句数（引擎重新分句后）===")
        var mismatch = 0
        val sizes = ArrayList<Triple<Int, Int, Int>>()
        for (i in enParas.indices) {
            val e = SentenceSplitter.split(enParas[i]).size
            val z = SentenceSplitter.split(zhParas[i]).size
            if (e != z) {
                mismatch++
                sizes += Triple(i, e, z)
            }
        }
        println("  不一致的段 = $mismatch / ${enParas.size}")
        println("  (段号, en句数, zh句数) 前 20:")
        sizes.take(20).forEach { println("    p${it.first}: en=${it.second} zh=${it.third}") }
        val maxBoth = sizes.maxOfOrNull { maxOf(it.second, it.third) } ?: 0
        println("  不一致段里最大句数 = $maxBoth")
        println("  EXHAUSTIVE_MAX_SENTENCES = 5  → 超过的段会直接返回 null")
        val overLimit = sizes.count { maxOf(it.second, it.third) > 5 }
        println("  因超过穷举上限而必然失败的段 = $overLimit / $mismatch")

        // 再看这些段的 UNALIGNED 数量
        val provider = EcdictProvider.load(repo)
        val doc = AiTranslationAligner().align(
            listOf(AlignmentChapter("c", enParas)),
            listOf(AlignmentChapter("c", zhParas)),
            provider
        )
        val un = doc.units.filter { it.status == AlignmentStatus.UNALIGNED }
        println()
        println("=== UNALIGNED 分布 ===")
        println("  总数=${un.size}")
        println("  理由分布=" + un.groupingBy { it.skipReason?.name ?: "null" }.eachCount())
        println("  首因文案分布=" + un.groupingBy {
            (it.evidence.reasons.firstOrNull() ?: "(none)").substringBefore("(").take(60)
        }.eachCount().entries.sortedByDescending { it.value }.take(6))
        println()
        println("  抽样 10 条:")
        un.take(10).forEach { println("    ${it.sourceText.take(50)} | ${it.evidence.reasons.firstOrNull()}") }
    }
}
