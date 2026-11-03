package com.linguareader.shared.alignment

import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.ln

/**
 * 第 1 章验收：与 [Ch3RealAiTranslationAcceptanceTest] **完全同一套口径**，
 * 用于验证泛用性（第 1 章是叙事+长描写+诗歌，第 3 章是对话密集）。
 *
 * 只喂段落级中文，不给句级真值；输出后再拿真值算分。
 */
class Ch1RealAiTranslationAcceptanceTest {

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    private val stopwords = setOf(
        "a", "an", "the", "and", "or", "but", "if", "of", "to", "in", "on", "at", "by",
        "for", "with", "from", "as", "is", "are", "was", "were", "be", "been", "being",
        "am", "do", "does", "did", "have", "has", "had", "will", "would", "shall",
        "should", "may", "might", "must", "can", "could", "i", "you", "he", "she", "it",
        "we", "they", "me", "him", "her", "us", "them", "my", "your", "his", "its",
        "our", "their", "this", "that", "these", "those", "there", "here", "not", "no",
        "so", "up", "down", "out", "off", "over", "than", "then", "now", "just", "very",
        "too", "also", "only", "all", "any", "some", "more", "most", "such", "what",
        "which", "who", "whom", "whose", "when", "where", "why", "how", "said", "say",
        "says", "into", "onto", "upon", "about", "after", "before", "again", "still"
    )

    private fun contentWords(s: String): List<String> =
        Regex("[A-Za-z][A-Za-z'’-]*").findAll(s)
            .map { it.value.lowercase().trimEnd('\'', '’', '-') }
            .filter { it.length >= 2 && it !in stopwords }
            .toList()

    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, "artifacts/ai-ch1/ch1_truth.json").isFile) {
            dir = dir.parentFile
        }
        return dir
    }

    @Test
    fun measure() {
        val repo = repoRoot() ?: run { println("[ch1] SKIP: corpus missing"); return }
        val json = JSONObject(File(repo, "artifacts/ai-ch1/ch1_truth.json").readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")

        val enParas = ArrayList<String>()
        val zhParas = ArrayList<String>()
        data class P(val en: String, val zh: String)
        val truth = ArrayList<P>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            check(en.length() == zh.length()) { "truth mismatch at $i" }
            val enText = StringBuilder()
            val zhText = StringBuilder()
            for (k in 0 until en.length()) {
                if (enText.isNotEmpty()) enText.append(' ')
                enText.append(en.getString(k))
                zhText.append(zh.getString(k))
                truth += P(en.getString(k), zh.getString(k))
            }
            enParas.add(enText.toString())
            zhParas.add(zhText.toString())
        }

        val ecdict = EcdictProvider.load(repo)
        val provider: MeaningProvider = ecdict ?: object : MeaningProvider {
            override fun chineseCandidates(englishWord: String): List<String> = emptyList()
        }

        println("=== 第 1 章（叙事/描写/诗歌）===")
        println("paragraphs=${enParas.size} enSentences=${truth.size}")
        println("zhChars=" + zhParas.sumOf { it.length })
        println("ECDICT=" + (ecdict?.entryCount ?: 0))

        val started = System.currentTimeMillis()
        val doc = AiTranslationAligner().align(
            listOf(AlignmentChapter("Chapter 1", enParas)),
            listOf(AlignmentChapter("第1章", zhParas)),
            provider
        )
        val elapsed = System.currentTimeMillis() - started

        val truthSet = HashMap<String, MutableSet<String>>()
        for (t in truth) truthSet.getOrPut(norm(t.en)) { HashSet() }.add(norm(t.zh))
        val unitOf = HashMap<String, AlignedUnit>()
        for (u in doc.units) unitOf[norm(u.sourceText)] = u

        var aq = 0; var aw = 0; var uc = 0; var uw = 0; var nn = 0
        var missing = 0
        val wrongSamples = ArrayList<String>()
        var wAq = 0.0; var wAw = 0.0; var wUc = 0.0; var wUw = 0.0; var wNn = 0.0
        var wAll = 0.0

        val df = HashMap<String, Int>()
        for (t in truth) contentWords(t.en).distinct().forEach { w -> df[w] = (df[w] ?: 0) + 1 }
        val nDoc = truth.size.toDouble()
        fun idf(w: String) = ln((nDoc + 1.0) / ((df[w] ?: 1) + 1.0)).coerceAtLeast(0.05)

        for (t in truth) {
            val u = unitOf[norm(t.en)]
            if (u == null) { missing++; continue }
            val got = norm(u.targetText)
            val correct = got.isNotEmpty() && truthSet[norm(t.en)]?.contains(got) == true
            var bucketW = 0.0
            for (w in contentWords(t.en)) bucketW += idf(w)
            wAll += bucketW
            when (u.status) {
                AlignmentStatus.ALIGNED -> if (correct) { aq++; wAq += bucketW } else {
                    aw++; wAw += bucketW
                    if (wrongSamples.size < 5) wrongSamples += "ALIGNED-WRONG: ${t.en.take(50)} | got=${u.targetText.take(30)}"
                }
                AlignmentStatus.UNCERTAIN -> if (correct) { uc++; wUc += bucketW } else { uw++; wUw += bucketW }
                AlignmentStatus.UNALIGNED -> { nn++; wNn += bucketW }
            }
        }

        val total = truth.size
        fun pct(v: Int) = if (total == 0) 0.0 else v * 100.0 / total
        fun wpct(v: Double) = if (wAll <= 0) 0.0 else v * 100 / wAll

        println("units=${doc.units.size} elapsed=${elapsed}ms missingTruth=$missing")
        println("--- 句级 ---")
        println("  ALIGNED  $aq (correct) / $aw (wrong)   coverage=%.2f%%  precision=%.2f%%".format(
            pct(aq), if (aq + aw == 0) 0.0 else aq * 100.0 / (aq + aw)))
        println("  UNCERTAIN $uc (correct) / $uw (wrong)")
        println("  UNALIGNED $nn")
        println("--- IDF 加权（用户视角）---")
        println("  A-quiet   = %.2f%%".format(wpct(wAq)))
        println("  A-wrong   = %.2f%%   ← 红线".format(wpct(wAw)))
        println("  U-correct = %.2f%%".format(wpct(wUc)))
        println("  U-wrong   = %.2f%%".format(wpct(wUw)))
        println("  N-*       = %.2f%%".format(wpct(wNn)))
        println("  有答案且正确 = %.2f%%".format(wpct(wAq) + wpct(wUc)))
        println("  静默错       = %.2f%%".format(wpct(wAw)))
        println()
        println("  stats: ${doc.stats.summaryLine()}")
        if (wrongSamples.isNotEmpty()) {
            println("  错标样例:")
            wrongSamples.forEach { println("    $it") }
        }
        println()
        println("=== 与第 3 章对照 ===")
        println("  第3章（对话密集）: A-quiet 51.87%  U-correct 39.06%  N-* 6.45%  静默错 0.00%")
        println("  第1章（叙事/诗歌）: A-quiet %.2f%%  U-correct %.2f%%  N-* %.2f%%  静默错 %.2f%%".format(
            wpct(wAq), wpct(wUc), wpct(wNn), wpct(wAw)))
    }
}
