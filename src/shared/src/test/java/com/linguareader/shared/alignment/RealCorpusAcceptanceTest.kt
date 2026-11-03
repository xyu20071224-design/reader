package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **真实语料验收台**：在魔戒英中语料上跑本引擎，报覆盖率与金标准召回。
 *
 * ## 为什么必须有这个文件
 *
 * 合成语料只能证明「不产生错标」（安全性），证明不了「能用」。真实语料（13611 英文句 /
 * 9336 中文句 / 48 英文章 vs 38 中文章 / 全繁体）才是可用性证据。
 *
 * ## 指标
 *
 * | 指标 | 定义 |
 * | --- | --- |
 * | `coverage` | ALIGNED 覆盖的英文有效句 / 英文有效句总数（契约口径，用 `stats`） |
 * | `goldRecall` | 金标准 100 条里 `approved.level != MISS` 的句子中，被报 ALIGNED 的比例 |
 * | `falseAligned` | 金标准里 `verdict == bad` 却被报 ALIGNED 的条数（**红线**） |
 *
 * 金标准口径说明：`golden-samples.json` 的 `approved.level` 为 `MISS` = 人工确认无对照；
 * `SENTENCE` = 有句级对照；`PARAGRAPH` = 只有段级对照（可接受 ALIGNED 但不算精确召回）。
 */
class RealCorpusAcceptanceTest {

    @Test
    fun measureOnLordsOfTheRings() {
        val root = findRepoRoot()
        val enChapters = loadEnglishChapters(root)
        val zhChapters = loadChineseChapters(root)
        if (enChapters.isEmpty() || zhChapters.isEmpty()) {
            println("SKIP: 语料缺失（需要 artifacts/lotr-book 与 artifacts/lotr-zh）")
            return
        }
        val ecdict = EcdictProvider.load(root)
        println("=== 真实语料 ===")
        println("  en: ${enChapters.size} 章 / ${enChapters.sumOf { it.paragraphs.size }} 段")
        println("  zh: ${zhChapters.size} 章 / ${zhChapters.sumOf { it.paragraphs.size }} 段")
        println(
            "  词典：" + if (ecdict == null) "无（ecdict-sample.tsv 缺失）"
            else "真 ECDICT（${ecdict.entryCount} 词条 / ${ecdict.lemmaCount} 词形）"
        )

        val started = System.nanoTime()
        val document = IndependentAligner().align(enChapters, zhChapters, ecdict)
        val elapsed = (System.nanoTime() - started) / 1_000_000

        println()
        println("=== 结果（${if (ecdict == null) "meaning = null" else "真 ECDICT"}）===")
        println("  stats: ${document.stats.summaryLine()}")
        println("  elapsed = ${elapsed}ms")
        println("  三态：ALIGNED=${document.stats.alignedUnits} " +
            "UNCERTAIN=${document.stats.uncertainUnits} " +
            "UNALIGNED=${document.stats.unalignedUnits}")
        val skipReasons = document.units
            .filter { it.skipReason != null }
            .groupingBy { it.skipReason!! }
            .eachCount()
        println("  skipReason: $skipReasons")
        println()
        println("  --- notes（前 12 条）---")
        document.notes.take(12).forEach { println("    $it") }

        println()
        println("=== 金标准重放 ===")
        replayGolden(root, document)

        // 崩不崩是底线：不抛异常即可。覆盖率不设断言（真实书上尚无已知正确值）。
        kotlin.test.assertTrue(
            document.units.isNotEmpty(),
            "真实语料上必须产出单元（不许静默丢句）"
        )
    }

    // ──────────────────────────────────────────────────────────────

    private fun replayGolden(root: File, document: AlignmentDocument) {
        val goldenFile = File(root, "artifacts/alignment-eval/golden-samples.json")
        if (!goldenFile.isFile) {
            println("  SKIP: golden-samples.json 缺失")
            return
        }
        val json = org.json.JSONObject(goldenFile.readText())
        val samples = json.getJSONArray("samples")

        // 用英文原句文本索引：单元 sourceText 与样本 en 做**归一化包含**匹配
        val bySource = HashMap<String, AlignedUnit>()
        document.units.forEach { unit ->
            bySource.putIfAbsent(normalize(unit.sourceText), unit)
        }

        var positive = 0
        var positiveAligned = 0
        var positiveSentence = 0
        var positiveSentenceAligned = 0
        var badTotal = 0
        var badAligned = 0
        var matched = 0
        val misses = ArrayList<String>()
        // **产品形态决策指标**（Lead 要求）：ALIGNED + 带候选的 UNCERTAIN 的可用覆盖率。
        // 若这个数足够高且精确率可接受，产品可以做成「默认展示 + 标注把握不足」。
        var usableAligned = 0
        var usableUncertain = 0
        var usableBad = 0
        var usablePositive = 0
        var uncertainWithText = 0

        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            val english = sample.optString("en", "")
            val verdict = sample.optString("verdict", "")
            val level = sample.optJSONObject("approved")?.optString("level", "") ?: "MISS"
            val unit = findUnit(bySource, english)
            if (unit != null) matched++

            val isPositive = level != "MISS"
            val isUsable = unit != null &&
                (unit.status == AlignmentStatus.ALIGNED ||
                    (unit.status == AlignmentStatus.UNCERTAIN && unit.targetText.isNotEmpty()))
            if (isUsable) {
                usableAligned += if (unit!!.status == AlignmentStatus.ALIGNED) 1 else 0
                usableUncertain += if (unit!!.status == AlignmentStatus.UNCERTAIN) 1 else 0
                if (verdict == "bad") usableBad++
            }
            if (isPositive) {
                positive++
                usablePositive += if (isUsable) 1 else 0
                if (unit?.status == AlignmentStatus.ALIGNED) {
                    positiveAligned++
                    if (level == "SENTENCE") positiveSentenceAligned++
                } else if (misses.size < 8) {
                    misses += "[$level] en=\"${english.take(46)}\" " +
                        "→ ${unit?.status ?: "NOT_FOUND"} ${unit?.skipReason ?: ""}"
                }
                if (level == "SENTENCE") positiveSentence++
            }
            if (verdict == "bad") {
                badTotal++
                if (unit?.status == AlignmentStatus.ALIGNED) {
                    badAligned++
                    println(
                        "  !! FALSE-ALIGN: en=\"${english.take(46)}\" " +
                            "→ zh=\"${unit.targetText.take(32)}\""
                    )
                }
            }
        }

        // 全书范围：UNCERTAIN 里有多少带候选译文（产品可用性基数）
        document.units.forEach { unit ->
            if (unit.status == AlignmentStatus.UNCERTAIN && unit.targetText.isNotEmpty()) {
                uncertainWithText++
            }
        }

        println("  样本匹配：$matched/${samples.length()}")
        println("  人工正样本（level != MISS）：$positive，其中报 ALIGNED = $positiveAligned " +
            "(${pct(positiveAligned.toDouble() / positive.coerceAtLeast(1))})")
        println("  其中句级对照（level == SENTENCE）：$positiveSentence，" +
            "报 ALIGNED = $positiveSentenceAligned " +
            "(${pct(positiveSentenceAligned.toDouble() / positiveSentence.coerceAtLeast(1))})")
        println("  人工判定 bad 的样本：$badTotal，其中报 ALIGNED = $badAligned（红线应为 0）")
        println()
        println("  === 产品形态决策指标（ALIGNED + 带候选 UNCERTAIN）===")
        println("  全书 UNCERTAIN 且带候选译文 = $uncertainWithText 条")
        println(
            "  金标准人工正样本中被「ALIGNED 或 带候选 UNCERTAIN」覆盖 = " +
                "$usablePositive/$positive (${pct(usablePositive.toDouble() / positive.coerceAtLeast(1))})"
        )
        println("    其中 ALIGNED = $usableAligned，带候选 UNCERTAIN = $usableUncertain")
        println(
            "  这些可展示条目里人工判定 bad 的 = $usableBad " +
                "（可用精确率 = ${pct(1.0 - usableBad.toDouble() / (usableAligned + usableUncertain).coerceAtLeast(1))}）"
        )
        if (misses.isNotEmpty()) {
            println("  漏掉的样例（前 8 条）：")
            misses.forEach { println("    $it") }
        }
    }

    /** 在单元里找对应的英文句（样本 en 是片段/整句，做归一化后双向包含匹配）。 */
    private fun findUnit(bySource: Map<String, AlignedUnit>, english: String): AlignedUnit? {
        val needle = normalize(english)
        if (needle.isEmpty()) return null
        bySource[needle]?.let { return it }
        // 片段匹配：样本 en 可能只是单元文本的一部分
        for ((key, unit) in bySource) {
            if (key.contains(needle) || needle.contains(key)) return unit
        }
        return null
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

    // ──────────────────────────────────────────────────────────────
    //  语料装载（口径与 TtsTextExtractor 一致）
    // ──────────────────────────────────────────────────────────────

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/lotr-book").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到仓库根：${System.getProperty("user.dir")}")
    }

    private fun loadEnglishChapters(root: File): List<AlignmentChapter> {
        val metadata = File(root, "artifacts/lotr-book/metadata.json")
        if (!metadata.isFile) return emptyList()
        val json = org.json.JSONObject(metadata.readText())
        val chapters = json.getJSONArray("chapters")
        val out = ArrayList<AlignmentChapter>(chapters.length())
        for (i in 0 until chapters.length()) {
            val chapter = chapters.getJSONObject(i)
            val file = File(root, "artifacts/lotr-book/" + chapter.optString("relativePath", ""))
            if (!file.isFile) continue
            out.add(AlignmentChapter(chapter.optString("title", ""), leafBlocks(file)))
        }
        return out
    }

    private fun loadChineseChapters(root: File): List<AlignmentChapter> {
        val dir = File(root, "artifacts/lotr-zh/OEBPS/Text")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.extension.lowercase() == "xhtml" }
            ?.sortedBy { it.name }
            ?.map { AlignmentChapter(it.nameWithoutExtension, leafBlocks(it)) }
            ?: emptyList()
    }

    private fun leafBlocks(file: File): List<String> {
        val document = org.jsoup.Jsoup.parse(file, "UTF-8")
        return document.select(BLOCK_SELECTOR)
            .filter { candidate -> candidate.select(BLOCK_SELECTOR).all { it === candidate } }
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
    }

    private companion object {
        const val BLOCK_SELECTOR =
            "p, li, h1, h2, h3, h4, h5, h6, blockquote, td, figcaption, pre, div, " +
                "section, article, header, footer"
    }
}
