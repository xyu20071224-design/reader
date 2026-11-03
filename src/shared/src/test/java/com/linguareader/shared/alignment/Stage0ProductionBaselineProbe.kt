package com.linguareader.shared.alignment

import com.linguareader.shared.translation.MeaningIndex
import com.linguareader.shared.translation.TranslationAligner
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.ln

/**
 * **阶段 0（零 API 费用 / 零生产改动）**：量出**生产链路**的真实表现。
 *
 * ## 为什么必须做这一步
 *
 * P3 影响面分析发现：`AiTranslationAligner`（`ai-pair-v1`）在 `src/app/src/main` 里
 * **零引用**，生产实际跑的是 [TranslationAligner.align]（`TranslationMemoryRepository`
 * 第 343 行）。而此前所有「第 1 章 2 条静默错」的数据都来自研究线的 AI 对齐器。
 *
 * 也就是说：**生产链路的静默错率从未被测量过**。在投入 12~14 个文件的 P3 改造之前，
 * 必须先知道现状有多差——如果现状本来就很好，P3 就是不必要的复杂度。
 *
 * ## 口径（与验收报告一致，全部带分母）
 *
 *  - **coverage**      = ALIGNED 的英文句 / 真值英文句总数
 *  - **precision**     = 译文与真值逐字符相等（去空白）的句对 / ALIGNED 句对数
 *  - **silent-wrong**  = 档案里有一条句对、但译文和真值不等 → 用户以为对、其实错
 *  - **no-answer**     = 该英文句在档案里没有任何句级条目 → 退到段级兜底
 *
 * 同时给出 **IDF 加权**（用户视角）与**「若句界完美」的理论上界**，便于判断差距来源。
 *
 * 判定「生产档案里某英文句有没有条目」用 `enSentence` 精确文本匹配（去空白）。
 */
class Stage0ProductionBaselineProbe {

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
        while (dir != null && !File(dir, EcdictProvider.EXPORT_RELATIVE_PATH).isFile) {
            dir = dir.parentFile
        }
        return dir
    }

    @Test
    fun measure() {
        val repo = repoRoot() ?: run { println("[stage0] SKIP: repo root not found"); return }
        val ecdict = EcdictProvider.load(repo)
        println("[stage0] ecdict=" + (ecdict?.entryCount ?: 0) + " entries")
        val meaningIndex: MeaningIndex? = ecdict?.let { p ->
            object : MeaningIndex {
                override fun phrasesOf(word: String): Set<String> =
                    // 与生产 EcdictMeaningIndex 同口径：2–4 字纯汉字段
                    p.chineseCandidates(word)
                        .flatMap { EcdictProvider.parseGlosses(it) }
                        .toSet()
            }
        }

        report(repo, "ch1", "artifacts/ai-ch1/ch1_truth.json", meaningIndex)
        report(repo, "ch3", "artifacts/ai-ch3/ch3_merged_partial.json", meaningIndex)

        println()
        println("=== 判读 ===")
        println("  silent-wrong = 档案里有句对但译文与真值不等 → 用户看到错译文却以为是本句的")
        println("  no-answer    = 该英文句没有句级条目 → 退段级兜底（用户点句拿整段）")
        println("  若 silent-wrong 接近 0 → 生产链路本来就没问题，P3 不值得做")
        println("  若 silent-wrong 明显 > 0 → 定标了 P3 的收益上界，可据此决定投入")
    }

    private fun report(repo: File, name: String, rel: String, meaningIndex: MeaningIndex?) {        val file = File(repo, rel)
        if (!file.isFile) { println("=== $name: MISSING $rel ==="); return }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val arr = json.getJSONArray("paragraphs")

        val enParas = ArrayList<String>()
        val zhParas = ArrayList<String>()
        data class T(val en: String, val zh: String)
        val truth = ArrayList<T>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            val enText = StringBuilder()
            val zhText = StringBuilder()
            for (k in 0 until en.length()) {
                if (enText.isNotEmpty()) enText.append(' ')
                enText.append(en.getString(k))
                zhText.append(zh.getString(k))
                // truth 只在两侧句数相等时有意义（语料构造如此）
                if (k < zh.length()) truth += T(en.getString(k), zh.getString(k))
            }
            enParas.add(enText.toString())
            zhParas.add(zhText.toString())
        }

        println()
        println("=== $name ===")
        println("  paragraphs=${enParas.size} truthSentences=${truth.size}")

        val started = System.currentTimeMillis()
        val pairs = TranslationAligner.align(listOf(enParas), listOf(zhParas), meaningIndex)
        val elapsed = System.currentTimeMillis() - started

        // 真值：英文句 → 可接受的中文句集合；同时记录该句所在段落的句数是否相等
        val truthSet = HashMap<String, MutableSet<String>>()
        // 段落级：句数相等（enN == zhN）的段落里产生的英文句集合 → 用于归因
        val parityOkSentences = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            if (en.length() == zh.length()) {
                for (k in 0 until en.length()) parityOkSentences += norm(en.getString(k))
            }
        }
        for (t in truth) truthSet.getOrPut(norm(t.en)) { HashSet() }.add(norm(t.zh))

        // 生产档案：英文句 → 产出的条数 / 第一条译文
        val gotCount = HashMap<String, Int>()
        val gotFirst = HashMap<String, String>()
        for (p in pairs) {
            val key = norm(p.enSentence)
            gotCount[key] = (gotCount[key] ?: 0) + 1
            if (!gotFirst.containsKey(key)) gotFirst[key] = norm(p.zhSentence)
        }

        var correct = 0
        var wrong = 0
        var multi = 0
        var noAnswer = 0
        // 归因：错误/无答案是否落在「段落句数相等」的段里
        var wrongInParityOk = 0
        var wrongInParityBad = 0
        var noAnswerInParityOk = 0
        var noAnswerInParityBad = 0
        var wCorrect = 0.0
        var wWrong = 0.0
        var wNoAnswer = 0.0
        var wAll = 0.0
        val wrongSamples = ArrayList<String>()

        val df = HashMap<String, Int>()
        for (t in truth) contentWords(t.en).distinct().forEach { w -> df[w] = (df[w] ?: 0) + 1 }
        val nDoc = truth.size.toDouble()
        fun idf(w: String) = ln((nDoc + 1.0) / ((df[w] ?: 1) + 1.0)).coerceAtLeast(0.05)

        val seenEn = HashSet<String>()
        for (t in truth) {
            val key = norm(t.en)
            if (!seenEn.add(key)) continue  // 重复英文句只算一次，避免分母虚高
            var bucketW = 0.0
            for (w in contentWords(t.en)) bucketW += idf(w)
            wAll += bucketW

            val c = gotCount[key] ?: 0
            if (c == 0) {
                noAnswer++
                if (key in parityOkSentences) noAnswerInParityOk++ else noAnswerInParityBad++
                wNoAnswer += bucketW
                continue
            }
            if (c > 1) multi++
            val got = gotFirst[key] ?: ""
            if (got.isNotEmpty() && truthSet[key]?.contains(got) == true) {
                correct++
                wCorrect += bucketW
            } else {
                wrong++
                if (key in parityOkSentences) wrongInParityOk++ else wrongInParityBad++
                wWrong += bucketW
                if (wrongSamples.size < 6) {
                    wrongSamples += "  WRONG: ${t.en.take(60)}\n" +
                        "         got =${got.take(34)}\n" +
                        "         want=${norm(t.zh).take(34)}"
                }
            }
        }

        val total = seenEn.size
        fun pct(v: Int) = if (total == 0) 0.0 else v * 100.0 / total
        fun wpct(v: Double) = if (wAll <= 0) 0.0 else v * 100 / wAll

        println("  pairs=${pairs.size} distinctEn=${pairs.map { norm(it.enSentence) }.toSet().size} " +
            "elapsed=${elapsed}ms")
        println("  --- 句级（分母 = 真值不同英文句 $total）---")
        println("  correct    = $correct (${"%.2f".format(pct(correct))}%)")
        println("  SILENT-WRONG = $wrong (${"%.2f".format(pct(wrong))}%)   ← 关键指标")
        println("  no-answer  = $noAnswer (${"%.2f".format(pct(noAnswer))}%)")
        println("  multiEntry = $multi （同一英文句产出多条）")
        if (correct + wrong > 0) {
            println("  precision  = ${"%.2f".format(correct * 100.0 / (correct + wrong))}%")
        }
        println("  coverage   = ${"%.2f".format(pct(correct + wrong))}%")
        println("  --- IDF 加权 ---")
        println("  A-quiet    = ${"%.2f".format(wpct(wCorrect))}%")
        println("  A-wrong    = ${"%.2f".format(wpct(wWrong))}%   ← 静默错")
        println("  no-answer  = ${"%.2f".format(wpct(wNoAnswer))}%")
        println("  有答案且正确 = ${"%.2f".format(wpct(wCorrect))}%")
        println("  --- 归因（错误落在哪类段落）---")
        println("  静默错的 $wrong 条：句数相等段 ${wrongInParityOk} / 句数不等段 ${wrongInParityBad}")
        println("  无答案的 $noAnswer 条：句数相等段 ${noAnswerInParityOk} / 句数不等段 ${noAnswerInParityBad}")
        println("  → 若错误绝大多数在「句数不等段」，则句级 1:1 已到上限，")
        println("    进一步收益只能靠「让中文句界正确」（P3）")
        if (wrongSamples.isNotEmpty()) {
            println("  错标样例（最多 6 条）:")
            wrongSamples.forEach { println(it) }
        }
    }
}
