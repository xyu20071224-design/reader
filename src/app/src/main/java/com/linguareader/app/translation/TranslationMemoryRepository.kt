package com.linguareader.app.translation

import android.app.Application
import android.net.Uri
import com.linguareader.app.data.Book
import com.linguareader.app.data.BookImporter
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.app.data.WordLookup
import com.linguareader.app.tts.TtsTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 译本对齐仓库（Android 平台层）。
 *
 * 纯对齐/查询逻辑在 [TranslationAligner] / [TranslationMemoryIndex]；本类只负责：
 * 导入中文译本（复用 [BookImporter] 到隐藏目录 `files/translations/`，不上书架）、
 * 用与听书相同的叶级选择器 [TtsTextExtractor] 抽取段落、读写对齐档案，并把
 * **查询索引按书缓存**（否则每次点词都要重读整份档案）。
 */
class TranslationMemoryRepository(private val application: Application) {

    private val translationsDir = File(application.filesDir, "translations")
    private val memoryDir = File(application.filesDir, "translation-memory")
    private val dictionary = DictionaryRepository(application)

    private val cacheLock = Mutex()
    private var cachedBookId: String? = null
    private var cachedIndex: TranslationMemoryIndex? = null

    fun hasMemory(bookId: String): Boolean = memoryFile(bookId).isFile

    /** 导入中文译本并与英文书对齐；耗时较长（整本小说量级为数十秒），在 IO 上跑。 */
    suspend fun attach(book: Book, uri: Uri): AttachTranslationResult = withContext(Dispatchers.IO) {
        translationsDir.mkdirs()
        val translationBook = BookImporter(application, translationsDir).import(uri)
        val memory = buildMemory(book, translationBook)
        save(memory)
        cacheLock.withLock {
            cachedBookId = memory.sourceBookId
            cachedIndex = TranslationMemoryIndex(memory)
        }
        AttachTranslationResult(translationBook = translationBook, memory = memory)
    }

    suspend fun lookup(
        book: Book,
        chapterIndex: Int,
        lookup: WordLookup
    ): TranslationLookupResult? = withContext(Dispatchers.IO) {
        val index = index(book.id) ?: return@withContext null
        val result = index.lookup(chapterIndex, lookup.sentence, lookup.paragraph)
            ?: return@withContext null
        // 只有句子级命中才做词级定位；段落级命中宁可不高亮，避免错标。
        if (result.matchLevel != TranslationMatchLevel.SENTENCE) return@withContext result
        val alignment = WordAligner.align(
            enWord = lookup.word,
            enSentence = lookup.sentence,
            zhSentence = result.chinese,
            candidates = dictionarySenses(lookup.word),
            enOffset = lookup.sentenceOffset
        )
        result.copy(wordAlignment = alignment)
    }

    suspend fun remove(book: Book) = withContext(Dispatchers.IO) {
        memoryFile(book.id).delete()
        if (book.translationBookId.isNotBlank()) {
            File(translationsDir, book.translationBookId).deleteRecursively()
        }
        cacheLock.withLock {
            if (cachedBookId == book.id) {
                cachedBookId = null
                cachedIndex = null
            }
        }
    }

    private suspend fun index(bookId: String): TranslationMemoryIndex? = cacheLock.withLock {
        if (cachedBookId == bookId) return@withLock cachedIndex
        val memory = load(bookId) ?: return@withLock null
        TranslationMemoryIndex(memory).also {
            cachedBookId = bookId
            cachedIndex = it
        }
    }

    private suspend fun dictionarySenses(word: String): List<String> {
        val result = dictionary.lookup(WordLookup(word, "", "", 0, 0f, 0f))
        return result.entry?.senses?.map { it.text }.orEmpty()
    }

    private fun buildMemory(source: Book, translation: Book): TranslationMemory {
        val enExtractor = TtsTextExtractor()
        val zhExtractor = TtsTextExtractor()
        val enChapters = source.chapters.indices.map { enExtractor.chapter(source, it).blocks }
        val zhChapters = translation.chapters.indices.map { zhExtractor.chapter(translation, it).blocks }
        return TranslationMemory(
            sourceBookId = source.id,
            sourceTitle = source.title,
            translationBookId = translation.id,
            translationTitle = translation.title,
            alignedAt = System.currentTimeMillis(),
            pairs = TranslationAligner.align(enChapters, zhChapters)
        )
    }

    private fun save(memory: TranslationMemory) {
        memoryDir.mkdirs()
        val file = memoryFile(memory.sourceBookId)
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
