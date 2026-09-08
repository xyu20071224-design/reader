package com.linguareader.shared.translation

/**
 * 合成双语语料：**句对真值由构造即知**，用来在 CI 里机械地量「对齐正确率」。
 *
 * 真实图书的对齐质量只能靠人工判定（见 `src/tools/alignment-eval/`）；合成语料的价值是
 * 免人工、免版权、可无限注入噪声——每句嵌一个唯一编号，编号在中英两侧同时出现，
 * 于是「这一对配得对不对」不需要人看，直接比对编号即可。
 *
 * 全部内容由固定词表按下标组合而成，**无随机数**：同一版本永远生成同一份语料，
 * 指标变化只可能来自对齐器改动。
 */
internal object SyntheticBilingualCorpus {

    private val EN_SUBJECTS = listOf(
        "the elder", "the hunter", "the smith", "the singer",
        "the farmer", "the sailor", "the healer", "the scribe"
    )
    private val EN_VERBS = listOf(
        "spoke", "remembered", "described", "followed",
        "watched", "answered", "waited", "glimpsed"
    )
    private val EN_OBJECTS = listOf(
        "the river", "the mountain", "the city", "the sea",
        "the harvest", "the harbour", "the forest", "the tower"
    )

    private val ZH_SUBJECTS = listOf(
        "那长者", "那猎人", "那歌者", "那水手",
        "那商人", "那客人", "那学者", "那旅人"
    )
    private val ZH_VERBS = listOf(
        "说起了", "想起了", "描述了", "跟随了",
        "注视着", "回答了", "等待着", "望见了"
    )
    private val ZH_OBJECTS = listOf(
        "那条河", "那座山", "那座城", "那片海",
        "那次收获", "那处港口", "那座森林", "那座塔"
    )

    /**
     * 假词典（en 词 → 中文义项短语）：只覆盖上面词表里的实义词，用来在 CI 里
     * 跑通 V2 词义锚点路径（真 ECDICT 只在 `:app` 的 Robolectric 测试里）。
     */
    val MEANING_DICTIONARY: Map<String, Set<String>> = buildMap {
        val enHeads = EN_SUBJECTS.map { it.removePrefix("the ") } + EN_VERBS +
            EN_OBJECTS.map { it.removePrefix("the ") }
        val zhHeads = ZH_SUBJECTS.map { it.removePrefix("那") } + ZH_VERBS +
            ZH_OBJECTS.map { it.removePrefix("那") }
        enHeads.forEachIndexed { i, en -> put(en, setOf(zhHeads[i])) }
    }

    /** 语料自带的「繁→简」替换（只含 `TraditionalSimplified` 确认收录的字）。 */
    private val TRADITIONAL = mapOf(
        '长' to '長', '猎' to '獵', '说' to '說', '条' to '條',
        '学' to '學', '获' to '獲', '处' to '處', '见' to '見'
    )

    class Book(
        val enChapters: List<List<String>>,
        val zhChapters: List<List<String>>,
        /** 扁平化的句序真值：第 i 条句级句对应等于 enSentences[i] / zhSentences[i]。 */
        val enSentences: List<String>,
        val zhSentences: List<String>,
        /** 全部句编号（去掉编号的语料里为空集）。 */
        val markers: Set<String>
    )

    /**
     * @param traditional 中文侧是否改写为繁体（只替换 [TRADITIONAL] 里的字）
     * @param markerAt 第 i 句的编号；返回 null 表示该句不带编号（只剩词义锚点可用）
     */
    fun build(
        chapters: Int = 5,
        paragraphsPerChapter: Int = 4,
        sentencesPerParagraph: Int = 3,
        traditional: Boolean = false,
        markerAt: (Int) -> String? = { "%04d".format(it + 1) }
    ): Book {
        val enChapters = mutableListOf<List<String>>()
        val zhChapters = mutableListOf<List<String>>()
        val enSentences = mutableListOf<String>()
        val zhSentences = mutableListOf<String>()
        val markers = mutableSetOf<String>()
        var index = 0
        for (chapter in 0 until chapters) {
            val enParagraphs = mutableListOf<String>()
            val zhParagraphs = mutableListOf<String>()
            for (paragraph in 0 until paragraphsPerChapter) {
                val enLines = mutableListOf<String>()
                val zhLines = mutableListOf<String>()
                for (sentence in 0 until sentencesPerParagraph) {
                    val i = index++
                    val subject = i % EN_SUBJECTS.size
                    val verb = (i / EN_SUBJECTS.size) % EN_VERBS.size
                    // 5 与 8 互素 → (subject, verb, object) 在 i < 64 内唯一
                    val obj = (i * 5) % EN_OBJECTS.size
                    val marker = markerAt(i)
                    val en = buildString {
                        append(EN_SUBJECTS[subject]).append(' ')
                        append(EN_VERBS[verb]).append(" of ")
                        append(EN_OBJECTS[obj])
                        if (marker != null) append(' ').append(marker)
                        append('.')
                    }
                    var zh = buildString {
                        append(ZH_SUBJECTS[subject])
                        append(ZH_VERBS[verb])
                        append(ZH_OBJECTS[obj])
                        if (marker != null) append("，编号").append(marker)
                        append('。')
                    }
                    if (traditional) zh = zh.map { TRADITIONAL[it] ?: it }.joinToString("")
                    if (marker != null) markers += marker
                    enLines += en
                    zhLines += zh
                    enSentences += en
                    zhSentences += zh
                }
                enParagraphs += enLines.joinToString(" ")
                zhParagraphs += zhLines.joinToString("")
            }
            enChapters += enParagraphs
            zhChapters += zhParagraphs
        }
        return Book(enChapters, zhChapters, enSentences, zhSentences, markers)
    }

    /** 语料对应的假词义索引。 */
    fun meaningIndex(): MeaningIndex = object : MeaningIndex {
        override fun phrasesOf(word: String): Set<String> =
            MEANING_DICTIONARY[word.lowercase()] ?: emptySet()
    }
}
