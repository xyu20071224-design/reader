package com.linguareader.app.translation

import android.app.Application
import android.net.Uri
import com.linguareader.app.data.Book
import com.linguareader.app.data.BookImporter
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.app.data.WordLookup
import com.linguareader.app.tts.TtsTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 译本对齐仓库（Android 平台层）。
 *
 * 纯对齐/查询逻辑在 :core 的 [TranslationAligner] / [TranslationMemorySearch]；
 * 本类只负责：导入中文译本（复用 [BookImporter] 到隐藏目录 files/translations/）、
 * 用与 TTS 相同的叶级选择器 [TtsTextExtractor] 抽取段落、读写对齐档案。
 * 另在 attach 时一次性学习「本书术语表」([TermLexiconLearner]) 并随档案持久化，
 * 查词阶段用它给 [WordAligner] 提供偏好加成。
 */
class TranslationMemoryRepository(private val application: Application) {
    private val translationsDir = File(application.filesDir, "translations").apply { mkdirs() }
    private val memoryDir = File(application.filesDir, "translation-memory").apply { mkdirs() }
    private val dictionary = DictionaryRepository(application)

    suspend fun attach(book: Book, uri: Uri): AttachTranslationResult = withContext(Dispatchers.IO) {
        val translationBook = BookImporter(application, translationsDir).import(uri)
        val memory = buildMemory(book, translationBook)
        save(memory)
        AttachTranslationResult(translationBook = translationBook, memory = memory)
    }

    suspend fun lookup(
        book: Book,
        chapterIndex: Int,
        lookup: WordLookup
    ): TranslationLookupResult? = withContext(Dispatchers.IO) {
        val memory = load(book.id) ?: return@withContext null
        val result = TranslationMemorySearch.lookup(memory, chapterIndex, lookup.sentence, lookup.paragraph)
            ?.copy(translationTitle = memory.translationTitle)
            ?: return@withContext null

        // 第二期：句子级命中时，先用本书术语表偏好，再以词典义项兜底做词级对齐。
        if (result.matchLevel == TranslationMatchLevel.SENTENCE) {
            val prefer = memory.terms
                .filter { it.enWord == TermLexiconLearner.normalizeWord(lookup.word) }
                .associate { it.zhTerm to (0.05f + it.confidence * 0.3f) }
            val alignment = WordAligner.align(
                enWord = lookup.word,
                enSentence = lookup.sentence,
                zhSentence = result.chinese,
                candidates = dictionarySenses(lookup.word),
                enOffset = lookup.sentenceOffset,
                prefer = prefer
            )
            return@withContext result.copy(wordAlignment = alignment)
        }
        result
    }

    private suspend fun dictionarySenses(word: String): List<String> {
        val result = dictionary.lookup(WordLookup(word, "", "", 0, 0f, 0f))
        return result.entry?.senses?.map { it.text }.orEmpty()
    }

    fun remove(book: Book) {
        memoryFile(book.id).delete()
        if (book.translationBookId.isNotBlank()) {
            File(translationsDir, book.translationBookId).deleteRecursively()
        }
    }

    private suspend fun buildMemory(source: Book, translation: Book): TranslationMemory {
        val enExtractor = TtsTextExtractor()
        val zhExtractor = TtsTextExtractor()
        val enChapters = source.chapters.indices.map { enExtractor.chapter(source, it).blocks }
        val zhChapters = translation.chapters.indices.map { zhExtractor.chapter(translation, it).blocks }
        val pairs = TranslationAligner.align(enChapters, zhChapters)
        // 第一次附加时的额外一次性工作：从对齐句对学习本书术语表。
        // 之后查词阶段从档案直接读 terms，零学习成本。
        val terms = learnTerms(pairs)
        return TranslationMemory(
            sourceBookId = source.id,
            sourceTitle = source.title,
            translationBookId = translation.id,
            translationTitle = translation.title,
            alignedAt = System.currentTimeMillis(),
            pairs = pairs,
            terms = terms
        )
    }

    /** 收集句中英文词的 ECDICT 义项作为种子，学习「本书中译法」术语表。 */
    private suspend fun learnTerms(pairs: List<AlignedSentencePair>): List<BookTerm> {
        val enWords = pairs.flatMap { TermLexiconLearner.tokens(it.enSentence) }.distinct()
        val seeds = enWords.mapNotNull { w ->
            dictionarySenses(w).takeIf { it.isNotEmpty() }?.let { w to it }
        }.toMap()
        return TermLexiconLearner.learn(pairs, seeds)
    }

    private fun save(memory: TranslationMemory) {
        val file = memoryFile(memory.sourceBookId)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(memory.toJson().toString())
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun load(sourceBookId: String): TranslationMemory? {
        val file = memoryFile(sourceBookId)
        if (!file.isFile) return null
        return runCatching { TranslationMemory.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    private fun memoryFile(sourceBookId: String) = File(memoryDir, "$sourceBookId.json")
}
