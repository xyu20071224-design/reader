package com.linguareader.app.translation

import com.linguareader.app.data.Book
import org.json.JSONArray
import org.json.JSONObject

enum class TranslationMatchLevel { SENTENCE, PARAGRAPH }

/** 词级对照的来源：锚点直配（数字/拉丁专名）或词典辅助匹配。 */
enum class WordAlignmentSource { DICTIONARY, ANCHOR }

/**
 * 中文句内「对应词/短语」的定位结果。
 * [start] / [endExclusive] 是中文字符偏移（相对于 [TranslationLookupResult.chinese]）。
 */
data class WordAlignment(
    val word: String,
    val start: Int,
    val endExclusive: Int,
    val confidence: Float,
    val source: WordAlignmentSource
)

/** One aligned sentence pair inside the per-book translation memory. */
data class AlignedSentencePair(
    val enChapter: Int,
    val zhChapter: Int,
    val enParagraph: String,
    val zhParagraph: String,
    val enSentence: String,
    val zhSentence: String,
    val confidence: Float
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enChapter", enChapter)
        .put("zhChapter", zhChapter)
        .put("enParagraph", enParagraph)
        .put("zhParagraph", zhParagraph)
        .put("enSentence", enSentence)
        .put("zhSentence", zhSentence)
        .put("confidence", confidence.toDouble())

    companion object {
        fun fromJson(json: JSONObject) = AlignedSentencePair(
            enChapter = json.optInt("enChapter"),
            zhChapter = json.optInt("zhChapter"),
            enParagraph = json.optString("enParagraph"),
            zhParagraph = json.optString("zhParagraph"),
            enSentence = json.optString("enSentence"),
            zhSentence = json.optString("zhSentence"),
            confidence = json.optDouble("confidence", 0.5).toFloat()
        )
    }
}



/**
 * 本书级术语表条目：英文词（已还原、小写）→ 中文译本中的稳定译法。
 * 由 [TermLexiconLearner] 在 attach 对齐时一次性学习，查询时作候选偏好源。
 */
data class TranslationMemory(
    val sourceBookId: String,
    val sourceTitle: String,
    val translationBookId: String,
    val translationTitle: String,
    val alignedAt: Long,
    val pairs: List<AlignedSentencePair>,
    /** 本书级术语表（attach 时学习）；旧档默认空，不强制重对齐。 */
    val terms: List<BookTerm> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sourceBookId", sourceBookId)
        .put("sourceTitle", sourceTitle)
        .put("translationBookId", translationBookId)
        .put("translationTitle", translationTitle)
        .put("alignedAt", alignedAt)
        .put("pairs", JSONArray().apply { pairs.forEach { put(it.toJson()) } })
        .put("terms", JSONArray().apply { terms.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(json: JSONObject): TranslationMemory {
            val pairArray = json.getJSONArray("pairs")
            val termsJson = json.optJSONArray("terms")
            return TranslationMemory(
                sourceBookId = json.optString("sourceBookId"),
                sourceTitle = json.optString("sourceTitle"),
                translationBookId = json.optString("translationBookId"),
                translationTitle = json.optString("translationTitle"),
                alignedAt = json.optLong("alignedAt"),
                pairs = (0 until pairArray.length()).map {
                    AlignedSentencePair.fromJson(pairArray.getJSONObject(it))
                },
                terms = if (termsJson == null) emptyList() else
                    (0 until termsJson.length()).map {
                        BookTerm.fromJson(termsJson.getJSONObject(it))
                    }
            )
        }
    }
}

data class BookTerm(
    val enWord: String,
    val zhTerm: String,
    val count: Int,
    val confidence: Float
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enWord", enWord)
        .put("zhTerm", zhTerm)
        .put("count", count)
        .put("confidence", confidence.toDouble())

    companion object {
        fun fromJson(json: JSONObject) = BookTerm(
            enWord = json.optString("enWord"),
            zhTerm = json.optString("zhTerm"),
            count = json.optInt("count"),
            confidence = json.optDouble("confidence", 0.5).toFloat()
        )
    }
}

data class TranslationLookupResult(
    val translationTitle: String,
    val english: String,
    val chinese: String,
    val chineseParagraph: String,
    val matchLevel: TranslationMatchLevel,
    val confidence: Float,
    /** 第二期：中文句内的词级对照；null 表示未对齐到词（降级句/段级）。 */
    val wordAlignment: WordAlignment? = null
)

data class AttachTranslationResult(
    val translationBook: Book,
    val memory: TranslationMemory
)
