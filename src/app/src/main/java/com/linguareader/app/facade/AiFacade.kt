package com.linguareader.app.facade

import android.app.Application
import com.linguareader.app.ai.AiLookupRequest
import com.linguareader.app.ai.AiLookupResult
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.ai.AiSettingsStore
import com.linguareader.app.ai.BookContextProfile
import com.linguareader.app.ai.BookContextRepository
import com.linguareader.app.ai.BookGlossary
import com.linguareader.app.ai.BookGlossaryRepository
import com.linguareader.app.ai.GlossaryEntry
import com.linguareader.app.ai.SentenceTranslatorFactory
import com.linguareader.app.data.Book
import com.linguareader.app.data.ContextualDictionaryEntry
import com.linguareader.app.data.WordLookup

/**
 * AI 域 facade：AI 设置、语境档案、术语表、整句翻译。
 *
 * 委托给 [AiSettingsStore]、[BookContextRepository]、[BookGlossaryRepository] 与
 * [SentenceTranslatorFactory]。语境档案生成/重生成仍返回 [AiBookStatus]（供
 * AppViewModel 写入 shared state），本类不持有 UI 状态。
 */
internal class AiFacade(application: Application) {
    private val settingsStore = AiSettingsStore(application)
    private val contextRepository = BookContextRepository(application, settingsStore)
    private val glossaryRepository = BookGlossaryRepository(application)

    fun loadSettings(): AiSettings = settingsStore.load()

    fun saveSettings(settings: AiSettings) {
        settingsStore.save(settings)
    }

    suspend fun generateContext(book: Book, force: Boolean = false): BookContextProfile =
        contextRepository.generate(book, force)

    suspend fun importGlossaryFromProfile(bookId: String, profile: BookContextProfile) {
        glossaryRepository.importFromProfile(bookId, profile)
    }

    fun delete(bookId: String) {
        contextRepository.delete(bookId)
        glossaryRepository.delete(bookId)
    }

    /** Builds the contextual lookup request from a local dictionary hit. */
    suspend fun aiLookup(
        book: Book,
        lookup: WordLookup,
        entry: ContextualDictionaryEntry?
    ): AiLookupResult? {
        val request = AiLookupRequest(
            bookId = book.id,
            bookTitle = book.title,
            surfaceWord = lookup.word,
            headword = entry?.headword ?: lookup.word,
            sentence = lookup.sentence,
            paragraph = lookup.paragraph,
            localSenses = entry?.senses?.map { it.text }.orEmpty(),
            localDefinitions = entry?.definitions.orEmpty(),
            matchedPhrase = entry?.matchedPhrase,
            glossary = glossaryRepository.load(book.id).entries
        )
        return contextRepository.translate(book, request)
    }

    // --- per-book glossary ---------------------------------------------------

    suspend fun glossary(bookId: String): BookGlossary = glossaryRepository.load(bookId)

    suspend fun addGlossaryEntry(bookId: String, term: String, translation: String): BookGlossary =
        glossaryRepository.addOrUpdate(bookId, term, translation)

    suspend fun updateGlossaryEntry(bookId: String, entry: GlossaryEntry): BookGlossary =
        glossaryRepository.update(bookId, entry)

    suspend fun removeGlossaryEntry(bookId: String, term: String): BookGlossary =
        glossaryRepository.remove(bookId, term)

    // --- Azure/DeepSeek sentence translation ---------------------------------

    suspend fun translateSentence(book: Book, sentence: String, settings: AiSettings): String {
        val translator = SentenceTranslatorFactory.from(settings)
            ?: error("未启用整句翻译（需要 Azure Translator Key 或 DeepSeek API Key）")
        val glossary = glossaryRepository.load(book.id)
        return translator.translateSentence(sentence, glossary)
    }
}
