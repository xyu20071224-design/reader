package com.linguareader.shared.alignment

import com.linguareader.shared.translation.MeaningIndex
import com.linguareader.shared.translation.TranslationAligner
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.ln

/**
 * **全书端到端验收**：用翻译好的整本《魔戒》（41 章 / 3954 段 / 13503 句）评估生产对齐器。
 *
 * 这份语料与之前 ch1/ch3 的关键区别：
 *  - **覆盖量大 10 倍以上**，不再是两章抽样
 *  - 覆盖诗歌、附录、序言、脚注等全部文体
 *  - 译文由子代理按「段内每句一行」产出，`zhLines` 即 **P3 构造性句界**
 *
 * 因此本探针能回答两个此前无法回答的问题：
 *  1. P3 在**整本书**上到底能带来多少收益（不是两章）
 *  2. 对齐器在长尾文体上的鲁棒性
 *
 * 三个对照：
 *  - **B 现状**：只给整段中文，对齐器自己分句（生产默认行为）
 *  - **A P3**：把 `zhLines` 作为构造性句界传入
 *  - **上界**：真值本身（构造性 1:1）
 */
class FullBookAcceptanceProbe {

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
        while (dir != null && !File(dir, "artifacts/ai-full/book_truth_flat.json").isFile) {
            dir = dir.parentFile
        }
        return dir
    }

    @Test
    fun measure() {
        val repo = repoRoot() ?: run { println("[fullbook] SKIP: corpus missing"); return }
        val truthFile = File(repo, "artifacts/ai-full/book_truth.json")
        val flatFile = File(repo, "artifacts/ai-full/book_truth_flat.json")
        if (!truthFile.isFile || !flatFile.isFile) {
            println("[fullbook] SKIP: need book_truth.json + book_truth_flat.json")
            return
        }

        val ecdict = EcdictProvider.load(repo)
        val meaningIndex: MeaningIndex? = ecdict?.let { p ->
            object : MeaningIndex {
                override fun phrasesOf(word: String): Set<String> =
                    p.chineseCandidates(word).flatMap { EcdictProvider.parseGlosses(it) }.toSet()
            }
        }
        println("[fullbook] ECDICT=" + (ecdict?.entryCount ?: 0))

        // ---- 读真值 ----
        val rich = JSONObject(truthFile.readText(Charsets.UTF_8))
        val chapters = rich.getJSONArray("chapters")
        println("[fullbook] 章=" + chapters.length() +
            " 段=" + rich.getJSONObject("meta").optInt("paragraphs") +
            " 句=" + rich.getJSONObject("meta").optInt("zhSentences"))

        // 每个章：英文段落列表、中文段落列表（整段）、P3 句界表、真值 (en句 → zh句)
        val enChapters = ArrayList<List<String>>()
        val zhChapters = ArrayList<List<String>>()
        val knownAll = HashMap<String, List<String>>()
        val truthPairs = ArrayList<Pair<String, String>>()   // 扁平真值

        for (ci in 0 until chapters.length()) {
            val ch = chapters.getJSONObject(ci)
            val paras = ch.getJSONArray("paragraphs")
            val enList = ArrayList<String>()
            val zhList = ArrayList<String>()
            for (pi in 0 until paras.length()) {
                val p = paras.getJSONObject(pi)
                val en = p.getString("en")
                val zh = p.getString("zh")
                val lines = p.getJSONArray("zhLines")
                val zhFlat = (0 until lines.length()).joinToString(" ") { lines.getString(it) }
                enList += en
                zhList += zhFlat
                val key = zhFlat.replace(Regex("\\s+"), " ").trim()
                if (lines.length() > 1) {
                    knownAll[key] = (0 until lines.length()).map { lines.getString(it) }
                }
                // 真值配对：英文侧用生产分句器切，与 zhLines 逐句对应
                val enSents = com.linguareader.shared.tts.SentenceSplitter
                    .split(en).filter { s -> s.any { it.isLetterOrDigit() } }
                for (k in 0 until minOf(enSents.size, lines.length())) {
                    truthPairs += enSents[k] to lines.getString(k)
                }
            }
            enChapters += enList
            zhChapters += zhList
        }

        // 真值映射（en句 → 可接受 zh 集合）
        val truthSet = HashMap<String, MutableSet<String>>()
        for ((e, z) in truthPairs) truthSet.getOrPut(norm(e)) { HashSet() }.add(norm(z))

        // IDF 权重
        val df = HashMap<String, Int>()
        for ((e, _) in truthPairs) contentWords(e).distinct().forEach { w ->
            df[w] = (df[w] ?: 0) + 1
        }
        val nDoc = truthPairs.size.toDouble()
        fun idf(w: String) = ln((nDoc + 1.0) / ((df[w] ?: 1) + 1.0)).coerceAtLeast(0.05)
        val distinctEn = LinkedHashSet<String>()
        for ((e, _) in truthPairs) distinctEn += norm(e)
        val wOf = HashMap<String, Double>()
        var wAll = 0.0
        for (k in distinctEn) {
            var b = 0.0
            for (w in contentWords(k)) b += idf(w)
            wOf[k] = b
            wAll += b
        }
        println("[fullbook] 不同英文句=${distinctEn.size} knownSentences=${knownAll.size}")

        // ---- B 现状 ----
        runOne("B 现状（分句器推断）", enChapters, zhChapters, meaningIndex,
            TranslationAligner.ZhSplitPolicy.WITHOUT_SEMICOLON,
            truthSet, distinctEn, wOf, wAll)

        // ---- A P3 ----
        runOne("A P3（构造性句界）", enChapters, zhChapters, meaningIndex,
            TranslationAligner.ZhSplitPolicy.withKnownSentences(knownAll),
            truthSet, distinctEn, wOf, wAll)

        println()
        println("=== 判读 ===")
        println("  同一份语料、同一个对齐器，唯一差别是中文句界「猜」还是「已知」。")
        println("  代价 = 加权无答案×1.0 + 加权静默错×2.0")
    }

    private fun runOne(
        label: String,
        en: List<List<String>>,
        zh: List<List<String>>,
        meaning: MeaningIndex?,
        policy: TranslationAligner.ZhSplitPolicy,
        truthSet: Map<String, MutableSet<String>>,
        distinctEn: Set<String>,
        wOf: Map<String, Double>,
        wAll: Double
    ) {
        val started = System.currentTimeMillis()
        val pairs = TranslationAligner.align(en, zh, meaning, policy)
        val elapsed = System.currentTimeMillis() - started

        val got = HashMap<String, String>()
        for (p in pairs) {
            val k = norm(p.enSentence)
            if (k.isNotEmpty() && !got.containsKey(k)) got[k] = norm(p.zhSentence)
        }
        var correct = 0; var wrong = 0; var none = 0
        var wCorrect = 0.0; var wWrong = 0.0; var wNone = 0.0
        for (k in distinctEn) {
            val b = wOf[k] ?: 0.0
            val g = got[k]
            when {
                g == null -> { none++; wNone += b }
                truthSet[k]?.contains(g) == true -> { correct++; wCorrect += b }
                else -> { wrong++; wWrong += b }
            }
        }
        val total = distinctEn.size
        val wp = { v: Double -> if (wAll <= 0) 0.0 else v * 100 / wAll }
        val cost = wp(wNone) + 2.0 * wp(wWrong)
        println()
        println("--- $label ---")
        println("  句对=${pairs.size} 耗时=${elapsed}ms")
        println("  正确=${correct}(${"%.2f".format(correct * 100.0 / total)}%) " +
            "静默错=${wrong}(${"%.2f".format(wrong * 100.0 / total)}%) " +
            "无答案=${none}(${"%.2f".format(none * 100.0 / total)}%)")
        println("  加权: 正确 ${"%.2f".format(wp(wCorrect))}% " +
            "静默错 ${"%.2f".format(wp(wWrong))}% 无答案 ${"%.2f".format(wp(wNone))}%")
        println("  代价=${"%.2f".format(cost)}")
    }
}
