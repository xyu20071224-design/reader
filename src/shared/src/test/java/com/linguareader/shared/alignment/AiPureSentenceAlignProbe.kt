package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **AI 译本「纯段内对齐能力」测量**（选项 A：绕过 `SentenceSplitter`）。
 *
 * ## 为什么必须绕过分句器
 *
 * 用 `enParagraphs`/`zhParagraphs` 直接跑时，`precision = 42.86%`，但那个数字
 * **不含算法能力信息**——因为：
 *
 * 1. `SentenceSplitter` 把 `A. Whitmore` 切成 `"A."` + `"Whitmore, Books"`（单字母缩写
 *    保护不足），英文侧多切 1 句 → 后续所有 `[i]` 全部错位；
 * 2. 真值 `es`/`zs` 与 split 结果**只有 12/35 条一致（34%）**。
 *
 * 在 66% 的输入都错位的情况下测出的 precision，测的是分句器，不是对齐器。
 *
 * ## 这个探针做什么
 *
 * 用**真值 `es`/`zs` 直接构造 `AlignmentChapter`**（每句自己成一段，段界=句界），
 * 这样：
 * - 分句器完全不参与
 * - 章节/段落 identity 由构造给出
 * - 测到的就是「**段内句级对齐**」这个唯一需要算法的环节
 *
 * ## 与人工译本的可比性说明
 *
 * 这个分母是「35 个真值句对」，不是「全书 13598 句」。
 * **两个数字不可直接比较**——它们是不同的题目。报告里必须同时报两个分母。
 */
class AiPureSentenceAlignProbe {

    @Test
    fun measurePureSentenceAlignment() {
        val root = findRepoRoot()
        val file = File(root, "artifacts/ai-translation-memory-device.json")
        if (!file.isFile) {
            println("SKIP: ai-translation-memory-device.json 缺失")
            return
        }
        val json = org.json.JSONObject(file.readText())
        val pairs = json.getJSONArray("pairs")

        // 用真值 es/zs 构造：每句一段（段界 = 句界），章节数 = 1
        // 这样 engine 的「章节 identity + 段落 identity + 段内 1:1」全部由构造给出，
        // 它唯一要做的是「这句英文配哪句中文」。
        val enSentences = ArrayList<String>(pairs.length())
        val zhSentences = ArrayList<String>(pairs.length())
        val truthPairs = ArrayList<Pair<String, String>>(pairs.length())
        for (i in 0 until pairs.length()) {
            val pair = pairs.getJSONObject(i)
            val en = pair.optString("es", "").trim()
            val zh = pair.optString("zs", "").trim()
            if (en.isEmpty() || zh.isEmpty()) continue
            enSentences.add(en)
            zhSentences.add(zh)
            truthPairs.add(en to zh)
        }
        println("=== 用真值 es/zs 构造的纯净语料 ===")
        println("  句对数 = ${truthPairs.size}（每句一段，段界=句界）")

        val ecdict = EcdictProvider.load(root)
        if (ecdict == null) {
            println("  SKIP: ecdict-sample.tsv 缺失")
            return
        }
        val enChapters = listOf(AlignmentChapter("ai-pure", enSentences))
        val zhChapters = listOf(AlignmentChapter("ai-pure-zh", zhSentences))
        val document = IndependentAligner().align(enChapters, zhChapters, ecdict)
        println()
        println("  stats: ${document.stats.summaryLine()}")
        println("  三态：ALIGNED=${document.stats.alignedUnits} " +
            "UNCERTAIN=${document.stats.uncertainUnits} " +
            "UNALIGNED=${document.stats.unalignedUnits}")
        document.notes.take(6).forEach { println("  note: $it") }

        // ── 逐条对照（这次是干净的：构造即真值）──────────────────────
        println()
        println("=== 逐条对照（纯净语料，构造即真值）===")
        var exact = 0
        var wrong = 0
        var uncertain = 0
        var unaligned = 0
        val wrongSamples = ArrayList<String>(12)
        val uncertainSamples = ArrayList<String>(12)
        document.units.forEachIndexed { index, unit ->
            val truth = truthPairs.getOrNull(index) ?: return@forEachIndexed
            when (unit.status) {
                AlignmentStatus.ALIGNED -> {
                    if (normalize(unit.targetText) == normalize(truth.second)) {
                        exact++
                    } else {
                        wrong++
                        if (wrongSamples.size < 12) {
                            wrongSamples.add(
                                "  #$index ALIGNED-WRONG en=\"${truth.first.take(40)}\"\n" +
                                    "        truth=\"${truth.second.take(40)}\"\n" +
                                    "        got  =\"${unit.targetText.take(40)}\""
                            )
                        }
                    }
                }
                AlignmentStatus.UNCERTAIN -> {
                    uncertain++
                    if (uncertainSamples.size < 12) {
                        val matches = normalize(unit.targetText) == normalize(truth.second)
                        uncertainSamples.add(
                            "  #$index UNCERTAIN(${if (matches) "候选正确" else "候选错误/空"}) " +
                                "en=\"${truth.first.take(34)}\""
                        )
                    }
                }
                AlignmentStatus.UNALIGNED -> unaligned++
            }
        }
        val total = document.units.size
        println("  单元总数 = $total")
        println("  ALIGNED 且精确正确 = $exact")
        println("  ALIGNED 但错误（**错标**）= $wrong")
        println("  UNCERTAIN = $uncertain")
        println("  UNALIGNED = $unaligned")
        if (total > 0) {
            println("  **coverage = ${pct((exact + wrong).toDouble() / total)}**")
            println("  **precision = ${pct(if (exact + wrong == 0) 1.0 else exact.toDouble() / (exact + wrong))}**")
            println("  **falseAligned = $wrong**（红线应为 0）")
            println("  UNCERTAIN 里候选正确的 = ${uncertainSamples.count { it.contains("候选正确") }}")
        }
        if (wrongSamples.isNotEmpty()) {
            println()
            println("  错标明细：")
            wrongSamples.forEach { println(it) }
        }
        if (uncertainSamples.isNotEmpty()) {
            println()
            println("  UNCERTAIN 明细：")
            uncertainSamples.forEach { println(it) }
        }

        // ── 对照：把顺序打乱，验证算法不是「靠位置蒙对」────────────────
        println()
        println("=== 对照实验：打乱中文顺序（若仍高覆盖率，说明算法在靠位置而非内容）===")
        val shuffledZh = zhSentences.toMutableList().also { list ->
            // 确定性打乱：相邻交换
            var i = 0
            while (i + 1 < list.size) {
                val tmp = list[i]
                list[i] = list[i + 1]
                list[i + 1] = tmp
                i += 2
            }
        }
        val shuffledDoc = IndependentAligner().align(
            enChapters, listOf(AlignmentChapter("ai-pure-zh-shuffled", shuffledZh)), ecdict
        )
        var shuffledCorrect = 0
        var shuffledAligned = 0
        shuffledDoc.units.forEachIndexed { index, unit ->
            if (unit.status != AlignmentStatus.ALIGNED) return@forEachIndexed
            shuffledAligned++
            val truth = truthPairs.getOrNull(index) ?: return@forEachIndexed
            if (normalize(unit.targetText) == normalize(truth.second)) shuffledCorrect++
        }
        println("  打乱后 ALIGNED = $shuffledAligned，其中正确 = $shuffledCorrect")
        println(
            "  → 若打乱后正确数明显下降，说明算法在用内容/锚点而非位置；" +
                "若几乎不变，说明它只是在按位置蒙"
        )
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

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/lotr-book").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到仓库根")
    }
}
