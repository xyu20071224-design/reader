package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **词义桥的覆盖死因量化**（真实语料）：为什么 recall 恒 0？
 *
 * 核心检验：ECDICT 的**释义字串**到底有没有出现在译本的**中文正文**里？
 * 若「释义字串覆盖率」本身就极低，那任何匹配粒度（2–4 字、单字）都救不了。
 */
class BridgeCoverageProbe {

    @Test
    fun measureGlossCoverageInTranslation() {
        val root = findRepoRoot()
        val zhChapters = loadChineseChapters(root)
        val ecdict = EcdictProvider.load(root)
        if (zhChapters.isEmpty() || ecdict == null) {
            println("SKIP: 语料或词典缺失")
            return
        }
        val target = BookSide.build(SideKind.TARGET, zhChapters)
        val corpusText = buildString {
            for (s in 0 until target.sentCount) append(target.sentNormText[s])
        }
        println("=== 语料 ===")
        println("  中文字符总数=${corpusText.length}  有效句=${target.sentCount}")

        // 取 Top-N 高频英文词（本书），看它们的 ECDICT 释义在译文里出现了几个
        val enChapters = loadEnglishChapters(root)
        val source = BookSide.build(SideKind.SOURCE, enChapters)
        val frequency = HashMap<Int, Int>(8192)
        for (s in 0 until source.sentCount) {
            for (h in source.sentWordHashes[s]) frequency.merge(h, 1, Int::plus)
        }
        val surface = HashMap<Int, String>(4096)
        for (s in 0 until source.sentCount) {
            val text = source.sentText[s]
            var i = 0
            while (i < text.length) {
                if (TextScan.isLetter(text[i])) {
                    var j = i + 1
                    while (j < text.length && (TextScan.isLetter(text[j]) ||
                            text[j] == '\'' || text[j] == '-')) j++
                    val hash = TextScan.hashLowerAscii(text, i, j)
                    if (frequency.containsKey(hash)) surface.putIfAbsent(hash, text.substring(i, j).lowercase())
                    i = j
                } else i++
            }
        }
        val ranked = frequency.entries.sortedByDescending { it.value }.take(300)

        var examined = 0
        var withGloss = 0
        var glossAppears = 0
        val examples = ArrayList<String>(10)
        for (entry in ranked) {
            val word = surface[entry.key] ?: continue
            examined++
            val glosses = ecdict.chineseCandidates(word)
            if (glosses.isEmpty()) continue
            withGloss++
            var appeared = false
            for (gloss in glosses) {
                if (corpusText.contains(gloss)) {
                    appeared = true
                    if (examples.size < 12) {
                        examples.add("$word (${entry.value}次) → \"$gloss\" ✅ 在译文中出现")
                    }
                    break
                }
            }
            if (appeared) glossAppears++
            else if (examples.size < 12) {
                examples.add("$word (${entry.value}次) → ${glosses.take(3)} ❌ 均未出现")
            }
        }
        println()
        println("=== Top-300 高频英文词的 ECDICT 释义是否出现在译文中 ===")
        println("  检查词数=$examined，有 ECDICT 释义=$withGloss")
        println(
            "  **释义字串出现在译文中的词：$glossAppears/$withGloss = " +
                "${pct(glossAppears.toDouble() / withGloss.coerceAtLeast(1))}**"
        )
        println()
        println("  样例：")
        examples.forEach { println("    $it") }

        // 单字级别的覆盖率
        println()
        println("=== 单字级：ECDICT 释义的组成字有多少出现在译文里 ===")
        val corpusChars = corpusText.toSet()
        var charExamined = 0
        var charPresent = 0
        val missingChars = ArrayList<Char>(32)
        for (entry in ranked.take(100)) {
            val word = surface[entry.key] ?: continue
            for (gloss in ecdict.chineseCandidates(word)) {
                for (c in gloss) {
                    charExamined++
                    if (c in corpusChars) charPresent++
                    else if (missingChars.size < 30 && c !in missingChars) missingChars.add(c)
                }
            }
        }
        println(
            "  释义用字总数=$charExamined，其中出现在译文里=$charPresent " +
                "(${pct(charPresent.toDouble() / charExamined.coerceAtLeast(1))})"
        )
        println("  未出现的字（前 30）：${missingChars.joinToString("")}")
        println()
        println("  → 若单字覆盖率高但整串覆盖率低，说明**译文用的是同一批字但组合方式完全不同**")
        println("    （例如 `戒指` vs `戒`），那单字匹配应当有效——但它需要有 IDF 权重区分")
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
