package com.linguareader.shared.alignment

import org.json.JSONObject
import org.junit.Test
import java.io.File

/** 最小诊断：引擎返回的三态与 targetText 非空性统计。 */
class P0StatusCountProbe {

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    @Test
    fun count() {
        var repo: File? = File(System.getProperty("user.dir"))
        while (repo != null && !File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").isFile) {
            repo = repo.parentFile
        }
        if (repo == null) { println("[cnt] SKIP"); return }

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

        val provider = EcdictProvider.load(repo)
        val doc = AiTranslationAligner().align(
            listOf(AlignmentChapter("c", enParas)),
            listOf(AlignmentChapter("c", zhParas)),
            provider
        )
        println("[cnt] units=${doc.units.size}")
        println("[cnt] status = " + doc.units.groupingBy { it.status.name }.eachCount())
        println("[cnt] UNALIGNED skipReason = " +
            doc.units.filter { it.status == AlignmentStatus.UNALIGNED }
                .groupingBy { it.skipReason?.name ?: "null" }.eachCount())
        println("[cnt] targetText 非空 by status:")
        for (st in AlignmentStatus.values()) {
            val us = doc.units.filter { it.status == st }
            val nonEmpty = us.count { norm(it.targetText).isNotEmpty() }
            println("   $st: ${us.size} 条，targetText 非空 $nonEmpty")
        }
        println("[cnt] stats = ${doc.stats.summaryLine()}")
        println()
        println("[cnt] === 覆盖率一致性校验 ===")
        println("[cnt] enSide 应在 ${enParas.sumOf { com.linguareader.shared.tts.SentenceSplitter.split(it).size }} 句左右")
        println("[cnt] units=${doc.units.size}  vs  期望 ${enParas.sumOf { com.linguareader.shared.tts.SentenceSplitter.split(it).size }}")
        val covered = doc.units.map { norm(it.sourceText) }.toHashSet()
        var missing = 0
        val missingSamples = ArrayList<String>()
        for (p in enParas) {
            for (s in com.linguareader.shared.tts.SentenceSplitter.split(p)) {
                if (norm(s) !in covered) {
                    missing++
                    if (missingSamples.size < 6) missingSamples += s.take(60)
                }
            }
        }
        println("[cnt] 未覆盖的英文句 = $missing")
        missingSamples.forEach { println("       MISS: $it") }
    }
}
