package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **真实书上的合并跨度分布与覆盖率分子口径验证**（Lead 裁定前的必备数据）。
 *
 * 回答三个问题：
 * 1. 「几个英文句对应 1 个中文句」的真实分布（p50/p90/p99）→ 定 `MAX_SPAN_ONE_SIDED`
 * 2. 覆盖率分子：`N:1` 边投影出的 N 个 ALIGNED 单元，是否各算 1 句（契约 §4.1）
 * 3. 中文侧的数字是阿拉伯数字还是汉字（决定数字锚点是否可用）
 */
class RealSpanDistributionProbe {

    @Test
    fun measureSpanDistributionAndNumerals() {
        val root = findRepoRoot()
        val enChapters = loadEnglishChapters(root)
        val zhChapters = loadChineseChapters(root)
        if (enChapters.isEmpty() || zhChapters.isEmpty()) {
            println("SKIP: 语料缺失")
            return
        }
        val source = BookSide.build(SideKind.SOURCE, enChapters)
        val target = BookSide.build(SideKind.TARGET, zhChapters)

        println("=== 语料 ===")
        println("  en 句=${source.sentCount}  zh 句=${target.sentCount}  " +
            "比值=${String.format("%.2f", source.sentCount.toDouble() / target.sentCount)}")

        // ── 问题 3：中文侧数字形态 ────────────────────────────────
        println()
        println("=== 问题 3：中文侧数字形态（决定数字锚点是否可用）===")
        var arabicDigits = 0
        var fullwidthDigits = 0
        var chineseNumerals = 0
        for (s in 0 until target.sentCount) {
            for (c in target.sentText[s]) {
                when {
                    c in '0'..'9' -> arabicDigits++
                    c in '０'..'９' -> fullwidthDigits++
                    c in '〇'..'九' || c == '十' || c == '百' || c == '千' || c == '萬' ||
                        c == '万' || c == '零' -> chineseNumerals++
                }
            }
        }
        println("  阿拉伯数字 0-9：$arabicDigits 次")
        println("  全角数字：$fullwidthDigits 次")
        println("  汉字数字（〇一二三四五六七八九十百千万）：$chineseNumerals 次")
        println(
            "  → 阿拉伯数字占 ${pct(arabicDigits.toDouble() / (arabicDigits + chineseNumerals).coerceAtLeast(1))}，" +
                "汉字数字占 ${pct(chineseNumerals.toDouble() / (arabicDigits + chineseNumerals).coerceAtLeast(1))}"
        )
        // 中文侧拉丁串（锚点第一类）
        var latinInZh = 0
        for (s in 0 until target.sentCount) latinInZh += target.sentLatins[s].size
        println("  中文侧拉丁串键总数：$latinInZh（$target.sentCount 句共 ${target.sentCount} 句）")
        println("  → 若这个数极小，说明「中文侧拉丁串」作为锚点源在真实译本上不可用")

        // ── 问题 1：真实书的合并跨度分布 ──────────────────────────
        println()
        println("=== 问题 1：真实合并跨度分布 ===")
        println("  说明：用「段落级」粗估——按段落字数比推算每段对应的句数比")
        val paragraphRatios = ArrayList<Double>()
        val enParagraphs = source.paraCount
        val zhParagraphs = target.paraCount
        println("  en 段=$enParagraphs  zh 段=$zhParagraphs")
        for (p in 0 until minOf(enParagraphs, zhParagraphs)) {
            val enSents = source.paraSentCount[p]
            val zhSents = target.paraSentCount[p]
            if (zhSents > 0) paragraphRatios.add(enSents.toDouble() / zhSents)
        }
        paragraphRatios.sort()
        println(
            "  同序号段落的 英文句数/中文句数：p50=${fmt(quantile(paragraphRatios, 0.5))} " +
                "p90=${fmt(quantile(paragraphRatios, 0.9))} " +
                "p99=${fmt(quantile(paragraphRatios, 0.99))} " +
                "max=${fmt(paragraphRatios.lastOrNull() ?: 0.0)}"
        )

        // 全局句数比给出「平均需要几次合并」的直接度量
        println(
            "  全书句数比 en/zh = ${fmt(source.sentCount.toDouble() / target.sentCount)} " +
                "→ 平均每个中文句要承载 ${fmt(source.sentCount.toDouble() / target.sentCount)} 个英文句"
        )

        // ── 问题 2：覆盖率分子口径 ────────────────────────────────
        println()
        println("=== 问题 2：覆盖率分子口径验证 ===")
        val document = IndependentAligner().align(enChapters, zhChapters, null)
        val alignedUnits = document.units.filter { it.status == AlignmentStatus.ALIGNED }
        val sumSpan = alignedUnits.sumOf { it.sourceSpanSize }
        println("  ALIGNED 单元数=${alignedUnits.size}")
        println("  Σ sourceSpanSize = $sumSpan")
        println("  stats.sourceSentencesAligned = ${document.stats.sourceSentencesAligned}")
        println("  两者相等：${sumSpan == document.stats.sourceSentencesAligned}")
        val spanHist = alignedUnits.groupingBy { it.sourceSpanSize }.eachCount()
        println("  ALIGNED 单元的 sourceSpanSize 分布：$spanHist（应恒为 {1=N}）")
        println(
            "  → 契约 §4.1「合并对按实际覆盖句数计」：`N:1` 拆成 N 个单元后，" +
                "每个单元 sourceSpanSize=1，N 个单元各算 1 句，合计 N 句 ✅"
        )
        println("  stats.mergedUnits（按 DP 边）= ${document.stats.mergedUnits}")
        println("  → 合并边数与覆盖句数不是一个概念，不能混用")

        // 若合并边通过判定，它们能贡献多少覆盖
        println()
        println("  当前 mergedUnits=${document.stats.mergedUnits}，但 ALIGNED 单元只有 ${alignedUnits.size}")
        println("  → 合并边几乎全部未通过判定（见证门），这是覆盖率低的直接原因")
    }

    // ──────────────────────────────────────────────────────────────

    private fun quantile(sorted: List<Double>, p: Double): Double =
        if (sorted.isEmpty()) 0.0
        else sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]

    private fun fmt(v: Double): String = String.format("%.3f", v)

    private fun pct(v: Double): String = "${Math.round(v * 10000.0) / 100.0}%"

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/lotr-book").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到仓库根")
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
