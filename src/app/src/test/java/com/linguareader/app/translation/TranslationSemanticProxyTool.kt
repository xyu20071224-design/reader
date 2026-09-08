package com.linguareader.app.translation

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
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
        val index = SemanticProxyIndex.build(cedict)
        println("[proxy] CC-CEDICT 索引: ${index.size} 个英文词条，耗时 ${System.currentTimeMillis() - started}ms")

        val samples = loadSamples(fixtureFile)

        // P3 变体对照：同一批人工判定样本上比 AUC（选型依据；圣经机械真值上的对照见
        // TranslationProxyBibleValidationTest 的同一张表）
        println("[proxy] P3 变体对照（金标准；共同子集 = 所有变体都能评分的样本，口径才可比）：")
        val variantScored = variants.map { (name, config) ->
            val variant = SemanticProxyIndex.build(cedict, config)
            name to samples
                .map { it to SemanticProxyIndex.evaluate(variant, it.en, it.zh) }
                .filter { it.second.scoreable }
        }
        val common = variantScored
            .map { entry -> entry.second.map { it.first.id }.toSet() }
            .reduce { a, b -> a intersect b }
        for ((name, scored) in variantScored) {
            val sub = scored.filter { it.first.id in common }
            val subAuc = auc(sub.map { it.first }, sub.associate { it.first.id to it.second.hitRate })
            val allAuc = auc(scored.map { it.first }, scored.associate { it.first.id to it.second.hitRate })
            println(
                "  %-18s 共同子集 n=%-4d AUC=%.3f | 全可评分 n=%-4d AUC=%.3f%s".format(
                    name, sub.size, subAuc, scored.size, allAuc,
                    if (variants.first { it.second == SemanticProxyIndex.Config() }.first == name) "（当前默认）" else ""
                )
            )
        }
        val scores = samples.associate { it.id to SemanticProxyIndex.evaluate(index, it.en, it.zh) }
        val scoreable = samples.filter { scores.getValue(it.id).scoreable }
        val thin = samples.filter { !scores.getValue(it.id).scoreable }
        println("[proxy] 可评分样本=${samples.size}（ok/ok2=${samples.count { it.positive }}，bad=${samples.count { !it.positive }}）")
        println(
            "[proxy] 证据门槛(≥%d 个可查词)：够=%d 条（ok/ok2=%d bad=%d）| 不足=%d 条（ok/ok2=%d bad=%d）".format(
                SemanticProxyIndex.MIN_SCORABLE_WORDS,
                scoreable.size, scoreable.count { it.positive }, scoreable.count { !it.positive },
                thin.size, thin.count { it.positive }, thin.count { !it.positive }
            )
        )

        val proxyScores = scores.mapValues { it.value.hitRate }
        val wilsonScores = scores.mapValues { it.value.wilsonLower }
        val lengthScores = samples.associate { it.id to scoreLengthRatio(it.en, it.zh) }
        val anchorScores = samples.associate { it.id to scoreAnchorOverlap(it.en, it.zh) }

        report("语义代理(原始命中率, 全部)", samples, proxyScores)
        report("语义代理(原始命中率, 仅够证据)", scoreable, proxyScores)
        report("语义代理(Wilson 下界, 仅够证据)", scoreable, wilsonScores)
        report("内部信号(长度比贴近 1)", samples, lengthScores)
        report("内部信号(数字/拉丁锚点重叠)", samples, anchorScores)

        // 对照：把信号按「ok/ok2 vs bad」的分离度排个队，决定值不值得依赖
        val proxyAuc = auc(samples, proxyScores)
        val scoreableAuc = auc(scoreable, proxyScores)
        val wilsonAuc = auc(scoreable, wilsonScores)
        val lengthAuc = auc(samples, lengthScores)
        val anchorAuc = auc(samples, anchorScores)
        println(
            "[proxy] AUC  语义代理(全部=%.3f 够证据=%.3f Wilson=%.3f)  长度比=%.3f  锚点=%.3f".format(
                proxyAuc, scoreableAuc, wilsonAuc, lengthAuc, anchorAuc
            )
        )
        println(
            "[proxy] 结论：" + when {
                wilsonAuc >= 0.80 -> "外部信号可用——可作回归报警与主动采样排序（阈值见上表）"
                wilsonAuc >= 0.65 -> "弱可用——只能当粗筛/报警，不能单独定质量"
                else -> "不可用——与人工判定分离度不足，别依赖它（要么换句向量模型，要么维持人工判定）"
            }
        )

        // 用途一：整本分布当回归门基线；用途二：按分数排出「嫌疑样本」给下一轮人工
        reportBookLevel(index, File(artifacts, "alignment-eval/pairs-sample.json"))
        reportActiveSampling(scoreable, scores)
    }

    /** 用途一：整本句对抽样分布——均值/分位数就是回归门基线，漂移即报警。 */
    private fun reportBookLevel(index: SemanticProxyIndex.Index, file: File) {
        if (!file.isFile) {
            println("[proxy] 没有 pairs-sample.json（先跑 TranslationGoldenReplayTest），跳过整本分布")
            return
        }
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("pairs")
        val scores = (0 until array.length()).map {
            val pair = array.getJSONObject(it)
            SemanticProxyIndex.score(index, pair.getString("en"), pair.getString("zh"))
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

    /**
     * 用途二：把「人工判对、代理却存疑」的样本排在最前面——下一轮人工优先复看这批。
     * 只收够证据的样本：短对白句（可查词 < [SemanticProxyIndex.MIN_SCORABLE_WORDS]）
     * 之前霸占整个列表，那不是可疑，只是没法评。
     */
    private fun reportActiveSampling(
        scoreable: List<Sample>,
        scores: Map<String, SemanticProxyIndex.Score>
    ) {
        val suspects = scoreable.filter { it.positive }
            .sortedBy { scores.getValue(it.id).rank }
            .take(10)
        println("[proxy] 主动采样：代理命中率最低的 10 条 ok/ok2 样本（下一轮优先复看）")
        suspects.forEach {
            val score = scores.getValue(it.id)
            println(
                "  %.3f (k/n=%d/%d)  %-5s EN=«%s»".format(
                    score.rank, (score.hitRate * score.scorableWords).toInt(),
                    score.scorableWords, it.id, it.en.take(60)
                )
            )
        }
    }

    // ---- 信号 ----

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

    /** P3 变体清单（与 TranslationProxyBibleValidationTest 保持一致）。 */
    private val variants = listOf(
        "base(200,uniform)" to SemanticProxyIndex.Config(maxCandidates = 200, idf = false),
        "idf(200)" to SemanticProxyIndex.Config(maxCandidates = 200, idf = true),
        "idf_wide(5000)" to SemanticProxyIndex.Config(maxCandidates = 5000, idf = true),
        "primary" to SemanticProxyIndex.Config(primaryGlossOnly = true),
        "primary_idf" to SemanticProxyIndex.Config(primaryGlossOnly = true, idf = true),
        "idf_min2" to SemanticProxyIndex.Config(idf = true, minCandidateChars = 2)
    )

    private fun findRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/alignment-eval/golden-samples.json").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }
}
