package com.linguareader.shared.translation

import com.linguareader.shared.data.Book
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

/**
 * 一条对齐句对。
 *
 * 段落文本在内存里是**共享引用**（对齐器与反序列化都让同段落的多条句对指向
 * 同一个 String 实例），持久化时段落进 [TranslationMemory] 的段落表、句对只
 * 存下标 —— 否则整段文本会按句重复写出，单本书档案能膨胀到 15 MB 量级。
 */
data class AlignedSentencePair(
    val enChapter: Int,
    val zhChapter: Int,
    val enParagraph: String,
    val zhParagraph: String,
    val enSentence: String,
    val zhSentence: String,
    val confidence: Float
)

/**
 * 本书级术语表条目：英文词（已还原、小写）→ 中文译本中的稳定译法。
 *
 * **v1 恒为空**：实测「英文词 × 中文 2–4gram」共现学习在整本小说上需要约
 * 9.09M 个累加键、峰值堆 1.7 GB（512 MB 堆直接 OOM），手机上不可行，且产出被
 * 虚词噪声主导。字段保留是为了让未来版本能在不改档案格式的前提下填充。
 */
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

/**
 * 一本书的对齐档案。落盘在 `files/translation-memory/<sourceBookId>.json`。
 *
 * 格式 v2 = 段落表（`enParagraphs` / `zhParagraphs`）+ 句对只存段落下标；
 * 反序列化时把下标还原成共享的 String 引用。`fromJson` 兼容 v1（每条句对内联
 * 段落全文）的旧档案，无需重新对齐。
 */
data class TranslationMemory(
    val sourceBookId: String,
    val sourceTitle: String,
    val translationBookId: String,
    val translationTitle: String,
    val alignedAt: Long,
    val pairs: List<AlignedSentencePair>,
    val terms: List<BookTerm> = emptyList(),
    /**
     * 生成该档案时对齐器版本（[TranslationAligner.VERSION]）。
     * 旧档案没有此字段，读出即 0；重对齐后写入当前版本。
     */
    val alignerVersion: Int = 0
) {
    fun toJson(): JSONObject {
        val enParagraphs = ArrayList<String>()
        val zhParagraphs = ArrayList<String>()
        val enIndex = HashMap<String, Int>()
        val zhIndex = HashMap<String, Int>()
        val pairArray = JSONArray()
        for (pair in pairs) {
            val ep = enIndex.getOrPut(pair.enParagraph) {
                enParagraphs.add(pair.enParagraph)
                enParagraphs.size - 1
            }
            val zp = zhIndex.getOrPut(pair.zhParagraph) {
                zhParagraphs.add(pair.zhParagraph)
                zhParagraphs.size - 1
            }
            pairArray.put(
                JSONObject()
                    .put("c", pair.enChapter)
                    .put("z", pair.zhChapter)
                    .put("ep", ep)
                    .put("zp", zp)
                    .put("es", pair.enSentence)
                    .put("zs", pair.zhSentence)
                    .put("f", pair.confidence.toDouble())
            )
        }
        return JSONObject()
            .put("version", FORMAT_VERSION)
            .put("sourceBookId", sourceBookId)
            .put("sourceTitle", sourceTitle)
            .put("translationBookId", translationBookId)
            .put("translationTitle", translationTitle)
            .put("alignedAt", alignedAt)
            .put("enParagraphs", JSONArray(enParagraphs as Collection<*>))
            .put("zhParagraphs", JSONArray(zhParagraphs as Collection<*>))
            .put("pairs", pairArray)
            .put("terms", JSONArray().apply { terms.forEach { put(it.toJson()) } })
            .put("alignerVersion", alignerVersion)
    }

    companion object {
        const val FORMAT_VERSION = 2

        fun fromJson(json: JSONObject): TranslationMemory {
            val enParagraphs = stringList(json.optJSONArray("enParagraphs"))
            val zhParagraphs = stringList(json.optJSONArray("zhParagraphs"))
            val pairArray = json.optJSONArray("pairs") ?: JSONArray()
            val pairs = ArrayList<AlignedSentencePair>(pairArray.length())
            for (i in 0 until pairArray.length()) {
                val item = pairArray.optJSONObject(i) ?: continue
                // v1 档案把段落全文内联在句对里；v2 只存下标。
                val legacy = !item.has("ep")
                pairs += AlignedSentencePair(
                    enChapter = if (legacy) item.optInt("enChapter") else item.optInt("c"),
                    zhChapter = if (legacy) item.optInt("zhChapter") else item.optInt("z"),
                    enParagraph = if (legacy) item.optString("enParagraph")
                    else enParagraphs.getOrElse(item.optInt("ep", -1)) { "" },
                    zhParagraph = if (legacy) item.optString("zhParagraph")
                    else zhParagraphs.getOrElse(item.optInt("zp", -1)) { "" },
                    enSentence = if (legacy) item.optString("enSentence") else item.optString("es"),
                    zhSentence = if (legacy) item.optString("zhSentence") else item.optString("zs"),
                    confidence = (if (legacy) item.optDouble("confidence", 0.5)
                    else item.optDouble("f", 0.5)).toFloat()
                )
            }
            val termsJson = json.optJSONArray("terms")
            return TranslationMemory(
                sourceBookId = json.optString("sourceBookId"),
                sourceTitle = json.optString("sourceTitle"),
                translationBookId = json.optString("translationBookId"),
                translationTitle = json.optString("translationTitle"),
                alignedAt = json.optLong("alignedAt"),
                pairs = pairs,
                alignerVersion = json.optInt("alignerVersion"),
                terms = if (termsJson == null) emptyList()
                else (0 until termsJson.length()).mapNotNull { index ->
                    termsJson.optJSONObject(index)?.let { BookTerm.fromJson(it) }
                }
            )
        }

        private fun stringList(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { array.optString(it) }
        }
    }
}

data class TranslationLookupResult(
    val translationTitle: String,
    val english: String,
    val chinese: String,
    val chineseParagraph: String,
    val matchLevel: TranslationMatchLevel,
    val confidence: Float,
    /** 中文句内的词级对照；null 表示未对齐到词（降级句/段级）。 */
    val wordAlignment: WordAlignment? = null,
    /**
     * 该命中对应的句对在 memory.pairs 里的全局下标；句级定点重翻靠它精确定位
     * 档案条目。段落级兜底命中同样带（重译入口只对 SENTENCE 开放）。
     */
    val pairIndex: Int = -1,
    /** 命中句对所在的英文段落（重翻时给模型上下文用）。 */
    val englishParagraph: String = ""
)

data class AttachTranslationResult(
    val translationBook: Book,
    val memory: TranslationMemory
)
