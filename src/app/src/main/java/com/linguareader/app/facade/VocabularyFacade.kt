package com.linguareader.app.facade

import android.app.Application
import android.net.Uri
import com.linguareader.app.ai.AiLookupResult
import com.linguareader.app.data.Book
import com.linguareader.app.data.ContextualDictionaryEntry
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.SavedWord
import com.linguareader.app.data.VocabularyRepository
import com.linguareader.app.data.WordLookup

/**
 * 生词本域 facade：收藏、删除、复习、导出。
 *
 * 所有方法均为纯 domain 调用，仅委托给 [VocabularyRepository]；状态写入与
 * 提醒重调度由 AppViewModel 编排完成（AppViewModel 是 AppUiState 的唯一写者）。
 */
internal class VocabularyFacade(
    application: Application
) {
    private val vocabulary = VocabularyRepository(application)

    suspend fun load(): List<SavedWord> = vocabulary.load()

    suspend fun save(
        book: Book,
        chapterTitle: String,
        lookup: WordLookup,
        entry: ContextualDictionaryEntry,
        pace: ReviewPace,
        aiResult: AiLookupResult?
    ): List<SavedWord> = vocabulary.save(book, chapterTitle, lookup, entry, pace, aiResult)

    suspend fun remove(id: String): List<SavedWord> = vocabulary.remove(id)

    suspend fun review(id: String, remembered: Boolean, pace: ReviewPace): List<SavedWord> =
        vocabulary.review(id, remembered, pace)

    suspend fun export(uri: Uri, words: List<SavedWord>) = vocabulary.export(uri, words)
}
