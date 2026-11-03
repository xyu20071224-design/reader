package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **单句完整 trace + IDF 分布定阈值**（Lead 裁定 A 的前置数据）。
 *
 * 两件事：
 * 1. 打印**一个具体句子**的完整 V1 计算过程（`terms(E)` 取了哪些词、各 IDF、
 *    各词的中文候选、哪些命中译文）——验证「权重挂在功能词无用释义上」这个判断；
 * 2. 打印英文书里**词的 IDF 分布**（p50/p60/p70/p80），用数据定「丢弃最低多少分位」。
 */
class IdfTraceProbe {

    @Test
    fun traceOneSentenceAndIdfDistribution() {
        val root = findRepoRoot()
        val enChapters = loadEnglishChapters(root)
        val zhChapters = loadChineseChapters(root)
        val ecdict = EcdictProvider.load(root)
        if (enChapters.isEmpty() || zhChapters.isEmpty() || ecdict == null) {
            println("SKIP: 语料或词典缺失")
            return
        }
        val source = BookSide.build(SideKind.SOURCE, enChapters)
        val target = BookSide.build(SideKind.TARGET, zhChapters)

        // ── 第一部分：英文词的 IDF 分布（用「出现句数」当 df）────────────
        println("=== 英文词的 df/idf 分布（用于定「丢弃最低多少分位」）===")
        val documentFrequency = HashMap<Int, Int>(16384)
        for (s in 0 until source.sentCount) {
            for (h in source.sentWordHashes[s]) documentFrequency.merge(h, 1, Int::plus)
        }
        // 只统计有 ECDICT 释义的词（Bridge 实际用的那批）
        val surface = HashMap<Int, String>(8192)
        for (s in 0 until source.sentCount) {
            val text = source.sentText[s]
            var i = 0
            while (i < text.length) {
                if (TextScan.isLetter(text[i])) {
                    var j = i + 1
                    while (j < text.length && (TextScan.isLetter(text[j]) ||
                            text[j] == '\'' || text[j] == '-')) j++
                    val hash = TextScan.hashLowerAscii(text, i, j)
                    if (documentFrequency.containsKey(hash)) {
                        surface.putIfAbsent(hash, text.substring(i, j).lowercase())
                    }
                    i = j
                } else i++
            }
        }
        val totalSentences = source.sentCount.coerceAtLeast(1)
        val rows = ArrayList<Triple<String, Int, Double>>(2048)
        for ((hash, df) in documentFrequency) {
            val word = surface[hash] ?: continue
            if (ecdict.chineseCandidates(word).isEmpty()) continue
            rows.add(Triple(word, df, Math.log((1.0 + totalSentences) / (1.0 + df))))
        }
        rows.sortBy { it.third }
        println("  有 ECDICT 释义的不同英文词=${rows.size}")
        for (p in doubleArrayOf(0.0, 0.1, 0.25, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)) {
            val index = ((rows.size - 1) * p).toInt().coerceIn(0, rows.lastIndex)
            val row = rows[index]
            println(
                "  p${Math.round(p * 100)}: idf=${String.format("%.3f", row.third)} " +
                    "df=${row.second} word=\"${row.first}\""
            )
        }
        // 各分位点以下是什么词（判断是否功能词）
        println()
        println("  --- 最低 IDF 段（p0–p20）的词（应当全是功能词）---")
        println("    " + rows.take(20).joinToString(", ") { "${it.first}(df=${it.second})" })
        println("  --- p60–p70 段的词（临界区）---")
        val from60 = (rows.size * 0.60).toInt()
        println(
            "    " + rows.subList(from60, (from60 + 15).coerceAtMost(rows.size))
                .joinToString(", ") { "${it.first}(df=${it.second})" }
        )
        println("  --- p80–p90 段的词（应全是内容词）---")
        val from80 = (rows.size * 0.80).toInt()
        println(
            "    " + rows.subList(from80, (from80 + 15).coerceAtMost(rows.size))
                .joinToString(", ") { "${it.first}(df=${it.second})" }
        )

        // ── 第二部分：单句完整 trace ────────────────────────────────
        println()
        println("=== 单句完整 V1 trace ===")
        val density = DensityModel(source)
        val structural = BooleanArray(source.chapterCount) { source.isStructural(it) }
        density.totalRatioFromChapters(target.chapterChars, source.chapterWords, structural)
        val verify = VerifyLayer(source, target, density, ecdict)
        verify.build()
        println("  bridge notes=${verify.notes}")

        // 找一句有代表意义的：英文 >60 字、terms 非空、recall 为 0
        var traced = false
        for (i in 0 until minOf(source.sentCount, target.sentCount)) {
            if (traced) break
            if (!source.sentLangUnit[i] || !target.sentLangUnit[i]) continue
            val english = source.sentText[i]
            if (english.length < 60) continue
            val score = verify.score(i, 1, i, 1)
            if (!score.available || score.recall > 0.0) continue
            traced = true
            println("  --- i=$i ---")
            println("    en=\"${english.take(110)}\"")
            println("    zh=\"${target.sentText[i].take(80)}\"")
            println("    recall=${fmt(score.recall)} precision=${fmt(score.precision)}")
            println()
            println("    terms(E) 明细（word → ECDICT 候选 → 是否命中译文）：")
            val zhNorm = target.sentNormText[i]
            val words = englishWords(english)
            var hitCount = 0
            for (word in words) {
                val glosses = ecdict.chineseCandidates(word)
                if (glosses.isEmpty()) {
                    println("      $word → (无 ECDICT 释义)")
                    continue
                }
                var hit = ""
                for (g in glosses) if (zhNorm.contains(g)) { hit = g; break }
                if (hit.isNotEmpty()) hitCount++
                println(
                    "      $word → ${glosses.take(4)} " +
                        if (hit.isNotEmpty()) "✅ 命中 \"$hit\"" else "❌"
                )
            }
            println("    命中词数=$hitCount/${words.size}")
        }
        if (!traced) println("  （没找到符合条件的句子）")
    }

    private fun englishWords(sentence: String): List<String> {
        val result = ArrayList<String>(16)
        var i = 0
        while (i < sentence.length) {
            if (TextScan.isLetter(sentence[i])) {
                var j = i + 1
                while (j < sentence.length && (TextScan.isLetter(sentence[j]) ||
                        sentence[j] == '\'' || sentence[j] == '-')) j++
                val word = sentence.substring(i, j).lowercase()
                if (word.length >= 2) result.add(word)
                i = j
            } else i++
        }
        return result
    }

    private fun fmt(v: Double): String = String.format("%.3f", v)

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
