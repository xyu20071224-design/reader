package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **AI 译本验收探针**（只测量，不改引擎）。
 *
 * ## 为什么 AI 译本可能救活这个算法
 *
 * 人工译本（朱学恒）把设计假设逐条打破：数字转汉字、专名不保留拉丁、N:1 主导、
 * 半文言、全繁。而 AI 翻译链路的 prompt **明确要求**「直译为主、语序贴近原文、
 * 不合并拆分句子、数字原样保留」——它天然满足本算法的假设。
 *
 * ## 这 35 条有天然真值
 *
 * `pairs` 数组里每条 `es`/`zs` 就是**逐句配对真值**，所以 precision 可以直接算，
 * 不需要人工判定。这是本次转向最大的优势。
 *
 * ## 但必须先确认两个前提
 *
 * 1. 分句是否可靠（样本里已见「中文缺右引号」「译文错位到上一句」两处）
 * 2. 规模是否足够（只有 35 句）
 */
class AiTranslationAcceptanceProbe {

    @Test
    fun measureOnAiTranslation() {
        val root = findRepoRoot()
        val file = File(root, "artifacts/ai-translation-memory-device.json")
        if (!file.isFile) {
            println("SKIP: ai-translation-memory-device.json 缺失")
            return
        }
        val json = org.json.JSONObject(file.readText())
        val enParagraphs = json.getJSONArray("enParagraphs").toStringList()
        val zhParagraphs = json.getJSONArray("zhParagraphs").toStringList()
        val pairs = json.getJSONArray("pairs")

        println("=== AI 译本语料 ===")
        println("  en 段=${enParagraphs.size}  zh 段=${zhParagraphs.size}  真值句对=${pairs.length()}")

        // ── 第一步：先验证「分句是否可靠」——这是前提，不是结论 ──────────
        println()
        println("=== 前提检查 1：分句可靠性 ===")
        var enSplitTotal = 0
        var zhSplitTotal = 0
        val enPerParagraph = ArrayList<Int>()
        val zhPerParagraph = ArrayList<Int>()
        for (p in enParagraphs) {
            val n = com.linguareader.shared.tts.SentenceSplitter.split(p).size
            enPerParagraph.add(n)
            enSplitTotal += n
        }
        for (p in zhParagraphs) {
            val n = com.linguareader.shared.tts.SentenceSplitter.split(p).size
            zhPerParagraph.add(n)
            zhSplitTotal += n
        }
        println("  SentenceSplitter：英文 $enSplitTotal 句 / 中文 $zhSplitTotal 句")
        println("  真值对（pairs）= ${pairs.length()} 条")
        println("  → 若中英分句数不等，或与真值对数不等，说明「1:1 句对」前提已被破坏")
        println("  en 每段句数：$enPerParagraph")
        println("  zh 每段句数：$zhPerParagraph")

        // 逐段对比
        var mismatchedParagraphs = 0
        for (i in 0 until minOf(enPerParagraph.size, zhPerParagraph.size)) {
            if (enPerParagraph[i] != zhPerParagraph[i]) mismatchedParagraphs++
        }
        println("  段级句数不匹配的段落 = $mismatchedParagraphs/${minOf(enPerParagraph.size, zhPerParagraph.size)}")

        // ── 第二步：引号配对检查（Lead 点名的两处缺陷）──────────────
        println()
        println("=== 前提检查 2：中文引号配对 ===")
        var unbalanced = 0
        val unbalancedSamples = ArrayList<String>(8)
        for ((index, p) in zhParagraphs.withIndex()) {
            var left = 0
            var right = 0
            for (c in p) {
                when (c) {
                    '“' -> left++
                    '”' -> right++
                    '「' -> left++
                    '」' -> right++
                    '『' -> left++
                    '』' -> right++
                }
            }
            if (left != right) {
                unbalanced++
                if (unbalancedSamples.size < 8) {
                    unbalancedSamples.add("zh 段 $index：左=$left 右=$right  \"${p.take(50)}\"")
                }
            }
        }
        println("  中文段落引号不配对的 = $unbalanced/${zhParagraphs.size}")
        unbalancedSamples.forEach { println("    $it") }
        if (unbalanced > 0) {
            println("  ⚠️ 引号不配对会让 SentenceSplitter 的引号保护规则失效 → 分句错乱")
        }

        // 英文侧同样检查（真值 es 与 split 结果的差异）
        println()
        println("=== 前提检查 3：真值句 vs split 结果逐条比对 ===")
        var truthMatchesSplit = 0
        var truthMismatch = 0
        val mismatchSamples = ArrayList<String>(10)
        val splitEn = enParagraphs.flatMap { com.linguareader.shared.tts.SentenceSplitter.split(it) }
        for (i in 0 until pairs.length()) {
            val pair = pairs.getJSONObject(i)
            val truthEn = pair.optString("es", "")
            // 位置对应：第 i 条真值的英文应等于 split 结果第 i 条
            val splitSentence = splitEn.getOrNull(i) ?: continue
            if (normalize(splitSentence) == normalize(truthEn)) {
                truthMatchesSplit++
            } else {
                truthMismatch++
                if (mismatchSamples.size < 10) {
                    mismatchSamples.add(
                        "truth[$i]=\"${truthEn.take(40)}\"\n         split[$i]=\"${splitSentence.take(40)}\""
                    )
                }
            }
        }
        println("  真值 vs split 逐条相同 = $truthMatchesSplit，不同 = $truthMismatch")
        mismatchSamples.forEach { println("    $it") }

        // ── 第三步：跑引擎，用真值算 precision ─────────────────────
        println()
        println("=== 引擎实测（真 ECDICT）===")
        val ecdict = EcdictProvider.load(root)
        if (ecdict == null) {
            println("  SKIP: ecdict-sample.tsv 缺失")
            return
        }
        val enChapters = listOf(AlignmentChapter("AI sample (en)", enParagraphs))
        val zhChapters = listOf(AlignmentChapter("AI sample (zh)", zhParagraphs))
        val document = IndependentAligner().align(enChapters, zhChapters, ecdict)
        println("  stats: ${document.stats.summaryLine()}")
        println("  三态：ALIGNED=${document.stats.alignedUnits} " +
            "UNCERTAIN=${document.stats.uncertainUnits} " +
            "UNALIGNED=${document.stats.unalignedUnits}")
        document.notes.forEach { println("  note: $it") }

        // ── 第四步：逐条对照「算法给的」vs「真值」───────────────────
        println()
        println("=== 逐条对照（真值 vs 算法输出）===")
        println("  # | 状态 | 真值 zs | 算法 targetText | 判定")
        var exact = 0
        var wrong = 0
        var unc = 0
        var unaligned = 0
        val wrongSamples = ArrayList<String>(10)
        document.units.forEachIndexed { index, unit ->
            val truth = if (index < pairs.length()) pairs.getJSONObject(index) else null
            val truthZh = truth?.optString("zs", "") ?: ""
            val verdict = when (unit.status) {
                AlignmentStatus.ALIGNED -> {
                    if (normalize(unit.targetText) == normalize(truthZh)) {
                        exact++; "OK"
                    } else {
                        wrong++
                        if (wrongSamples.size < 10) {
                            wrongSamples.add(
                                "  $index | ALIGNED | truth=\"${truthZh.take(34)}\" " +
                                    "| got=\"${unit.targetText.take(34)}\""
                            )
                        }
                        "WRONG"
                    }
                }
                AlignmentStatus.UNCERTAIN -> { unc++; "UNCERTAIN" }
                AlignmentStatus.UNALIGNED -> { unaligned++; "UNALIGNED(${unit.skipReason})" }
            }
            if (index < 12) {
                println("  $index | ${unit.status} | \"${truthZh.take(26)}\" | " +
                    "\"${unit.targetText.take(26)}\" | $verdict")
            }
        }
        val total = document.units.size
        println()
        println("  单元总数 = $total")
        println("  ALIGNED 且与真值**逐字相同** = $exact")
        println("  ALIGNED 但与真值不同（**错标**）= $wrong")
        println("  UNCERTAIN = $unc")
        println("  UNALIGNED = $unaligned")
        if (total > 0) {
            println("  coverage = ${pct((exact + wrong).toDouble() / total)}")
            println("  precision = ${pct(if (exact + wrong == 0) 1.0 else exact.toDouble() / (exact + wrong))}")
        }
        if (wrongSamples.isNotEmpty()) {
            println()
            println("  错标明细：")
            wrongSamples.forEach { println(it) }
        }
    }

    private fun normalize(text: String): String {
        val builder = StringBuilder(text.length)
        for (c in text) {
            when {
                c in 'A'..'Z' -> builder.append(c + 32)
                c in 'a'..'z' || c in '0'..'9' -> builder.append(c)
                c in '\u4E00'..'\u9FFF' -> builder.append(c)
            }
        }
        return builder.toString()
    }

    private fun pct(v: Double): String = "${Math.round(v * 10000.0) / 100.0}%"

    private fun org.json.JSONArray.toStringList(): List<String> =
        (0 until length()).map { optString(it, "") }

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/lotr-book").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到仓库根")
    }
}
