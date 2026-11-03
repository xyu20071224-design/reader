package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 诊断 P0 的副作用：U-wrong 从 1.43% → 9.75%，找出 DP/穷举配对错误的样例。
 *
 * 怀疑点：`SKIP_SENTENCE_COST = 0.35` 可能偏高，导致 DP 宁愿「配一个错的」
 * 也不愿「跳过一句」；或者 1:N/N:1 合并惩罚过低，导致乱并。
 */
class P0SideEffectProbe {

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    @Test
    fun diagnose() {
        var repo: File? = File(System.getProperty("user.dir"))
        while (repo != null && !File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").isFile) {
            repo = repo.parentFile
        }
        if (repo == null) { println("[p0se] SKIP"); return }

        val json = JSONObject(File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")
        val enParas = ArrayList<String>()
        val zhParas = ArrayList<String>()
        data class P(val en: String, val zh: String, val para: Int, val idx: Int)
        val truth = ArrayList<P>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            enParas += (0 until en.length()).joinToString(" ") { en.getString(it) }
            zhParas += (0 until zh.length()).joinToString("") { zh.getString(it) }
            for (k in 0 until en.length()) truth += P(en.getString(k), zh.getString(k), i, k)
        }

        val truthSet = HashMap<String, MutableSet<String>>()
        for (t in truth) truthSet.getOrPut(norm(t.en)) { HashSet() }.add(norm(t.zh))

        val enSplit = enParas.map { SentenceSplitter.split(it) }
        val zhSplit = zhParas.map { SentenceSplitter.split(it) }

        // 找出「句数不等」的段，看其中句子是否被错配
        println("=== 句数不等段落里的错配（UNCERTAIN 且答案错）===")
        var checked = 0
        var wrong = 0
        val examples = ArrayList<String>()
        for (pi in enParas.indices) {
            val e = enSplit[pi]
            val z = zhSplit[pi]
            if (e.size == z.size) continue
            checked++
        }
        println("  句数不等段 = $checked")

        val provider = EcdictProvider.load(repo)
        val doc = AiTranslationAligner().align(
            listOf(AlignmentChapter("c", enParas)),
            listOf(AlignmentChapter("c", zhParas)),
            provider
        )
        val unitOf = HashMap<String, AlignedUnit>()
        for (u in doc.units) unitOf[norm(u.sourceText)] = u

        val uWrong = ArrayList<Pair<P, AlignedUnit>>()
        for (t in truth) {
            val u = unitOf[norm(t.en)] ?: continue
            if (u.status != AlignmentStatus.UNCERTAIN) continue
            val got = norm(u.targetText)
            val cands = truthSet[norm(t.en)] ?: emptySet()
            if (got.isNotEmpty() && got !in cands) uWrong += t to u
        }
        println("  U-wrong 总数（句级）= ${uWrong.size}")

        // 按段落是否句数不等分类
        val inMismatch = uWrong.filter { (t, _) ->
            val pi = t.para
            pi in enSplit.indices && enSplit[pi].size != zhSplit[pi].size
        }
        val inEqual = uWrong.filter { (t, _) ->
            val pi = t.para
            pi in enSplit.indices && enSplit[pi].size == zhSplit[pi].size
        }
        println("    落在「句数不等」段 = ${inMismatch.size}")
        println("    落在「句数相等」段 = ${inEqual.size}  ← 这些是新引入的")

        println()
        println("=== 抽样（句数不等段的错配）===")
        inMismatch.take(6).forEach { (t, u) ->
            println("  p${t.para} en=${t.en.take(55)}")
            println("      真值=${t.zh.take(40)}")
            println("      引擎=${u.targetText.take(40)}")
            println("      reasons=${u.evidence.reasons.take(2)}")
        }

        println()
        println("=== 抽样（句数相等段的错配，若存在）===")
        inEqual.take(6).forEach { (t, u) ->
            println("  p${t.para} en=${t.en.take(55)}")
            println("      真值=${t.zh.take(40)}")
            println("      引擎=${u.targetText.take(40)}")
        }

        // 检查这些错配单元的目标句子在段落里的位置偏移
        println()
        println("=== 位置偏移统计（U-wrong）===")
        val offsets = uWrong.mapNotNull { (t, u) ->
            val pi = t.para
            val z = zhSplit.getOrNull(pi) ?: return@mapNotNull null
            val wantIdx = z.indexOfFirst { norm(it) == norm(t.zh) }
            if (wantIdx < 0) null else (u.targetIndexInParagraph - wantIdx)
        }
        println("  可定位的 U-wrong = ${offsets.size}")
        println("  偏移分布 = " + offsets.groupingBy { it }.eachCount().toSortedMap())
    }
}
