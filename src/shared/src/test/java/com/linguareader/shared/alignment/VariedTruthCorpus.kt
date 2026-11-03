package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter


/**
 * **长度有方差、真值已知**的语料（消融实验用）。
 *
 * 构造方式：句长在一个**确定的循环模式**里变化（3 词、7 词、12 词、20 词、25 词…），
 * 中英严格 1:1，每句带唯一编码（英文伪词 / 中文罕用字），配对真值由下标直接给出。
 *
 * 与 `SyntheticBilingualFixture` 的关键差别只有一个：**长度有方差**。这一条差别正好
 * 决定 `margin` 是否可用（等长时任意交换代价相同 → margin=0 → 按设计必须弃权）。
 */
internal class VariedTruthCorpus private constructor(
    val source: List<AlignmentChapter>,
    val translation: List<AlignmentChapter>,
    val truth: Truth,
    val vocabulary: Map<String, String>
) {
    val paragraphCount: Int = source.sumOf { it.paragraphs.size }
    val chapterCount: Int = source.size
    val sourceSentences: Int = truth.keys.size

    /** 真值键（英文句编码）→ 该句的中文文本。 */
    class Truth(
        val keys: List<String>,
        val targetUnits: List<TargetUnit>,
        val markerToUnit: Map<String, Int>
    )

    /** 投影/算指标用的真值表（`AlignmentTruth` 不可变，每次新建即可）。 */
    fun alignmentTruth(): AlignmentTruth = AlignmentTruth(
        sourceKeysInOrder = truth.keys,
        targetUnits = truth.targetUnits,
        markerToUnit = truth.markerToUnit,
        sourceMarkerUniverse = truth.keys.toSet(),
        targetMarkerUniverse = truth.markerToUnit.keys.toSet()
    )

    /** 稀疏词典：只覆盖 [vocabulary] 里约 20 个词。 */
    fun sparseProvider(): MeaningProvider {
        val subset = vocabulary.entries.take(20).associate { it.key to it.value }
        return MeaningProvider { word -> subset[word.lowercase()]?.let { listOf(it) } ?: emptyList() }
    }

    /**
     * 完美词典：每个英文实词 → 它**在真值里**对应的中文译文片段（2–4 字）。
     *
     * 这是上界：词典 100% 正确且 100% 覆盖。它与 ECDICT 的唯一差别是「多义词被真值唯一化」。
     */
    fun oracleProvider(): MeaningProvider {
        val mapping = HashMap<String, List<String>>()
        keysToChinese.forEach { (key, chinese) ->
            val fragments = chineseFragments(chinese)
            for (word in wordsOf(key)) {
                val existing = mapping[word]
                mapping[word] = if (existing == null) fragments else (existing + fragments).distinct()
            }
        }
        return MeaningProvider { word -> mapping[word.lowercase()] ?: emptyList() }
    }

    /** 编码键 → 中文原文（构造时记下，供 oracle 词典用）。 */
    private val keysToChinese: Map<String, String>
        get() = truth.targetUnits.associate { unit ->
            unit.sourceKeys.first() to unit.text
        }

    private fun wordsOf(key: String): List<String> {
        val sentence = source.flatMap { it.paragraphs }
            .flatMap { SentenceSplitter.split(it) }
            .firstOrNull { it.contains("\u00AB$key\u00BB") } ?: return emptyList()
        return englishWords(sentence).filter { it.length >= 3 && it !in FUNCTION_WORDS }
    }

    private fun chineseFragments(text: String): List<String> {
        val cjk = text.filter { TextScan.isCjk(it) }
        if (cjk.length < 2) return emptyList()
        val result = LinkedHashSet<String>()
        for (len in 2..4) {
            var from = 0
            while (from + len <= cjk.length) {
                result.add(cjk.substring(from, from + len))
                from++
            }
        }
        return result.toList()
    }

    companion object {
        private val FUNCTION_WORDS = setOf(
            "the", "and", "for", "with", "from", "that", "this", "was", "were", "are",
            "his", "her", "its", "they", "them", "their", "there", "here", "have", "has",
            "had", "will", "would", "could", "should", "not", "but", "you", "our", "who"
        )

        /** 五种句长（词数）：让相邻句子的长度显著不同，`margin` 才有区分力。 */
        private val EN_TEMPLATES = listOf(
            // 3 词
            listOf("He", "nodded", "again"),
            // 7 词
            listOf("The", "elder", "remembered", "the", "grey", "river", "warmly"),
            // 12 词
            listOf(
                "The", "hunter", "described", "the", "high", "mountain", "and", "the",
                "cold", "wind", "quite", "carefully"
            ),
            // 20 词
            listOf(
                "By", "the", "time", "the", "smith", "had", "followed", "the", "old",
                "city", "road", "down", "to", "the", "harbour", "gate", "the", "autumn",
                "sun", "set"
            ),
            // 25 词
            listOf(
                "The", "singer", "watched", "the", "wide", "grey", "sea", "from", "the",
                "tower", "wall", "for", "a", "long", "while", "and", "then", "turned",
                "away", "without", "a", "single", "spoken", "word", "afterward"
            )
        )

        /** 与 [EN_TEMPLATES] 一一对应的中文（长度也随之变化，密度保持一致）。 */
        private val ZH_TEMPLATES = listOf(
            "他又点了点头。",
            "那长者温暖地想起了那条灰色的河，心里很静。",
            "那猎人极仔细地描述了那座高山，还有那阵冷风，一件一件说得很清楚。" +
                "他说完之后便不再开口。",
            "等到那铁匠沿着那条旧城的道路一直跟到港口的大门时，秋天的太阳已经落下，" +
                "天边只剩下一片很淡的红光，连鸟也不再叫了。",
            "那歌者从那座塔的墙上望着那片宽而灰的海，望了很久很久，然后转过身去，" +
                "一句话也没有说，只是慢慢地走下了那道长长的石阶，回到了自己的屋子里。"
        )

        private const val CHAPTERS = 4
        private const val PARAGRAPHS_PER_CHAPTER = 5
        private const val SENTENCES_PER_PARAGRAPH = 4

        /** 12 个罕用汉字作中文编码（避免与正文撞字）。 */
        private val ZH_CODES = listOf(
            "叺", "丂", "丄", "乂", "仉", "厹", "夬", "奂", "屮", "巛", "彳", "恏"
        )

        /** 12 个伪音节作英文编码。 */
        private val EN_CODES = listOf(
            "zqk", "vur", "nif", "gle", "drax", "mub", "phet", "jox", "civ", "wam", "ryu", "kob"
        )

        fun codeEn(index: Int): String {
            val base = EN_CODES.size
            return listOf(index % base, (index / base) % base, (index / (base * base)) % base)
                .joinToString(" ") { EN_CODES[it] }
        }

        fun codeZh(index: Int): String {
            val base = ZH_CODES.size
            return buildString {
                append(ZH_CODES[index % base])
                append(ZH_CODES[(index / base) % base])
                append(ZH_CODES[(index / (base * base)) % base])
            }
        }

        fun build(): VariedTruthCorpus {
            var index = 0
            val source = ArrayList<AlignmentChapter>(CHAPTERS)
            val translation = ArrayList<AlignmentChapter>(CHAPTERS)
            val keys = ArrayList<String>(CHAPTERS * PARAGRAPHS_PER_CHAPTER * SENTENCES_PER_PARAGRAPH)
            val targetUnits = ArrayList<TargetUnit>()
            val markerToUnit = HashMap<String, Int>()
            val vocabulary = LinkedHashMap<String, String>()

            for (chapter in 0 until CHAPTERS) {
                // 章长有方差：第 c 章有 (c+3) 段（3、4、5、6）
                val paragraphCount = chapter + 3
                val enParagraphs = ArrayList<String>(paragraphCount)
                val zhParagraphs = ArrayList<String>(paragraphCount)
                for (paragraph in 0 until paragraphCount) {
                    val enSentences = ArrayList<String>(SENTENCES_PER_PARAGRAPH)
                    val zhSentences = ArrayList<String>(SENTENCES_PER_PARAGRAPH)
                    for (sentence in 0 until SENTENCES_PER_PARAGRAPH) {
                        val templateIndex = index % EN_TEMPLATES.size
                        val words = EN_TEMPLATES[templateIndex]
                        val key = codeEn(index)
                        keys.add(key)
                        enSentences.add("${words.joinToString(" ")} \u00AB$key\u00BB.")
                        val chinese = ZH_TEMPLATES[templateIndex]
                        val code = codeZh(index)
                        val text = chinese.removeSuffix("。") + "，\u300C$code\u300D。"
                        zhSentences.add(text)
                        targetUnits.add(
                            TargetUnit(
                                id = targetUnits.size,
                                markers = listOf(code),
                                sourceKeys = listOf(key),
                                text = text
                            )
                        )
                        markerToUnit[code] = targetUnits.size - 1
                        // 词表：词汇 → 它在中文里的核心译法（供稀疏词典用）
                        words.forEachIndexed { slot, word ->
                            val normalized = word.lowercase()
                            if (normalized.length >= 3 && normalized !in FUNCTION_WORDS) {
                                vocabulary.putIfAbsent(normalized, coreGloss(normalized))
                            }
                        }
                        index++
                    }
                    enParagraphs.add(enSentences.joinToString(" "))
                    zhParagraphs.add(zhSentences.joinToString(""))
                }
                source.add(AlignmentChapter("Chapter ${chapter + 1}", enParagraphs))
                translation.add(AlignmentChapter("第${chapter + 1}章", zhParagraphs))
            }
            return VariedTruthCorpus(
                source = source,
                translation = translation,
                truth = Truth(keys, targetUnits, markerToUnit),
                vocabulary = vocabulary
            )
        }

        /** 词表里每个词给一个 2 字核心译法（够 V1 用即可）。 */
        private fun coreGloss(word: String): String = when (word) {
            "elder" -> "长者"
            "hunter" -> "猎人"
            "smith" -> "铁匠"
            "singer" -> "歌者"
            "remembered" -> "想起"
            "described" -> "描述"
            "followed" -> "跟随"
            "watched" -> "望着"
            "nodded" -> "点头"
            "river" -> "那条"
            "mountain" -> "那座"
            "city" -> "旧城"
            "sea" -> "那片"
            "tower" -> "塔的"
            "harbour" -> "港口"
            "autumn" -> "秋天"
            "sun" -> "太阳"
            "wind" -> "冷风"
            "carefully" -> "仔细"
            "warmly" -> "温暖"
            "grey" -> "灰色"
            "wide" -> "宽的"
            "long" -> "很久"
            "while" -> "一会"
            "turned" -> "转过"
            "away" -> "身去"
            "gate" -> "大门"
            "road" -> "道路"
            "wall" -> "墙上"
            "word" -> "句话"
            else -> word.take(2)
        }

        private fun englishWords(sentence: String): List<String> {
            val result = ArrayList<String>(8)
            var i = 0
            while (i < sentence.length) {
                if (TextScan.isLetter(sentence[i])) {
                    var j = i + 1
                    while (j < sentence.length && (TextScan.isLetter(sentence[j]) ||
                            sentence[j] == '\'' || sentence[j] == '-')
                    ) j++
                    result.add(sentence.substring(i, j).lowercase())
                    i = j
                } else i++
            }
            return result
        }
    }
}
