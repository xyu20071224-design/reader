package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 定位第 1 章唯一一条静默错（A-wrong）的完整成因。
 *
 * 症状：
 * ```
 * EN : Then you will see Gandalf the Grey uncloaked.’
 * got: 「你要是再说一遍，我就会生气。那时你就会看见脱去伪装的灰袍甘…
 * ```
 * 看起来是长引语被错位配对。本探针要回答：
 * 1. 它属于哪一段？该段英文/中文各自几句？
 * 2. 段内是否句数相等（档 1）还是不等（档 2）？
 * 3. 引擎给出的 targetIndexInParagraph 与真值应在的位置差多少？
 * 4. 该段的完整配对情况（逐句对照），看是否整体偏移。
 */
class Ch1SilentWrongDiagnosisProbe {

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    @Test
    fun diagnose() {
        var repo: File? = File(System.getProperty("user.dir"))
        while (repo != null && !File(repo, "artifacts/ai-ch1/ch1_truth.json").isFile) {
            repo = repo.parentFile
        }
        if (repo == null) { println("[diag] SKIP"); return }

        val json = JSONObject(File(repo, "artifacts/ai-ch1/ch1_truth.json").readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")

        val enParas = ArrayList<String>()
        val zhParas = ArrayList<String>()
        data class P(val p: Int, val idx: Int, val en: String, val zh: String)
        val truth = ArrayList<P>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = o.getInt("p")
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            val enText = StringBuilder()
            val zhText = StringBuilder()
            for (k in 0 until en.length()) {
                if (enText.isNotEmpty()) enText.append(' ')
                enText.append(en.getString(k))
                zhText.append(zh.getString(k))
                truth += P(p, k, en.getString(k), zh.getString(k))
            }
            enParas.add(enText.toString())
            zhParas.add(zhText.toString())
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

        // 找所有 ALIGNED 但错的
        val wrong = ArrayList<Pair<P, AlignedUnit>>()
        for (t in truth) {
            val u = unitOf[norm(t.en)] ?: continue
            if (u.status != AlignmentStatus.ALIGNED) continue
            val got = norm(u.targetText)
            if (got.isNotEmpty() && truthSet[norm(t.en)]?.contains(got) != true) wrong += t to u
        }

        println("=== A-wrong 总数 = ${wrong.size} ===")
        for ((t, u) in wrong) {
            println()
            println("--- 段落 p${t.p} / 段内第 ${t.idx} 句 ---")
            println("  EN  = ${t.en}")
            println("  TRUTH= ${t.zh}")
            println("  GOT  = ${u.targetText}")
            println("  sourceIndexInParagraph=${u.sourceIndexInParagraph}")
            println("  targetIndexInParagraph=${u.targetIndexInParagraph}")
            println("  confidence=${u.confidence}")
            println("  reasons=${u.evidence.reasons}")
            println("  evidence: ${u.evidence.summary()}")

            // 该段结构
            val enSents = SentenceSplitter.split(enParas[t.p])
            val zhSents = SentenceSplitter.split(zhParas[t.p])
            println("  段结构: en=${enSents.size} zh=${zhSents.size} ${if (enSents.size == zhSents.size) "(档1: 1:1)" else "(档2: 句数不等)"}")

            // 真值应在的 zh 下标 vs 引擎给的
            val wantIdx = zhSents.indexOfFirst { norm(it) == norm(t.zh) }
            println("  真值中文在切分后下标 = $wantIdx ; 引擎给的下标 = ${u.targetIndexInParagraph}")

            println("  段内逐句对照（引擎输出）:")
            val paraUnits = doc.units.filter { norm(it.sourceParagraph) == norm(enParas[t.p]) }
            for (x in paraUnits.sortedBy { it.sourceIndexInParagraph }) {
                val truthZh = truth.firstOrNull { it.p == t.p && it.idx == x.sourceIndexInParagraph }?.zh
                val ok = truthZh != null && norm(truthZh) == norm(x.targetText)
                val mark = when {
                    x.status == AlignmentStatus.UNALIGNED -> "  UNALIGNED"
                    ok -> "  ✅"
                    else -> "  ❌"
                }
                println("    [${x.sourceIndexInParagraph}] ${x.status}$mark")
                println("        EN : ${x.sourceText.take(60)}")
                println("        GOT: ${x.targetText.take(50)}")
                println("        TGT: ${truthZh?.take(50)}")
                println("        evidence: ${x.evidence.summary()}")
            }

            // ── 该段的 dev 分布（用于定阈值）
            val devs = paraUnits.map { it.evidence.lengthDeviation }.sorted()
            println()
            println("  段内 dev 分布: ${devs.map { String.format("%.3f", it) }}")
            if (devs.isNotEmpty()) {
                val med = devs[devs.size / 2]
                println("  median=${String.format("%.3f", med)}  " +
                    "目标句 dev=${String.format("%.3f", u.evidence.lengthDeviation)}  " +
                    "比值=${String.format("%.2f", if (med > 1e-6) u.evidence.lengthDeviation / med else -1.0)}")
                println("  → 若比值 < SHIFT_RATIO(2.5)，说明该段整体偏离，中位数法无法区分")
            }
        }
    }
}
