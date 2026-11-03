package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **词典桥内部诊断**（真实语料）：桥建起来了（1507 词条），为什么 `recall` 恒 0？
 *
 * 逐环节核对：
 * 1. 桥的词条长什么样（`terms` 的实际中文形）
 * 2. 中文句扫描命中了什么（`sentenceHits`）
 * 3. 英文句的 `terms(E)` 是什么
 * 4. 两侧命中的词条下标是否有交集
 *
 * 这是「同序号配对下 recall 恒 0」这个异常的唯一可信定位方式。
 */
class BridgeDiagnosisProbe {

    @Test
    fun traceBridgeOnRealCorpus() {
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
        val density = DensityModel(source)
        val structural = BooleanArray(source.chapterCount) { source.isStructural(it) }
        density.totalRatioFromChapters(target.chapterChars, source.chapterWords, structural)
        val verify = VerifyLayer(source, target, density, ecdict)
        verify.build()

        println("=== 桥的构造结果 ===")
        println("  ${verify.notes}")

        // 用公开 API 逐句看：terms 是否为空、hits 是否为空、交集是否为 0
        println()
        println("=== 逐句诊断（找第一个「terms 非空但 recall=0」的句子）===")
        var printed = 0
        for (i in 0 until source.sentCount) {
            if (printed >= 6) break
            if (!source.sentLangUnit[i]) continue
            val t = i.coerceAtMost(target.sentCount - 1)
            if (!target.sentLangUnit[t]) continue
            val score = verify.score(i, 1, t, 1)
            if (!score.available) continue
            val english = source.sentText[i]
            if (english.length < 40) continue
            println("  --- i=$i ---")
            println("    en=\"${english.take(90)}\"")
            println("    zh=\"${target.sentText[t].take(70)}\"")
            println(
                "    recall=${fmt(score.recall)} precision=${fmt(score.precision)} " +
                    "lift=${fmt(score.lift)}"
            )
            // 该句英文词 → 词典候选（直接问 provider）
            val words = englishWords(english)
            var withGloss = 0
            val sampleGlosses = ArrayList<String>()
            for (word in words) {
                val glosses = ecdict.chineseCandidates(word)
                if (glosses.isNotEmpty()) {
                    withGloss++
                    if (sampleGlosses.size < 5) sampleGlosses.add("$word→${glosses.take(3)}")
                }
            }
            println("    英文词数=${words.size}，其中有 ECDICT 释义=$withGloss")
            println("    样例：${sampleGlosses.joinToString("; ")}")
            // 这些释义在中文句里出现了吗？
            val zhText = target.sentNormText[t]
            var glossHitInSentence = 0
            val hitSamples = ArrayList<String>()
            for (word in words) {
                for (gloss in ecdict.chineseCandidates(word)) {
                    if (gloss.length in 2..4 && zhText.contains(gloss)) {
                        glossHitInSentence++
                        if (hitSamples.size < 5) hitSamples.add("$word→$gloss")
                        break
                    }
                }
            }
            println("    有释义且在中文句里出现的词=$glossHitInSentence")
            println("    命中样例：${hitSamples.joinToString("; ")}")
            printed++
        }
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
