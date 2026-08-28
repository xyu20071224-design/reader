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
        finishAttach(book, translationBook)
    }

    /**
     * 接入一份已经写好章节文件的中文译本（AI 生成路径）：译文目录由
     * [com.linguareader.app.ai.AiTranslationRepository] 落在 `files/translations/`
     * 下，这里跳过 [BookImporter] 直接对齐落盘，其余契约与 [attach] 完全一致。
     */
    suspend fun attachGenerated(book: Book, translationBook: Book): AttachTranslationResult =
        withContext(Dispatchers.IO) {
            finishAttach(book, translationBook)
        }

    private suspend fun finishAttach(book: Book, translationBook: Book): AttachTranslationResult {
        val memory = buildMemory(book, translationBook)
        save(memory)
        cacheLock.withLock {
            cachedBookId = memory.sourceBookId
            cachedIndex = TranslationMemoryIndex(memory)
        }
        return AttachTranslationResult(translationBook = translationBook, memory = memory)
    }

    /** 读取整份档案（句级重翻需要原文、所在段落与上下文）。 */
    suspend fun memoryFor(bookId: String): TranslationMemory? = withContext(Dispatchers.IO) {
        load(bookId)
    }

    /**
     * 句级定点重翻落盘：只替换 [pairIndex] 那条句对的 zhSentence——`copy` 不会
     * 产生新段落实例，段落表去重与「同段句对共享 String 实例」契约都不受影响。
     * 原子写档案后在锁内整体重建查询索引（Index 内部全不可变，必须换对象；
     * 即使 cachedBookId 已相等也要覆写，否则查询继续拿旧索引返回旧译文）。
     */
    suspend fun replaceSentenceTranslation(
        bookId: String,
        pairIndex: Int,
        newZh: String
    ): TranslationMemory? = withContext(Dispatchers.IO) {
        val memory = load(bookId) ?: return@withContext null
        val pair = memory.pairs.getOrNull(pairIndex) ?: return@withContext null
        val trimmed = newZh.trim()
        if (trimmed.isEmpty()) return@withContext null
        val updated = memory.copy(
            pairs = memory.pairs.toMutableList().also {
                it[pairIndex] = pair.copy(zhSentence = trimmed)
            }
        )
        save(updated)
        cacheLock.withLock {
            cachedBookId = updated.sourceBookId
            cachedIndex = TranslationMemoryIndex(updated)
        }
        updated
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
