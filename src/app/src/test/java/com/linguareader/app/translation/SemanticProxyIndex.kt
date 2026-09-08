package com.linguareader.app.translation

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

    /** 候选中文过多 = 太泛的词（good → 好/善/良…），命中是噪声，直接不参与评分。 */
    private const val maxCandidates = 200

    /** `繁 简 [pin1 yin1] /gloss1/gloss2/` → 英文 gloss 内容词 → 简体词条集合。 */
    fun build(file: File): Map<String, Set<String>> {
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

    /** 英文句内容词的中文候选是否出现在中文展示里（命中率，越低越可疑）。 */
    fun score(index: Map<String, Set<String>>, en: String, zh: String): Double {
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

    fun contentWords(en: String): Set<String> =
        enWord.findAll(en.lowercase()).map { it.value }.filter { it !in stopwords }.toSet()
}
