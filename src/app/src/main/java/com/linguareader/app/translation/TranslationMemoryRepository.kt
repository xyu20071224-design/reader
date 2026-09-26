package com.linguareader.app.translation

import com.linguareader.app.data.BookScopedStore
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
import java.io.RandomAccessFile

/**
 * 译本对齐仓库（Android 平台层）。
 *
 * 纯对齐/查询逻辑在 [TranslationAligner] / [TranslationMemoryIndex]；本类只负责：
 * 导入中文译本（复用 [BookImporter] 到隐藏目录 `files/translations/`，不上书架）、
 * 用与听书相同的叶级选择器 [TtsTextExtractor] 抽取段落、读写对齐档案，并把
 * **查询索引按书缓存**（否则每次点词都要重读整份档案）。
 */
class TranslationMemoryRepository(
    private val application: Application,
    /**
     * 活动词典包的文件；null = 用内置。必须与查词面板（`AppViewModel.dictionary`）
     * **同一来源**：Q1-t09 ① 的病因就是「对齐候选」与「面板义项」是两条不同源的
     * 候选集合，剩下最后这一点差异（同一份词典）不齐，装了词典包的用户仍会错配。
     */
    dictionarySource: () -> File? = { null }
) : BookScopedStore {

    private val translationsDir = File(application.filesDir, "translations")
    private val memoryDir = File(application.filesDir, "translation-memory")
    private val dictionary = DictionaryRepository(application, dictionarySource)

    private val cacheLock = Mutex()
    private var cachedBookId: String? = null
    private var cachedIndex: TranslationMemoryIndex? = null

    fun hasMemory(bookId: String): Boolean = memoryFile(bookId).isFile

    /**
     * 该书的对齐档案是否由更旧的对齐器写出（[TranslationMemory.isOutdated]）。
     *
     * 书架每次刷新都要对**每一本有译本的书**问一次，所以走**尾部快读**：
     * `toJson` 把 `alignerVersion` 写成最后一个键，读文件末尾几百字节即可判定，
     * 不必为一本书解析整份 JSON（单本 5 MB 量级，见 translation-alignment-module）。
     * 快读不成立（文件缺失、字段今后被挪走）时退回 [load]，由
     * [TranslationMemory.isOutdated] 给最终答案 —— 慢但正确，绝不会给出错判。
     */
    suspend fun isMemoryOutdated(bookId: String): Boolean = withContext(Dispatchers.IO) {
        val file = memoryFile(bookId)
        if (!file.isFile) return@withContext false
        trailingAlignerVersion(file)?.let { return@withContext it < TranslationAligner.VERSION }
        load(bookId)?.isOutdated() ?: false
    }

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
        // 只有档案确认落盘，才允许丢正文。
        if (memoryFile(memory.sourceBookId).isFile) {
            discardTranslationBodyIfRedundant(translationBook)
        }
        cacheLock.withLock {
            cachedBookId = memory.sourceBookId
            cachedIndex = TranslationMemoryIndex(memory)
        }
        return AttachTranslationResult(translationBook = translationBook, memory = memory)
    }

    /**
     * 对齐完成后丢弃**出版译本**的正文（方案 D1.8）。
     *
     * 依据：对齐档案自带译文全文（段落表 `zhParagraphs` + 句对的 `zs`），而
     * `translations/<译本id>/` 里的章节文件**运行期零读取** —— 只在这里被
     * `buildMemory` 消费一次。留着就是整本译本白占空间。
     *
     * **AI 译本不删**：它是花钱产出的、唯一的一份，档案一旦损坏就再也拿不回来；
     * 出版译本的原文件还在用户手上，需要时重新导入即可。
     *
     * 删之前必须确认档案真的落盘了 —— 否则正文和档案一起没，译本就彻底丢了。
     */
    private fun discardTranslationBodyIfRedundant(translationBook: Book) {
        if (translationBook.id.isBlank()) return
        if (translationBook.id.startsWith(Book.AI_TRANSLATION_ID_PREFIX)) return
        val archive = File(translationBook.extractedDir)
        if (!archive.canonicalPath.startsWith(translationsDir.canonicalPath + File.separator)) return
        archive.deleteRecursively()
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
        // 与单词释义面板**同源**的候选：用点词时的真实 `WordLookup`（句子/段落/偏移）
        // 查同一套词典逻辑，`ContextAnalyzer.inferPartOfSpeech` 才拿得到上下文，
        // `DictionarySense.contextPreferred` 才与面板上那枚「本句优先」标一致。
        // 旧实现传 `WordLookup(word, "", "", 0, 0f, 0f)`：空句 → 词性 UNKNOWN →
        // contextPreferred 恒 false，两条路径的候选结构上不同源（Q1-t09 ①）。
        val senses = dictionary.lookup(lookup).entry?.senses.orEmpty()
        val alignment = WordAligner.align(
            enWord = lookup.word,
            enSentence = lookup.sentence,
            zhSentence = result.chinese,
            candidates = senses.map { it.text },
            enOffset = lookup.sentenceOffset,
            // 语境优选义项的候选词正加成：面板标「本句优先」的那条义项，同时决定
            // 中文句里高亮哪个词（Q1-t09 ②）。切词用对齐器自己的实现，保证两边候选
            // 集合逐字相同。
            prefer = WordAligner
                .candidateTerms(senses.filter { it.contextPreferred }.map { it.text })
                .associateWith { WordAligner.CONTEXT_PREFERRED_BONUS }
        )
        result.copy(wordAlignment = alignment)
    }

    override val storeId: String = "translations"

    /** 两处：译本正文与对齐档案。它们本该同生共死，却分属两个删除键（见方案证据 4/5）。 */
    override fun storageRoots(): List<File> = listOf(translationsDir, memoryDir)

    override suspend fun deleteBookData(book: Book) { remove(book) }

    /**
     * 两个根、两套命名，默认推断不适用：
     * - `translation-memory/<原书id>.json` 按书命名，用默认判据；
     * - `translations/<译本id>/` 按**译本** id 命名 —— 出版译本是内容哈希（只能靠
     *   某本书的 translationBookId 认领），AI 译本是「前缀 + 原书 id」。
     *   两者都没人认领时才是孤儿。
     */
    override fun orphans(books: List<Book>): List<File> {
        val known = books.map { it.id }.toSet()
        val claimed = books.mapNotNull { it.translationBookId.takeIf(String::isNotBlank) }.toSet() +
            books.map { Book.AI_TRANSLATION_ID_PREFIX + it.id }.toSet()
        val memoryOrphans = memoryDir.listFiles().orEmpty()
            .filter { it.name.removeSuffix(".json") !in known }
        val bodyOrphans = translationsDir.listFiles().orEmpty()
            .filter { it.name !in claimed }
        return memoryOrphans + bodyOrphans
    }

    suspend fun remove(book: Book) = withContext(Dispatchers.IO) {
        memoryFile(book.id).delete()
        // 译本正文目录有两种命名：
        //  - 导入的出版译本 = 源文件内容哈希，只能靠 metadata 里的 translationBookId 找回；
        //  - AI 生成译本 = 前缀 + 原书 id，**可以从书本身推出来**。
        // 所以 AI 那份不再依赖那个字段：字段一旦与磁盘不一致（metadata 写坏、迁移
        // 遗漏、attach 中途失败），正文就会变成没人认领的孤儿（方案证据 5）。
        // 出版译本那份仍然依赖字段——它的目录名不可推导，兜底交给孤儿对账（D1.7）。
        if (book.translationBookId.isNotBlank()) {
            File(translationsDir, book.translationBookId).deleteRecursively()
        }
        File(translationsDir, Book.AI_TRANSLATION_ID_PREFIX + book.id).deleteRecursively()
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

    /**
     * 用**当前对齐器**重跑该书档案的对齐（不重新翻译，也不重新导入译本）。
     *
     * 中文侧不读译本正文，而是从**旧档案**还原「章 → 段落」：
     * - 出版译本对齐后正文即被丢弃（D1.8，[discardTranslationBodyIfRedundant]），
     *   盘上已经没有了；档案自带译文全文（`zhParagraphs` + `pairs[].zs`），是
     *   **出版译本与 AI 译本都成立**的唯一中文源；
     * - 顺序可信：对齐器按 (zhChapter, 段序) 单调产出句对，所以「按首次出现顺序
     *   去重」还原出的段序与译本原文一致。去重按**引用**判等：`fromJson` 让同段落
     *   的多条句对共享同一个 String 实例，引用去重恰好等价于还原档案的段落表，
     *   不会把两处文本相同的段落并成一条。
     *
     * 代价（如实记在案，不是缺陷）：旧档案里**从来没配上对**的段落本来就不在档案
     * 里，重对齐也无从恢复（实测未对齐约 3%）；英文侧读原书正文，不受影响。
     *
     * @return 新档案（已落盘 + 已刷新 [cachedIndex]）。以下情形返回 null 且
     *   **一个字节都不写**——绝不把用户手上现存的对照清空：档案不存在；档案里没有
     *   可还原的中文段落；重跑结果为空而旧档案非空。
     */
    suspend fun realign(book: Book): TranslationMemory? = withContext(Dispatchers.IO) {
        val existing = load(book.id) ?: return@withContext null
        val zhChapters = archivedZhChapters(existing)
        if (zhChapters.isEmpty()) return@withContext null
        val updated = buildMemory(
            source = book,
            zhChapters = zhChapters,
            translationBookId = existing.translationBookId,
            translationTitle = existing.translationTitle
        )
        if (updated.pairs.isEmpty() && existing.pairs.isNotEmpty()) return@withContext null
        save(updated)
        cacheLock.withLock {
            cachedBookId = updated.sourceBookId
            cachedIndex = TranslationMemoryIndex(updated)
        }
        updated
    }

    /**
     * 从档案还原中文侧「章 → 段落」序列（[realign] 的输入）。
     * 空的中间章保留占位，章节下标才继续与 `pairs[].zhChapter` 对得上。
     */
    private fun archivedZhChapters(memory: TranslationMemory): List<List<String>> {
        val byChapter = sortedMapOf<Int, MutableList<String>>()
        val seen: MutableSet<String> =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap<String, Boolean>())
        for (pair in memory.pairs) {
            if (pair.zhChapter < 0) continue
            val paragraph = pair.zhParagraph
            if (paragraph.isBlank()) continue
            if (!seen.add(paragraph)) continue
            byChapter.getOrPut(pair.zhChapter) { ArrayList() }.add(paragraph)
        }
        if (byChapter.isEmpty()) return emptyList()
        return (0..byChapter.lastKey()).map { byChapter[it].orEmpty() }
    }

    /**
     * 只读档案尾部的 `alignerVersion`；文件缺失、截断或字段被挪出尾部时返回 null
     * （调用方退回整份 [load]）。取**最后一次**匹配：该字段是 `toJson` 写下的最后
     * 一个键，尾片里它必然排在所有正文之后；正文里出现的同名字面量在 JSON 里带转义
     * （`\"alignerVersion\"`），反斜杠挡在引号前，本正则不会误命中。
     */
    private fun trailingAlignerVersion(file: File): Int? = runCatching {
        val length = file.length()
        if (length <= 0L) return@runCatching null
        val tailSize = minOf(length, TRAILING_VERSION_BYTES).toInt()
        val buffer = ByteArray(tailSize)
        RandomAccessFile(file, "r").use { reader ->
            reader.seek(length - tailSize)
            reader.readFully(buffer)
        }
        // 尾片可能从多字节字符中间开始，解码用的替换字符只影响片首，无碍匹配。
        TAILING_ALIGNER_VERSION.findAll(String(buffer, Charsets.UTF_8))
            .lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    }.getOrNull()

    private fun buildMemory(source: Book, translation: Book): TranslationMemory {
        val zhExtractor = TtsTextExtractor()
        val zhChapters = translation.chapters.indices.map { zhExtractor.chapter(translation, it).blocks }
        return buildMemory(source, zhChapters, translation.id, translation.title)
    }

    /**
     * 对齐构建的公共部分：英文侧始终读原书正文；中文侧由调用方给——首次对齐来自
     * 译本正文（[buildMemory] 的译本重载），重对齐来自旧档案（[realign]）。
     */
    private fun buildMemory(
        source: Book,
        zhChapters: List<List<String>>,
        translationBookId: String,
        translationTitle: String
    ): TranslationMemory {
        val enExtractor = TtsTextExtractor()
        val enChapters = source.chapters.indices.map { enExtractor.chapter(source, it).blocks }
        return TranslationMemory(
            sourceBookId = source.id,
            sourceTitle = source.title,
            translationBookId = translationBookId,
            translationTitle = translationTitle,
            alignedAt = System.currentTimeMillis(),
            pairs = TranslationAligner.align(enChapters, zhChapters, meaningIndex),
            alignerVersion = TranslationAligner.VERSION
        )
    }

    /** 词义锚点查询（延迟打开 ecdict 副本，单词缓存）。 */
    private val meaningIndex: MeaningIndex by lazy { EcdictMeaningIndex(application) }

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

    private companion object {
        /**
         * 尾部快读的字节数：`alignerVersion` 是 `toJson` 写下的最后一个键，
         * 这点字节足够读到它，且用量不随档案（5 MB 量级）增长。
         */
        const val TRAILING_VERSION_BYTES = 512L

        /** 尾部 `"alignerVersion":<int>` 的匹配式；语义与兜底见 [trailingAlignerVersion]。 */
        val TAILING_ALIGNER_VERSION = Regex("\"alignerVersion\"\\s*:\\s*(\\d+)")
    }
}

/**
 * 基于 ECDICT（离线词典）的词义锚点：
 * 英文词 → 释义中的中文短语集合。词形走 forms 表回退到 lemma；短语经
 * [MeaningPhraseParser] 过滤（实义词性白名单 + 虚词黑名单）。
 *
 * 数据库复用词典的 filesDir 副本（DictionaryRepository 同一路径），只读打开；
 * 首次打开时若副本不存在则从 assets 拷贝（与词典逻辑一致）。
 */
internal class EcdictMeaningIndex(private val application: Application) : MeaningIndex {

    private val cache = HashMap<String, Set<String>>()

    private val db: android.database.sqlite.SQLiteDatabase by lazy {
        val databaseDir = File(application.filesDir, "dictionary").apply { mkdirs() }
        val target = File(databaseDir, "ecdict-v2.sqlite")
        if (!target.exists() || target.length() == 0L) {
            val temp = File(databaseDir, "ecdict-v2.sqlite.tmp")
            application.assets.open("dictionary/ecdict.sqlite").use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temp.renameTo(target)) {
                target.outputStream().use { output ->
                    temp.inputStream().use { it.copyTo(output) }
                }
                temp.delete()
            }
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(
            target.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY or
                android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
    }

    override fun phrasesOf(word: String): Set<String> {
        cache[word]?.let { return it }
        val phrases = query(word)
        cache[word] = phrases
        return phrases
    }

    private fun query(word: String): Set<String> {
        val w = word.lowercase().trim('\'', '\u2019')
        fetchTranslation(w)?.let { return MeaningPhraseParser.parse(it) }
        val lemma = db.rawQuery("select lemma from forms where form = ?", arrayOf(w)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return emptySet()
        fetchTranslation(lemma.lowercase())?.let { return MeaningPhraseParser.parse(it) }
        return emptySet()
    }

    private fun fetchTranslation(word: String): String? =
        db.rawQuery("select translation from entries where word = ?", arrayOf(word)).use { c ->
            if (c.moveToFirst()) c.getString(0).takeIf { it.isNotBlank() } else null
        }
}
