package com.linguareader.app.translation

import com.linguareader.shared.translation.TraditionalSimplified
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * CC-CEDICT 语义代理索引（**仅测试工具使用**，生产代码不依赖）。
 *
 * 把社区维护的 CC-CEDICT 反查成「英文内容词 → 候选简体词」表，用来给一组中英对照
 * 打一个**外部**语义分数：英文句的内容词，其中文候选有多少出现在中文展示里。
 * 之所以必须是外部资源：对齐器自己的信号（长度比/锚点/词义加分）量的是「DP 有没有
 * 优化自己的目标函数」，是循环论证。
 *
 * 共用方：
 *  - [TranslationSemanticProxyTool]：拿人工判定的 100 条样本做 ROC/AUC 验证；
 *  - [TranslationJudgmentCardTool]：按分数给判定卡排序（越低越可疑，人工先看）。
 */
object SemanticProxyIndex {

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

    /**
     * P3 评分变体：候选来源（全部释义 / 仅首释义）、候选最短字数、候选上限、是否按
     * 特异性加权。默认值是**在圣经 2,268 条机械真值上选出来的**（见 README §6 变体表）。
     */
    data class Config(
        /** 只用词条的第一个释义（主释义）——降多义噪声，但会掉覆盖。 */
        val primaryGlossOnly: Boolean = false,
        /** 候选中文最短字数：1 保留单字（好/大），2 只保留多字词（更具体）。 */
        val minCandidateChars: Int = 1,
        /**
         * 候选中文过多 = 太泛的词（good → 好/善/良…），超过这个数就不收。
         * P3 实测：放宽到 5000 + 加权只在共同子集上小胜（圣经 0.821→0.833、金标准
         * 0.881→0.883，约 1 个标准误），且新增的低区分度样本会稀释报警口径 →
         * **不采用**，保留 200 硬过滤。见 README §6 变体表。
         */
        val maxCandidates: Int = 200,
        /**
         * 是否按「英文词特异性」加权：候选越少权重越高（`1/ln(2+n)`），
         * 取代「每词一票」——`troll→巨魔` 该比 `good→好/善/良` 更有说服力。
         */
        val idf: Boolean = false
    )

    /**
     * 可评分内容词下限：低于这个数的句子「命中率」没有统计意义（1 个词命中就是 1.0、
     * 不命中就是 0.0），主动采样里排在最前面的全是这类短对白。低于下限的样本标
     * [Score.scoreable] = false，不参与 AUC / 报警 / 排序，只在报告里单列。
     */
    const val MIN_SCORABLE_WORDS = 3

    /** Wilson 95% 置信区间下界用的 z 值。 */
    private const val WILSON_Z = 1.96

    /**
     * 一次评分的完整结果。
     * @param hitRate 原始命中率（英文内容词中，中文候选出现在展示里的比例）
     * @param scorableWords 真正在 CC-CEDICT 里查到候选的英文内容词数（分母）
     * @param wilsonLower 命中率的 Wilson 95% 下界——1/1 只有 0.21，不再是「满分」
     * @param scoreable 证据是否够（[MIN_SCORABLE_WORDS]）
     */
    data class Score(
        val hitRate: Double,
        val scorableWords: Int,
        val wilsonLower: Double,
        val scoreable: Boolean
    ) {
        /**
         * 排序/报警用的分数：够证据时用原始命中率（金标准集实测 AUC 0.901，高于
         * Wilson 下界的 0.877——证据门槛已经把薄样本剔掉了，Wilson 只会压缩区间）；
         * 证据不足时排到最后（1.0），而不是当最可疑。
         */
        val rank: Double get() = if (scoreable) hitRate else 1.0
    }

    /**
     * 建好的索引：英文内容词 → 候选简体词，外加（可选）每个词的特异性权重。
     * [weights] 为空表示「每词一票」。
     */
    class Index(
        val candidates: Map<String, Set<String>>,
        val weights: Map<String, Double>
    ) {
        /** 收录的英文词条数（报告用）。 */
        val size: Int get() = candidates.size

        fun weightOf(word: String): Double = weights[word] ?: 1.0
    }

    /** `繁 简 [pin1 yin1] /gloss1/gloss2/` → 英文 gloss 内容词 → 简体词条集合。 */
    fun build(file: File, config: Config = Config()): Index {
        val buckets = HashMap<String, MutableSet<String>>()
        val entryCounts = HashMap<String, Int>()
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
                val glosses = if (config.primaryGlossOnly) {
                    listOf(glossPart.split('/').firstOrNull { it.isNotBlank() } ?: "")
                } else {
                    glossPart.split('/')
                }
                // 同一个词条里同一个英文词只算一次（多释义重复出现不重复计数）
                val wordsOfEntry = HashSet<String>()
                for (gloss in glosses) {
                    if (gloss.isBlank()) continue
                    for (word in enWord.findAll(gloss.lowercase())) {
                        if (word.value in stopwords) continue
                        wordsOfEntry += word.value
                        if (simplified.length < config.minCandidateChars) continue
                        val bucket = buckets.getOrPut(word.value) { HashSet() }
                        if (bucket.size <= config.maxCandidates) bucket.add(simplified)
                    }
                }
                for (word in wordsOfEntry) entryCounts[word] = (entryCounts[word] ?: 0) + 1
            }
        }
        val candidates = buckets.mapValues { it.value.toSet() }
            .filterValues { it.size <= config.maxCandidates }
        val weights = if (!config.idf) emptyMap()
        else candidates.keys.associateWith { 1.0 / kotlin.math.ln(2.0 + (entryCounts[it] ?: 1)) }
        return Index(candidates, weights)
    }

    /** 英文句内容词的中文候选是否出现在中文展示里（命中率，越低越可疑）。 */
    fun score(index: Index, en: String, zh: String): Double = evaluate(index, en, zh).hitRate

    /** 完整评分（命中率 + 证据量 + Wilson 下界）；短句证据不足时 [Score.scoreable] = false。 */
    fun evaluate(index: Index, en: String, zh: String): Score {
        if (zh.isBlank()) return Score(0.0, 0, 0.0, false)
        // CC-CEDICT 候选是简体；评估语料（和合本、朱譯魔戒）是繁体，不归一会把
        // 命中率整体压低（P2 大规模验证暴露：圣经同节样本均值只有 0.11）。归一
        // 用的是对齐器同一张繁→简表（`:shared` 的 TraditionalSimplified）。
        val zhSimplified = TraditionalSimplified.toSimplified(zh)
        val words = contentWords(en)
        if (words.isEmpty()) return Score(0.0, 0, 0.0, false)
        var hit = 0.0
        var total = 0.0
        var scorable = 0
        for (word in words) {
            val candidates = index.candidates[word] ?: continue
            val weight = index.weightOf(word)
            total += weight
            scorable++
            if (candidates.any { it in zhSimplified }) hit += weight
        }
        if (scorable == 0) return Score(0.0, 0, 0.0, false)
        return Score(
            hitRate = hit / total,
            scorableWords = scorable,
            wilsonLower = wilsonLower(hit, total),
            scoreable = scorable >= MIN_SCORABLE_WORDS
        )
    }

    /** 命中数 k / 可评分词数 n 的 Wilson 95% 下界（小样本不再给出虚假的高分）。 */
    fun wilsonLower(hits: Double, total: Double): Double {
        if (total <= 0.0) return 0.0
        val p = hits / total
        val z2 = WILSON_Z * WILSON_Z
        val denom = 1.0 + z2 / total
        val center = (p + z2 / (2.0 * total)) / denom
        val half = WILSON_Z * kotlin.math.sqrt(p * (1.0 - p) / total + z2 / (4.0 * total * total)) / denom
        return (center - half).coerceIn(0.0, 1.0)
    }

    fun contentWords(en: String): Set<String> =
        enWord.findAll(en.lowercase()).map { it.value }.filter { it !in stopwords }.toSet()
}
