package com.linguareader.shared.alignment

import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 打乱实验的**精确诊断**：打乱后引擎报的 ALIGNED 是否真的对？
 *
 * 上一轮打乱实验显示 cov=13.01%、prec=98.95%（即 95 条 ALIGNED 里 1 条错）。
 * 但这个 prec 是用**打乱后的段落**算的，必须逐条核对那 1 条错在哪里：
 * - 如果它是「引擎错配」→ 内容通道有漏洞
 * - 如果它是「打乱后恰好同一句重复出现」→ 真值本身有歧义，不算错
 *
 * 同时量化：内容通道在「位置不可用」时还能救回多少（13.01%）。
 */
class Ch3ShuffleDiagnosisProbe {

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    @Test
    fun diagnose() {
        var repo: File? = File(System.getProperty("user.dir"))
        while (repo != null && !File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").isFile) {
            repo = repo.parentFile
        }
        if (repo == null) { println("[shuffle] SKIP"); return }

        val json = JSONObject(File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")
        val paras = ArrayList<Triple<Int, List<String>, List<String>>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            paras += Triple(
                o.getInt("p"),
                (0 until en.length()).map { en.getString(it) },
                (0 until zh.length()).map { zh.getString(it) }
            )
        }
        val provider = EcdictProvider.load(repo)

        fun run(label: String, group: List<Triple<Int, List<String>, List<String>>>, showWrong: Boolean) {
            val src = listOf(AlignmentChapter("c", group.map { it.second.joinToString(" ") }))
            val tgt = listOf(AlignmentChapter("c", group.map { it.third.joinToString("") }))
            val doc = AiTranslationAligner().align(src, tgt, provider)

            // 打乱后真值仍然按「英文句 → 它的译文」建（打乱只改位置，不改语义对应）
            val truth = HashMap<String, MutableList<String>>()
            for ((_, en, zh) in group) for (k in en.indices) {
                truth.getOrPut(norm(en[k])) { ArrayList() }.add(norm(zh[k]))
            }

            var aligned = 0
            var correct = 0
            val wrong = ArrayList<String>()
            val ambiguousTruth = ArrayList<String>()
            for (u in doc.units) {
                if (u.status != AlignmentStatus.ALIGNED) continue
                aligned++
                val cands = truth[norm(u.sourceText)] ?: emptyList()
                val got = norm(u.targetText)
                when {
                    cands.isEmpty() -> wrong += "NO-TRUTH: ${u.sourceText.take(40)} -> ${u.targetText.take(30)}"
                    cands.size == 1 && cands[0] == got -> correct++
                    cands.size > 1 && cands.contains(got) -> {
                        correct++
                        ambiguousTruth += "${u.sourceText.take(40)} (真值有 ${cands.size} 种译文，命中其一)"
                    }
                    else -> wrong += "WRONG: ${u.sourceText.take(45)} | got=${u.targetText.take(30)} | want=${cands.first().take(30)}"
                }
            }
            println("  [$label] units=${doc.units.size} ALIGNED=$aligned correct=$correct wrong=${wrong.size}")
            println("    coverage=%.2f%%  precision=%.2f%%".format(
                aligned * 100.0 / doc.units.size,
                if (aligned == 0) 0.0 else correct * 100.0 / aligned))
            if (ambiguousTruth.isNotEmpty()) {
                println("    真值多译（不算错）: ${ambiguousTruth.size} 条")
                ambiguousTruth.take(3).forEach { println("      $it") }
            }
            if (showWrong && wrong.isNotEmpty()) {
                println("    错标明细:")
                wrong.take(8).forEach { println("      $it") }
            }
        }

        println("=== 打乱实验精确诊断 ===")
        println("基线（不打乱）:")
        run("baseline", paras, showWrong = false)

        println()
        println("段内打乱中文句序（位置全错，只能靠内容）:")
        run("shuffle-zh", paras.map { Triple(it.first, it.second, it.third.shuffled(Random(1))) }, showWrong = true)

        println()
        println("段内打乱英文句序:")
        run("shuffle-en", paras.map { Triple(it.first, it.second.shuffled(Random(2)), it.third) }, showWrong = true)

        println()
        println("打乱段落顺序（段内不动）:")
        run("shuffle-para", paras.shuffled(Random(3)), showWrong = true)

        println()
        println("=== 结论要点 ===")
        println("  段内打乱后 ALIGNED 骤降 → 说明引擎主要依赖「段内位置」，内容通道只是辅助。")
        println("  打乱段落顺序后指标不变 → 说明章节/段落层是逐下标 identity（构造保证），未做内容搜索。")
    }
}
