package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 精确诊断：为什么 stats.sentences = 728 而真值 730？哪些句子没被覆盖？
 * 以及 103 条 UNALIGNED 全为 AMBIGUOUS 是否合理。
 */
class Ch3LossDiagnosisProbe {

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    @Test
    fun diagnose() {
        var repo: File? = File(System.getProperty("user.dir"))
        while (repo != null && !File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").isFile) {
            repo = repo.parentFile
        }
        if (repo == null) { println("[loss] SKIP"); return }

        val json = JSONObject(File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")
        val enParas = ArrayList<String>()
        val zhParas = ArrayList<String>()
        val truthSents = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            enParas += (0 until en.length()).joinToString(" ") { en.getString(it) }
            zhParas += (0 until zh.length()).joinToString("") { zh.getString(it) }
            for (k in 0 until en.length()) truthSents += en.getString(k) to zh.getString(k)
        }

        // 复现引擎的切分：en 用 split，zh 用 split
        var enCount = 0
        var zhCount = 0
        for (p in enParas) enCount += SentenceSplitter.split(p).size
        for (p in zhParas) zhCount += SentenceSplitter.split(p).size
        println("[loss] truth=${truthSents.size}")
        println("[loss] 输入段落数=${enParas.size}")
        println("[loss] en split 后句数=$enCount   zh split 后句数=$zhCount")

        // 找出 en split 后句数与真值不一致的段
        var mismatchParas = 0
        val details = ArrayList<String>()
        for (i in enParas.indices) {
            val sp = SentenceSplitter.split(enParas[i])
            val truthN = arr.getJSONObject(i).getJSONArray("en").length()
            if (sp.size != truthN) {
                mismatchParas++
                if (details.size < 8) details += "p${arr.getJSONObject(i).getInt("p")}: truth=$truthN split=${sp.size}"
            }
        }
        println("[loss] en 重新分句后句数不等的段 = $mismatchParas")
        details.forEach { println("       $it") }

        // 跑引擎，看哪些真值英文句没有对应单元
        val provider = EcdictProvider.load(repo)
        val doc = AiTranslationAligner().align(
            listOf(AlignmentChapter("c", enParas)),
            listOf(AlignmentChapter("c", zhParas)),
            provider
        )
        println("[loss] 引擎 units=${doc.units.size}  stats.sentences=${doc.stats.sourceSentenceTotal}")

        val unitEn = doc.units.map { norm(it.sourceText) }.toHashSet()
        val missingFromUnits = truthSents.filter { norm(it.first) !in unitEn }
        println("[loss] 真值英文句中，未出现在任何 unit 里的 = ${missingFromUnits.size}")
        missingFromUnits.take(10).forEach { (e, z) ->
            println("       en=${e.take(70)}")
            println("       zh=${z.take(40)}")
        }

        // 检查 UNALIGNED 的 skipReason 分布 + 抽样
        val un = doc.units.filter { it.status == AlignmentStatus.UNALIGNED }
        println()
        println("[loss] UNALIGNED=${un.size} 理由分布=" +
            un.groupingBy { it.skipReason?.name ?: "null" }.eachCount())
        println("[loss] UNALIGNED 抽样（前 8 条）:")
        un.take(8).forEach { u ->
            println("       src=${u.sourceText.take(60)}")
            println("         reasons=${u.evidence.reasons.take(2)}")
        }

        // 这些 UNALIGNED 所在段落是否句数不等
        var unInEqualPara = 0
        var unInUnequalPara = 0
        for (u in un) {
            // 定位段落：用 sourceParagraph 匹配
            val idx = enParas.indexOfFirst { it.contains(u.sourceText.take(20)) }
            if (idx >= 0) {
                val enN = SentenceSplitter.split(enParas[idx]).size
                val zhN = SentenceSplitter.split(zhParas[idx]).size
                if (enN == zhN) unInEqualPara++ else unInUnequalPara++
            }
        }
        println("[loss] UNALIGNED 落在「切分后句数相等」的段 = $unInEqualPara；落在不等的段 = $unInUnequalPara")
    }
}
