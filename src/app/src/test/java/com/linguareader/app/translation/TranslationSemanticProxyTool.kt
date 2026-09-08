package com.linguareader.app.translation

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.ln

/**
 * **工具测试（人工跑）**：语义代理信号的有效性验证——先证明它分得开「对/勉强对」与
 * 「错」，再谈要不要依赖它。
 *
 * 动机：对齐器自身的信号（长度比、锚点、词义加分、置信度）**不能**当质量指标——
 * 它们量的是「DP 是否优化了自己的目标函数」，是循环论证（V5 的教训：锚点/词义加分
 * 能把错对照的置信度拉过门槛）。这里引入一个**外部**信号：CC-CEDICT（社区维护的
 * 中英词典，与对齐器用的 ECDICT 不是同一份）反查「英文句的内容词，其中文候选是否
 * 出现在展示的中文里」，拿六轮人工判定的 100 条样本做 ROC/AUC 验证。
 *
 * 素材：`artifacts/generalization/cedict.txt.gz`（CC BY-SA 4.0，本地 gitignored，
 * 下载见 `src/tools/alignment-eval/fetch-semantic-proxy-corpus.sh`）。缺失时跳过。
 *
 * 运行：
 * ```
 * ./toolchain/build.sh :app:testDebugUnitTest \
 *   --tests "com.linguareader.app.translation.TranslationSemanticProxyTool"
 * ```
 */
@RunWith(RobolectricTestRunner::class)
class TranslationSemanticProxyTool {

    private val enWord = Regex("[a-z][a-z'-]+")
    private val stopwords = setOf(
        "the", "and", "for", "that", "with", "was", "were", "his", "her", "him", "she", "they",
        "them", "their", "there", "here", "have", "has", "had", "not", "but", "you", "your",
        "are", "all", "one", "two", "out", "into", "upon", "from", "this", "these", "those",
        "who", "whom", "which", "what", "when", "where", "will", "would", "could", "should",
        "been", "being", "about", "than", "then", "now", "more", "most", "some", "such", "very",
        "said", "says", "say", "come", "came", "went", "goes", "going", "get", "got", "let",
        "man", "men", "way", "day", "days", "thing", "things", "yet", "still", "too", "also"
    )

    /** 候选中文过多 = 太泛的词（good → 好/善/良…），命中是噪声，直接不参与评分。 */
    private val maxCandidates = 200

    @Test
    fun validateAgainstHumanJudgments() {
        val root = findRoot()
        assumeTrue("缺少 artifacts 下的评估素材，跳过", root != null)
        val artifacts = File(root!!, "artifacts")
        val cedict = File(artifacts, "generalization/cedict.txt.gz")
        val fixtureFile = File(artifacts, "alignment-eval/golden-samples.json")
        assumeTrue("缺少 CC-CEDICT（见 fetch-semantic-proxy-corpus.sh），跳过", cedict.isFile)
        assumeTrue("缺少金标准 fixture，跳过", fixtureFile.isFile)

        val started = System.currentTimeMillis()
        val index = buildIndex(cedict)
        println("[proxy] CC-CEDICT 索引: ${index.size} 个英文词条，耗时 ${System.currentTimeMillis() - started}ms")

        val samples = loadSamples(fixtureFile)
        println("[proxy] 可评分样本=${samples.size}（ok/ok2=${samples.count { it.positive }}，bad=${samples.count { !it.positive }}）")

        val proxyScores = samples.associate { it.id to scoreProxy(index, it.en, it.zh) }
        val lengthScores = samples.associate { it.id to scoreLengthRatio(it.en, it.zh) }
        val anchorScores = samples.associate { it.id to scoreAnchorOverlap(it.en, it.zh) }

        report("语义代理(CC-CEDICT 内容词命中率)", samples, proxyScores)
        report("内部信号(长度比贴近 1)", samples, lengthScores)
        report("内部信号(数字/拉丁锚点重叠)", samples, anchorScores)

        // 对照：把三个信号按「ok/ok2 vs bad」的分离度排个队，决定值不值得依赖
        val proxyAuc = auc(samples, proxyScores)
        val lengthAuc = auc(samples, lengthScores)
        val anchorAuc = auc(samples, anchorScores)
        println("[proxy] AUC  语义代理=%.3f  长度比=%.3f  锚点=%.3f".format(proxyAuc, lengthAuc, anchorAuc))
        println(
            "[proxy] 结论：" + when {
                proxyAuc >= 0.80 -> "外部信号可用——可作回归报警与主动采样排序（阈值见上表）"
                proxyAuc >= 0.65 -> "弱可用——只能当粗筛/报警，不能单独定质量"
                else -> "不可用——与人工判定分离度不足，别依赖它（要么换句向量模型，要么维持人工判定）"
            }
        )

        // 用途一：整本分布当回归门基线；用途二：按分数排出「嫌疑样本」给下一轮人工
        reportBookLevel(index, File(artifacts, "alignment-eval/pairs-sample.json"))
        reportActiveSampling(samples, proxyScores)
    }

    /** 用途一：整本句对抽样分布——均值/分位数就是回归门基线，漂移即报警。 */
    private fun reportBookLevel(index: Map<String, Set<String>>, file: File) {
        if (!file.isFile) {
            println("[proxy] 没有 pairs-sample.json（先跑 TranslationGoldenReplayTest），跳过整本分布")
            return
        }
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("pairs")
        val scores = (0 until array.length()).map {
            val pair = array.getJSONObject(it)
            scoreProxy(index, pair.getString("en"), pair.getString("zh"))
        }
        val sorted = scores.sorted()
        fun percentile(p: Double) = sorted[(p * (sorted.size - 1)).toInt()]
        println(
            "[proxy] 整本分布(抽样 %d/%d): 均值=%.3f 中位=%.3f p10=%.3f p25=%.3f p75=%.3f".format(
                scores.size, root.optInt("totalSentencePairs"),
                scores.average(), percentile(0.5), percentile(0.1), percentile(0.25), percentile(0.75)
            )
        )
        println(
            "[proxy] 回归门基线：整本均值 / p25 相对上面数值漂移超过 0.03 即报警（本行数值就是当前基线）"
        )
    }

    /** 用途二：把「人工判对、代理却存疑」的样本排在最前面——下一轮人工优先复看这批。 */
    private fun reportActiveSampling(samples: List<Sample>, scores: Map<String, Double>) {
        val suspects = samples.filter { it.positive }.sortedBy { scores.getValue(it.id) }.take(10)
        println("[proxy] 主动采样：代理得分最低的 10 条 ok/ok2 样本（下一轮优先复看）")
        suspects.forEach {
            println(
                "  %.3f  %-5s EN=«%s»".format(scores.getValue(it.id), it.id, it.en.take(60))
            )
        }
    }

    // ---- 信号 ----

    /** 外部信号：英文句内容词的中文候选是否出现在中文展示里（命中率，越低越可疑）。 */
    private fun scoreProxy(index: Map<String, Set<String>>, en: String, zh: String): Double {
        if (zh.isBlank()) return 0.0
        val words = contentWords(en)
        if (words.isEmpty()) return 0.0
        var hit = 0.0
        var total = 0.0
        for (word in words) {
            val candidates = index[word] ?: continue
            total += 1.0
            if (candidates.any { it in zh }) hit += 1.0
        }
        return if (total == 0.0) 0.0 else hit / total
    }

    /** 内部信号 A：长度比越贴近 1 越好（展示是整段时天然偏低，这正是它的局限）。 */
    private fun scoreLengthRatio(en: String, zh: String): Double {
        val enWords = en.split(Regex("\\s+")).count { it.isNotBlank() }
        val zhChars = zh.count { !it.isWhitespace() }
        if (enWords == 0 || zhChars == 0) return 0.0
        val ratio = zhChars / (enWords * 1.7)
        return 1.0 / (1.0 + abs(ln(ratio.coerceAtLeast(0.01))))
    }

    /** 内部信号 B：英文句里的数字/拉丁专名有多少出现在中文展示里。 */
    private fun scoreAnchorOverlap(en: String, zh: String): Double {
        val anchors = Regex("[0-9]+|[A-Z][a-z]+|[A-Z]{2,}").findAll(en)
            .map { it.value.lowercase() }.filter { it.length >= 2 }.toSet()
        if (anchors.isEmpty()) return 0.5 // 无锚点可判，给中间分
        return anchors.count { it in zh.lowercase() }.toDouble() / anchors.size
    }

    private fun contentWords(en: String): Set<String> =
        enWord.findAll(en.lowercase()).map { it.value }.filter { it !in stopwords }.toSet()

    // ---- 指标 ----

    private class Sample(val id: String, val en: String, val zh: String, val positive: Boolean)

    private fun loadSamples(fixture: File): List<Sample> {
        val array = JSONObject(fixture.readText()).getJSONArray("samples")
        val result = mutableListOf<Sample>()
        for (i in 0 until array.length()) {
            val sample = array.getJSONObject(i)
            if (sample.optBoolean("garbage", false)) continue
            val verdict = sample.optString("verdict")
            val approved = sample.optJSONObject("approved") ?: continue
            val zh = approved.optString("zh")
            if (zh.isBlank()) continue
            if (verdict !in setOf("ok", "ok2", "bad")) continue
            result += Sample(sample.getString("id"), sample.optString("en"), zh, verdict != "bad")
        }
        return result
    }

    private fun auc(samples: List<Sample>, scores: Map<String, Double>): Double {
        val pos = samples.filter { it.positive }.map { scores.getValue(it.id) }
        val neg = samples.filter { !it.positive }.map { scores.getValue(it.id) }
        if (pos.isEmpty() || neg.isEmpty()) return Double.NaN
        var wins = 0.0
        for (p in pos) for (n in neg) {
            wins += when {
                p > n -> 1.0
                p < n -> 0.0
                else -> 0.5
            }
        }
        return wins / (pos.size.toDouble() * neg.size)
    }

    private fun report(label: String, samples: List<Sample>, scores: Map<String, Double>) {
        val pos = samples.filter { it.positive }.map { scores.getValue(it.id) }
        val neg = samples.filter { !it.positive }.map { scores.getValue(it.id) }
        println(
            "[proxy] %-32s ok/ok2 均值=%.3f 中位=%.3f | bad 均值=%.3f 中位=%.3f | AUC=%.3f"
                .format(label, pos.average(), pos.sorted()[pos.size / 2], neg.average(), neg.sorted()[neg.size / 2], auc(samples, scores))
        )
        // 阈值表：把「低于阈值判为坏」当作报警器，看各阈值下的精确率/召回率
        val thresholds = listOf(0.2, 0.3, 0.4, 0.5, 0.6, 0.7)
        val cells = thresholds.joinToString("  ") { t ->
            val flagged = samples.filter { scores.getValue(it.id) < t }
            val trueBad = flagged.count { !it.positive }
            val precision = if (flagged.isEmpty()) 0.0 else trueBad.toDouble() / flagged.size
            val recall = if (neg.isEmpty()) 0.0 else trueBad.toDouble() / neg.size
            "t=%.1f(%.2f/%.2f)".format(t, precision, recall)
        }
        println("[proxy] %-32s 报警精确率/召回率: %s".format(label, cells))
    }

    // ---- CC-CEDICT ----

    /** `繁 简 [pin1 yin1] /gloss1/gloss2/` → 英文 gloss 内容词 → 简体词条集合。 */
    private fun buildIndex(file: File): Map<String, Set<String>> {
        val index = HashMap<String, MutableSet<String>>()
        GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val open = line.indexOf('[')
                val close = line.indexOf(']')
                if (open < 0 || close < open) continue
                val head = line.substring(0, open).trim().split(' ')
                if (head.size < 2) continue
                val simplified = head[1]
                val glossPart = line.substring(close + 1).trim()
                if (!glossPart.startsWith("/")) continue
                for (gloss in glossPart.split('/')) {
                    if (gloss.isBlank()) continue
                    for (word in enWord.findAll(gloss.lowercase())) {
                        if (word.value in stopwords) continue
                        val bucket = index.getOrPut(word.value) { HashSet() }
                        if (bucket.size <= maxCandidates) bucket.add(simplified)
                    }
                }
            }
        }
        return index.mapValues { it.value.toSet() }.filterValues { it.size <= maxCandidates }
    }

    private fun findRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/alignment-eval/golden-samples.json").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }
}
