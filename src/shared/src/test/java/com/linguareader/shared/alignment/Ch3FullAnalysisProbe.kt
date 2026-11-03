package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 对「真实 AI 译本第 3 章」做全维度分析（只打印，不断言）。
 *
 * 分十节：语料特征 / 分句一致性 / 段落分层 / 引擎分层结果 / 位置基线 /
 * 打乱实验 / 残差分类 / 锚点统计 / 重复文本歧义 / 确定性与耗时。
 *
 * 核心问题：**引擎的 ALIGNED 究竟来自「位置可直接决定」的段落，
 * 还是「必须靠内容搜索」的段落？** 前者不能证明内容通道有价值。
 */
class Ch3FullAnalysisProbe {

    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "artifacts/ai-ch3/ch3_merged_partial.json").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    private fun pct(a: Int, b: Int) = if (b == 0) 0.0 else a * 100.0 / b

    private fun q(xs: List<Int>, p: Double): Int {
        if (xs.isEmpty()) return 0
        val s = xs.sorted()
        val idx = ((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)
        return s[idx]
    }

    private class Para(
        val p: Int,
        val en: List<String>,
        val zh: List<String>
    )

    @Test
    fun analyze() {
        val repo = repoRoot() ?: run { println("[analysis] SKIP"); return }
        val json = JSONObject(File(repo, "artifacts/ai-ch3/ch3_merged_partial.json").readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")
        val paras = ArrayList<Para>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            paras += Para(
                o.getInt("p"),
                (0 until en.length()).map { en.getString(it) },
                (0 until zh.length()).map { zh.getString(it) }
            )
        }

        val totalEn = paras.sumOf { it.en.size }
        val totalZh = paras.sumOf { it.zh.size }
        val enChars = paras.sumOf { p -> p.en.sumOf { it.length } }
        val zhChars = paras.sumOf { p -> p.zh.sumOf { it.length } }

        // ───────── 1. 语料特征 ─────────
        println("=== 1. 语料特征 ===")
        println("  paragraphs=${
            paras.size
        }  enSentences=$totalEn  zhSentences=$totalZh  enChars=$enChars  zhChars=$zhChars")
        println("  zh/en char ratio = %.3f".format(zhChars.toDouble() / enChars))
        val enLens = paras.flatMap { p -> p.en.map { it.length } }
        val zhLens = paras.flatMap { p -> p.zh.map { it.length } }
        println("  en sentence chars: p10=${q(enLens, 0.1)} p50=${q(enLens, 0.5)} p90=${q(enLens, 0.9)} max=${enLens.maxOrNull()}")
        println("  zh sentence chars: p10=${q(zhLens, 0.1)} p50=${q(zhLens, 0.5)} p90=${q(zhLens, 0.9)} max=${zhLens.maxOrNull()}")
        val paraSizes = paras.map { it.en.size }
        println("  per-paragraph en sentences: p50=${q(paraSizes, 0.5)} p90=${q(paraSizes, 0.9)} max=${paraSizes.maxOrNull()}")
        val sizeDist = paraSizes.groupingBy { it }.eachCount().toSortedMap()
        println("  句数分布: $sizeDist")

        // ───────── 2. 分句一致性（引擎切分 vs 真值）─────────
        println()
        println("=== 2. 分句一致性（引擎对拼接后的段落重新切分 vs 真值句数）===")
        var enSplitSame = 0
        var enSplitDiff = 0
        var zhSplitSame = 0
        var zhSplitDiff = 0
        val enDiffExamples = ArrayList<String>()
        val zhDiffExamples = ArrayList<String>()
        for (p in paras) {
            val enText = p.en.joinToString(" ")
            val zhText = p.zh.joinToString("")
            val enSp = SentenceSplitter.split(enText)
            val zhSp = SentenceSplitter.split(zhText)
            if (enSp.size == p.en.size) enSplitSame++ else {
                enSplitDiff++
                if (enDiffExamples.size < 4) {
                    enDiffExamples += "p${p.p}: truth=${p.en.size} split=${enSp.size}"
                }
            }
            if (zhSp.size == p.zh.size) zhSplitSame++ else {
                zhSplitDiff++
                if (zhDiffExamples.size < 6) {
                    zhDiffExamples += "p${p.p}: truth=${p.zh.size} split=${zhSp.size}"
                }
            }
        }
        println("  en: same=$enSplitSame diff=$enSplitDiff  (%.2f%% 一致)".format(pct(enSplitSame, paras.size)))
        enDiffExamples.forEach { println("     $it") }
        println("  zh: same=$zhSplitSame diff=$zhSplitDiff  (%.2f%% 一致)".format(pct(zhSplitSame, paras.size)))
        zhDiffExamples.forEach { println("     $it") }

        // ───────── 3. 段落分层：句数相等 vs 不等 ─────────
        // 用**真值**句数判断（这是"题目难度"的定义）
        val easyParas = paras.filter { it.en.size == it.zh.size }
        val hardParas = paras.filter { it.en.size != it.zh.size }
        val easySent = easyParas.sumOf { it.en.size }
        val hardSent = hardParas.sumOf { it.en.size }
        println()
        println("=== 3. 段落分层（按真值句数是否相等）===")
        println("  等句数段: ${easyParas.size} 段 / $easySent 句 (${"%.2f".format(pct(easySent, totalEn))}%)  ← 位置可直接决定")
        println("  不等句数段: ${hardParas.size} 段 / $hardSent 句 (${"%.2f".format(pct(hardSent, totalEn))}%)  ← 必须靠内容搜索")

        // ───────── 4. 引擎结果分层 ─────────
        val provider = EcdictProvider.load(repo)
        println()
        println("  ECDICT = " + (provider?.entryCount?.toString() ?: "NOT LOADED"))

        fun runEngine(parasIn: List<Para>, label: String): Map<String, Any> {
            val group = if (label.startsWith("shuffle")) {
                parasIn
            } else parasIn
            val src = listOf(AlignmentChapter("c", group.map { it.en.joinToString(" ") }))
            val tgt = listOf(AlignmentChapter("c", group.map { it.zh.joinToString("") }))
            val doc = AiTranslationAligner().align(src, tgt, provider)
            val truthMap = HashMap<String, String>()
            for (p in group) for (k in p.en.indices) truthMap[norm(p.en[k])] = norm(p.zh[k])
            var aligned = 0
            var correct = 0
            var unc = 0
            var uncCorrect = 0
            var un = 0
            for (u in doc.units) {
                val want = truthMap[norm(u.sourceText)]
                val got = norm(u.targetText)
                when (u.status) {
                    AlignmentStatus.ALIGNED -> {
                        aligned++
                        if (got.isNotEmpty() && got == want) correct++
                    }
                    AlignmentStatus.UNCERTAIN -> {
                        unc++
                        if (got.isNotEmpty() && got == want) uncCorrect++
                    }
                    AlignmentStatus.UNALIGNED -> un++
                }
            }
            val n = doc.units.size
            return mapOf(
                "n" to n, "aligned" to aligned, "correct" to correct,
                "unc" to unc, "uncCorrect" to uncCorrect, "un" to un,
                "coverage" to pct(aligned, n), "precision" to pct(correct, aligned.coerceAtLeast(1)),
                "usable" to pct(correct + uncCorrect, n)
            )
        }

        fun printRow(label: String, m: Map<String, Any>) {
            println(
                "  %-26s n=%-4s cov=%6.2f%%  prec=%6.2f%%  usable=%6.2f%%  (A=%s/%s correct, U=%s/%s, N=%s)".format(
                    label, m["n"], m["coverage"], m["precision"], m["usable"],
                    m["correct"], m["aligned"], m["uncCorrect"], m["unc"], m["un"]
                )
            )
        }

        println()
        println("=== 4. 引擎结果：全局 / 易段 / 难段 ===")
        val all = runEngine(paras, "all")
        printRow("全部", all)
        val easyM = runEngine(easyParas, "easy")
        printRow("仅等句数段（易）", easyM)
        val hardM = runEngine(hardParas, "hard")
        printRow("仅不等句数段（难）", hardM)

        // ───────── 5. 位置基线（分层）─────────
        println()
        println("=== 5. 纯位置基线（不走引擎，段内第 k 配第 k）===")
        fun posBaseline(group: List<Para>, label: String) {
            var usableSent = 0
            var aligned = 0
            var correct = 0
            for (p in group) {
                if (p.en.size != p.zh.size) continue
                for (k in p.en.indices) {
                    usableSent++
                    aligned++
                    if (norm(p.en[k]) == norm(p.en[k]) && norm(p.zh[k]) == norm(p.zh[k])) correct++
                }
            }
            println(
                "  %-26s 可位置映射=%d 句  cov=%6.2f%%  prec=100.00%% (定义上永远对)".format(
                    label, usableSent, pct(usableSent, totalEn)
                )
            )
        }
        posBaseline(paras, "全部")

        // ───────── 6. 打乱实验 ─────────
        println()
        println("=== 6. 打乱实验（检验引擎是否真的靠内容）===")
        val rnd = Random(20261031)
        // 6a. 段内打乱中文句序（句数不变 → 位置全错）
        val shuffledInPara = paras.map { p ->
            Para(p.p, p.en, p.zh.shuffled(rnd))
        }
        printRow("段内打乱中文句序", runEngine(shuffledInPara, "shuffleIn"))
        // 6b. 段内打乱英文句序
        val shuffledEn = paras.map { p -> Para(p.p, p.en.shuffled(rnd), p.zh) }
        printRow("段内打乱英文句序", runEngine(shuffledEn, "shuffleEn"))
        // 6c. 打乱段落顺序（段数不变，段内不动）
        val shuffledPara = paras.shuffled(rnd)
        printRow("打乱段落顺序", runEngine(shuffledPara, "shufflePara"))

        // ───────── 7. 残差分类 ─────────
        println()
        println("=== 7. 残差分类（全部段落，ai-pair-v1）===")
        val doc = AiTranslationAligner().align(
            listOf(AlignmentChapter("c", paras.map { it.en.joinToString(" ") })),
            listOf(AlignmentChapter("c", paras.map { it.zh.joinToString("") })),
            provider
        )
        val uncReasons = HashMap<String, Int>()
        val unReasons = HashMap<String, Int>()
        for (u in doc.units) {
            when (u.status) {
                AlignmentStatus.UNCERTAIN -> {
                    val r = u.evidence.reasons.firstOrNull() ?: "(none)"
                    val key = r.substringBefore("(").trim().take(46)
                    uncReasons[key] = (uncReasons[key] ?: 0) + 1
                }
                AlignmentStatus.UNALIGNED -> {
                    val key = u.skipReason?.name ?: "(none)"
                    unReasons[key] = (unReasons[key] ?: 0) + 1
                }
                else -> Unit
            }
        }
        println("  UNCERTAIN=${doc.units.count { it.status == AlignmentStatus.UNCERTAIN }} 按首因:")
        uncReasons.entries.sortedByDescending { it.value }.take(8).forEach {
            println("    %6d  %s".format(it.value, it.key))
        }
        println("  UNALIGNED=${doc.units.count { it.status == AlignmentStatus.UNALIGNED }} 按 skipReason:")
        unReasons.entries.sortedByDescending { it.value }.forEach {
            println("    %6d  %s".format(it.value, it.key))
        }

        // ───────── 8. 锚点统计 ─────────
        println()
        println("=== 8. 锚点可用性（本章）===")
        val digitSent = paras.flatMap { it.en }.count { s -> s.any { it.isDigit() } }
        val zhDigitSent = paras.flatMap { it.zh }.count { s -> s.any { it.isDigit() } }
        println("  含阿拉伯数字的英文句 = $digitSent / $totalEn (${"%.2f".format(pct(digitSent, totalEn))}%)")
        println("  含阿拉伯数字的中文句 = $zhDigitSent / $totalZh (${"%.2f".format(pct(zhDigitSent, totalZh))}%)")
        val names = HashSet<String>()
        for (p in paras) for (s in p.en) names += Transliteration.extractNames(s)
        println("  抽出的专名候选种类 = ${names.size}")
        val topNames = names.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
        println("  高频候选: " + topNames.joinToString { "${it.key}(${it.value})" })
        var nameHit = 0
        var nameChecked = 0
        for (p in paras) for (k in p.en.indices) {
            val ns = Transliteration.extractNames(p.en[k])
            if (ns.isEmpty()) continue
            nameChecked++
            if (ns.any { Transliteration.scoreInSentence(it, p.zh[k]) >= 0.9 }) nameHit++
        }
        println("  含专名的句对 = $nameChecked；其中精确锚点命中 = $nameHit (${"%.2f".format(pct(nameHit, nameChecked))}%)")

        // ───────── 9. 重复文本歧义 ─────────
        println()
        println("=== 9. 文本重复（真值歧义）===")
        val enDup = paras.flatMap { it.en }.groupingBy { norm(it) }.eachCount().filter { it.value > 1 }
        val zhDup = paras.flatMap { it.zh }.groupingBy { norm(it) }.eachCount().filter { it.value > 1 }
        println("  英文重复句种类=${enDup.size}  涉及句数=${enDup.values.sum()}")
        enDup.entries.sortedByDescending { it.value }.take(5).forEach {
            println("    ×${it.value}  ${it.key.take(50)}")
        }
        println("  中文重复句种类=${zhDup.size}  涉及句数=${zhDup.values.sum()}")
        zhDup.entries.sortedByDescending { it.value }.take(5).forEach {
            println("    ×${it.value}  ${it.key.take(40)}")
        }

        // ───────── 10. 确定性与耗时 ─────────
        println()
        println("=== 10. 确定性与耗时 ===")
        val sigs = ArrayList<String>()
        repeat(3) {
            val d2 = AiTranslationAligner().align(
                listOf(AlignmentChapter("c", paras.map { it.en.joinToString(" ") })),
                listOf(AlignmentChapter("c", paras.map { it.zh.joinToString("") })),
                provider
            )
            sigs += d2.units.joinToString("|") { "${it.status}:${it.sourceIndexInParagraph}:${it.targetIndexInParagraph}" }
        }
        println("  三次运行签名一致 = ${sigs.distinct().size == 1}")
        val t0 = System.currentTimeMillis()
        repeat(5) {
            AiTranslationAligner().align(
                listOf(AlignmentChapter("c", paras.map { it.en.joinToString(" ") })),
                listOf(AlignmentChapter("c", paras.map { it.zh.joinToString("") })),
                provider
            )
        }
        println("  5 次平均耗时 = ${(System.currentTimeMillis() - t0) / 5} ms（含 ECDICT 查询）")
        println("  stats: ${doc.stats.summaryLine()}")
    }
}
