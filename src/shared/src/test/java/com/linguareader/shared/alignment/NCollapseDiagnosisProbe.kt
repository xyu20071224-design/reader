package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 诊断：45 条 UNALIGNED 到底卡在哪一步？
 *
 * 疑点：
 * 1. 段内句数不等（档 2 无解 / 超过穷举上限）
 * 2. 超过 `EXHAUSTIVE_MAX_SENTENCES = 8`
 * 3. 档 2 求解成功但 `verifyPair` 全判 UNCERTAIN
 */
class NCollapseDiagnosisProbe {

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    @Test
    fun diagnose() {
        var repo: File? = File(System.getProperty("user.dir"))
        while (repo != null && !File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").isFile) {
            repo = repo.parentFile
        }
        if (repo == null) { println("[nc] SKIP"); return }

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

        val un = doc.units.filter { it.status == AlignmentStatus.UNALIGNED }
        println("=== UNALIGNED = ${un.size} ===")

        // 按理由分组
        val byReason = un.groupingBy {
            (it.evidence.reasons.firstOrNull() ?: "(none)").substringBefore("(").trim()
        }.eachCount()
        println("理由分布:")
        byReason.entries.sortedByDescending { it.value }.forEach { println("  ${it.value}  ${it.key}") }

        // 对每条，找它所在段的 en/zh 句数
        println()
        println("=== 逐条定位（前 20 条）===")
        println("%-6s %-8s %-8s %-10s %s".format("p", "enN", "zhN", "档位", "英文句开头"))
        var overLimit = 0
        var countMismatch = 0
        for (u in un.take(20)) {
            val pi = enParas.indexOfFirst { it == u.sourceParagraph }
            if (pi < 0) { println("  ?? 段落未找到"); continue }
            val enN = SentenceSplitter.split(enParas[pi]).size
            val zhN = SentenceSplitter.split(zhParas[pi]).size
            val tier = when {
                enN == zhN -> "档1(1:1)"
                maxOf(enN, zhN) > 8 -> "超穷举上限"
                else -> "档2/3"
            }
            println("%-6d %-8d %-8d %-10s %s".format(pi, enN, zhN, tier, u.sourceText.take(40)))
        }

        // 全量统计
        println()
        println("=== 全量归因 ===")
        val tierCount = HashMap<String, Int>()
        for (u in un) {
            val pi = enParas.indexOfFirst { it == u.sourceParagraph }
            if (pi < 0) continue
            val enN = SentenceSplitter.split(enParas[pi]).size
            val zhN = SentenceSplitter.split(zhParas[pi]).size
            val tier = when {
                enN == zhN -> "档1(句数相等)"
                maxOf(enN, zhN) > 8 -> "超过穷举上限(>8)"
                else -> "档2/3(句数不等,可穷举)"
            }
            tierCount[tier] = (tierCount[tier] ?: 0) + 1
        }
        tierCount.entries.sortedByDescending { it.value }.forEach {
            println("  ${it.value}  ${it.key}")
        }

        // 涉及哪些段
        println()
        println("=== 涉及的段落（去重）===")
        val paras = un.mapNotNull { u -> enParas.indexOfFirst { it == u.sourceParagraph }.takeIf { it >= 0 } }
            .distinct().sorted()
        println("共 ${paras.size} 段: ${paras.take(20)}")
        for (pi in paras.take(10)) {
            val enN = SentenceSplitter.split(enParas[pi]).size
            val zhN = SentenceSplitter.split(zhParas[pi]).size
            val unInPara = un.count { norm(it.sourceParagraph) == norm(enParas[pi]) }
            println("  p$pi: en=$enN zh=$zhN UNALIGNED=$unInPara ${if (maxOf(enN,zhN) > 8) "← 超上限" else ""}")
        }
    }
}
