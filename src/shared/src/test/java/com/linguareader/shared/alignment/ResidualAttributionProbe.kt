package com.linguareader.shared.alignment

import com.linguareader.shared.translation.MeaningIndex
import com.linguareader.shared.translation.TranslationAligner
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * **残差归因**：AI 译本上剩余静默错/无答案按**形态**分类，判断还有没有便宜可捡。
 *
 * 每个真值英文句落在下列之一：
 *  - `CORRECT`   档案里第一条句对就是正确译文
 *  - `MERGED`    返回的是**包含**本句正确译文、但更长的合并串（"A. B." 形态）
 *  - `OFFSET`    返回了**同段另一句**的译文（错位 / 错配）
 *  - `ELSEWHERE` 返回了**别段**的译文
 *  - `NONE`      没有任何句级条目（段级兜底）
 *
 * `MERGED` 是可修的（拆分落盘）；`OFFSET` 才是真错配。
 */
class ResidualAttributionProbe {

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, EcdictProvider.EXPORT_RELATIVE_PATH).isFile) dir = dir.parentFile
        return dir
    }

    @Test
    fun measure() {
        val repo = repoRoot() ?: run { println("[res] SKIP"); return }
        val ecdict = EcdictProvider.load(repo)
        val meaningIndex: MeaningIndex? = ecdict?.let { p ->
            object : MeaningIndex {
                override fun phrasesOf(word: String): Set<String> =
                    p.chineseCandidates(word).flatMap { EcdictProvider.parseGlosses(it) }.toSet()
            }
        }

        for ((name, rel) in listOf(
            "ch1" to "artifacts/ai-ch1/ch1_truth.json",
            "ch3" to "artifacts/ai-ch3/ch3_merged_partial.json"
        )) {
            val file = File(repo, rel)
            if (!file.isFile) { println("=== $name MISSING ==="); continue }
            val arr = JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("paragraphs")

            val enParas = ArrayList<String>()
            val zhParas = ArrayList<String>()
            // 每段的真值句对，用于判定「返回的是同段哪一句」
            val paraTruth = ArrayList<List<Pair<String, String>>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val en = o.getJSONArray("en")
                val zh = o.getJSONArray("zh")
                val enText = StringBuilder()
                val zhText = StringBuilder()
                val pairs = ArrayList<Pair<String, String>>()
                for (k in 0 until en.length()) {
                    if (enText.isNotEmpty()) enText.append(' ')
                    enText.append(en.getString(k))
                    zhText.append(zh.getString(k))
                    if (k < zh.length()) pairs += en.getString(k) to zh.getString(k)
                }
                enParas.add(enText.toString())
                zhParas.add(zhText.toString())
                paraTruth.add(pairs)
            }

            val pairs = TranslationAligner.align(listOf(enParas), listOf(zhParas), meaningIndex)

            // enSentence → 落盘译文列表
            val gotByEn = HashMap<String, MutableList<String>>()
            for (p in pairs) gotByEn.getOrPut(norm(p.enSentence)) { ArrayList() } += norm(p.zhSentence)
            // enParagraph → 该段落盘的所有译文（用于判定 ELSEWHERE/OFFSET）
            val gotByPara = HashMap<String, MutableList<String>>()
            for (p in pairs) gotByPara.getOrPut(norm(p.enParagraph)) { ArrayList() } += norm(p.zhSentence)

            var correct = 0
            var merged = 0
            var offset = 0
            var elsewhere = 0
            var none = 0
            val samples = LinkedHashMap<String, MutableList<String>>()

            fun bucket(kind: String, msg: String) {
                val list = samples.getOrPut(kind) { ArrayList() }
                if (list.size < 6) list += msg
            }

            for (pi in enParas.indices) {
                val truthPairs = paraTruth[pi]
                val enParaNorm = norm(enParas[pi])
                val paraCandidates = gotByPara[enParaNorm].orEmpty()
                for ((en, zh) in truthPairs) {
                    val enKey = norm(en)
                    val zhKey = norm(zh)
                    val got = gotByEn[enKey]
                    when {
                        got == null -> {
                            none++
                            bucket("NONE", "EN «${en.take(50)}»\n      want ZH «${zh.take(40)}»")
                        }
                        got.any { it == zhKey } -> correct++
                        got.any { it.contains(zhKey) } -> {
                            merged++
                            bucket(
                                "MERGED",
                                "EN «${en.take(50)}»\n      got  «${got.first().take(50)}»\n      want «${zh.take(40)}»"
                            )
                        }
                        paraCandidates.any { it == zhKey } -> {
                            offset++
                            bucket(
                                "OFFSET",
                                "EN «${en.take(50)}»\n      got  «${got.first().take(40)}»\n      want «${zh.take(40)}»"
                            )
                        }
                        else -> {
                            elsewhere++
                            bucket(
                                "ELSEWHERE",
                                "EN «${en.take(50)}»\n      got  «${got.first().take(40)}»\n      want «${zh.take(50)}»"
                            )
                        }
                    }
                }
            }

            val total = correct + merged + offset + elsewhere + none
            fun p(v: Int) = "%.2f%%".format(v * 100.0 / total)
            println()
            println("=== $name （真值句 $total）===")
            println("  CORRECT   = $correct (${p(correct)})")
            println("  MERGED    = $merged (${p(merged)})   ← 返回包含正确译文的合并串，可修")
            println("  OFFSET    = $offset (${p(offset)})   ← 返回同段他句译文，真错配")
            println("  ELSEWHERE = $elsewhere (${p(elsewhere)})   ← 返回别段译文")
            println("  NONE      = $none (${p(none)})   ← 段级兜底")
            for ((kind, list) in samples) {
                println("  --- $kind 样例 ---")
                list.forEach { println("    $it") }
            }
        }
    }
}
