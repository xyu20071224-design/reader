package com.linguareader.shared.translation

import com.linguareader.shared.data.ContextualDictionaryEntry
import com.linguareader.shared.data.DictionarySense
import com.linguareader.shared.data.PartOfSpeech

/** 查词面板中一条有明确词条来源的释义。 */
data class TranslationSenseRow(
    val sense: DictionarySense,
    val headword: String,
    val aligned: Boolean,
    val supplemental: Boolean = false
)

/**
 * 有界的释义呈现投影：保留当前词条前八义项，必要时追加一条明确来源的高亮释义。
 * 追加行可能来自回退单词，不能把它冠以当前短语词条的身份。
 */
object TranslationSensePresentation {
    const val MAX_PRIMARY_SENSES = 8

    fun rows(entry: ContextualDictionaryEntry?, alignment: WordAlignment?): List<TranslationSenseRow> {
        val source = alignment?.takeIf {
            it.source == WordAlignmentSource.DICTIONARY &&
                !it.sourceSense.isNullOrBlank() && !it.sourceHeadword.isNullOrBlank()
        }
        val primarySenses = entry?.senses.orEmpty().take(MAX_PRIMARY_SENSES)
        val alignedIndex = if (source?.sourceHeadword == entry?.headword && source != null) {
            primarySenses.indexOfFirst { it.text == source.sourceSense }
        } else -1
        val rows = primarySenses.mapIndexed { index, sense ->
            TranslationSenseRow(sense, entry!!.headword, aligned = index == alignedIndex)
        }
        if (source == null || alignedIndex >= 0) return rows

        // 同词条的第九条之后沿用真实 DictionarySense；回退词条只有仓库给出的来源
        // 身份与原始义项文本可用，不从当前词条猜测词性或语境偏好。
        val actualSense = entry?.takeIf { it.headword == source.sourceHeadword }
            ?.senses?.firstOrNull { it.text == source.sourceSense }
            ?: DictionarySense(source.sourceSense!!, PartOfSpeech.UNKNOWN, source.sourceSensePreferred)
        return rows + TranslationSenseRow(
            actualSense, source.sourceHeadword!!, aligned = true, supplemental = true
        )
    }
}
