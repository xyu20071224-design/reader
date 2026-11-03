package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **产品形态评估的独立复算**（Lead 要求：不直接采信 implementer 的数字）。
 *
 * 问题：在 89 条人工金标准正样本上，「ALIGNED + 带候选 UNCERTAIN」这个产品形态到底
 * 能展示多少条、其中多少条**真的对**？
 *
 * Lead 给的参考数字（本探针**独立复算并逐条打印**，不采信）：
 *
 * ```
 * ALIGNED 覆盖               = 0/89  (0%)
 * ALIGNED + 带候选 UNCERTAIN = 14/89 (15.7%)
 * 其中人工判 bad = 2 → 可用精确率 85.7%
 * ```
 *
 * ## 为什么必须逐条打印
 *
 * 聚合数字看不出「看起来对但其实错」。那 14 条里若混入错配，可用精确率会掉，
 * 产品形态结论就会翻转。所以这里把英文句 / 算法中文 / 人工中文 / 人工 verdict
 * 全部打出来，供人眼判断。
 *
 * ## 与其他文件的分工
 *
 * [RealCorpusAcceptanceTest] 已经算了同样的聚合指标。本探针**不重复造聚合逻辑**，
 * 只补它缺的「逐条可展示条目明细」——这正是 Lead 点名的验证需求。
 */
class ProductFormAuditProbe {

    @Test
    fun independentlyRecomputeProductFormNumbers() {
        val root = findRepoRoot() ?: run {
            println("SKIP: 找不到仓库根（需要 artifacts/lotr-book）")
            return
        }
        val enChapters = loadEnglishChapters(root)
        val zhChapters = loadChineseChapters(root)
        if (enChapters.isEmpty() || zhChapters.isEmpty()) {
            println("SKIP: 魔戒语料缺失")
            return
        }
        val goldenFile = File(root, "artifacts/alignment-eval/golden-samples.json")
        if (!goldenFile.isFile) {
            println("SKIP: golden-samples.json 缺失")
            return
        }
        val ecdict = EcdictProvider.load(root)
        println("词典：${if (ecdict == null) "无" else "真 ECDICT（${ecdict.entryCount} 词条）"}")

        val document = IndependentAligner().align(enChapters, zhChapters, ecdict)
        println("stats: ${document.stats.summaryLine()}")

        // 与 RealCorpusAcceptanceTest 相同的归一化匹配：样本 en 是片段/整句。
        val bySource = HashMap<String, AlignedUnit>()
        document.units.forEach { unit -> bySource.putIfAbsent(normalize(unit.sourceText), unit) }

        val json = org.json.JSONObject(goldenFile.readText())
        val samples = json.getJSONArray("samples")
        var positives = 0
        var alignedHit = 0
        var candidateHit = 0
        var humanBad = 0
        val showable = ArrayList<Row>()

        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            val english = sample.optString("en", "")
            val verdict = sample.optString("verdict", "")
            val level = sample.optJSONObject("approved")?.optString("level", "") ?: "MISS"
            val approvedZh = sample.optJSONObject("approved")?.optString("zh", "") ?: ""
            if (level == "MISS") continue
            positives++

            val unit = findUnit(bySource, english)
            val status = unit?.status
            val algoZh = unit?.targetText ?: ""
            val usable = status == AlignmentStatus.ALIGNED ||
                (status == AlignmentStatus.UNCERTAIN && algoZh.isNotBlank())
            if (status == AlignmentStatus.ALIGNED) alignedHit++
            if (usable) {
                candidateHit++
                if (verdict == "bad") humanBad++
                showable += Row(sample.getString("id"), english, status!!.name, algoZh,
                    approvedZh, verdict, level)
            }
        }

        println()
        println("=== 独立复算（金标准正样本 level != MISS）===")
        println("  正样本总数 = $positives")
        println("  ALIGNED 覆盖 = $alignedHit/$positives (${pct(alignedHit, positives)})")
        println("  ALIGNED + 带候选 UNCERTAIN = $candidateHit/$positives " +
            "(${pct(candidateHit, positives)})")

        println()
        println("=== 可展示条目逐条明细（${showable.size} 条）===")
        showable.forEach { row ->
            println("--- ${row.id} [${row.status}] human_verdict=${row.verdict}" +
                " level=${row.level} ${if (row.verdict == "bad") "<<< BAD" else ""} ---")
            println("  en       =\"${row.english.take(100)}\"")
            println("  algoZh   =\"${row.algoZh.take(100)}\"")
            println("  humanZh  =\"${row.humanZh.take(100)}\"")
        }

        println()
        println("=== 可用精确率 ===")
        println("  可展示 $candidateHit 条，其中人工判 bad = $humanBad")
        println("  → 可用精确率 = ${pct(candidateHit - humanBad, candidateHit)}")

        // 只保证数据能读到，不对数字设阈值——这是测量，不是断言。
        kotlin.test.assertTrue(positives > 0, "金标准正样本为空，口径有问题")
    }

    private class Row(
        val id: String,
        val english: String,
        val status: String,
        val algoZh: String,
        val humanZh: String,
        val verdict: String,
        val level: String
    )

    private fun pct(n: Int, d: Int): String =
        if (d == 0) "n/a" else "${Math.round(n * 10000.0 / d) / 100.0}%"

    private fun findUnit(bySource: Map<String, AlignedUnit>, english: String): AlignedUnit? {
        val needle = normalize(english)
        if (needle.isEmpty()) return null
        bySource[needle]?.let { return it }
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

    private fun findRepoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/lotr-book").isDirectory) return dir
            dir = dir.parentFile
        }
        return null
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
