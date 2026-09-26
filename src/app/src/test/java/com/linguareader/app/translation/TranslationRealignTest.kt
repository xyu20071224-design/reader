package com.linguareader.app.translation

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import com.linguareader.app.data.WordLookup
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阶段 2：档案版本闸门（`alignerVersion` 只写不读 → 接上「重新对齐」）。
 *
 * 这组用例钉住四件事：
 * ① [TranslationMemoryRepository.isMemoryOutdated] 能判出旧档案（尾部快读），
 *    并在字段被挪出尾部/缺失时退回整份 load 仍给对答案；
 * ② [TranslationMemoryRepository.realign] **不重新翻译**：出版译本正文在对齐后
 *    就已被丢弃（D1.8），重跑的中文侧来自档案自带译文；
 * ③ AI 译本（正文保留）同样能重对齐；
 * ④ 没有可重跑的中文源时**一个字节都不写** —— 绝不清空用户手上现存的对照。
 */
@RunWith(RobolectricTestRunner::class)
class TranslationRealignTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    /**
     * 内置词典 60 MB 的落盘副本在 Robolectric 下要整块读进堆（`FileMap.getDataPtr`），
     * 一个测试类里多跑几次就会 `OutOfMemoryError`（实测）。本组用例只关心对齐/版本
     * 判定，不关心词义锚点，于是放一份**空表**迷你词典顶掉那次拷贝：查不到即空集，
     * 与既有实现「拿不到词义短语就不加分」的路径完全一致，断言不受影响。
     * 列名照抄 `DictionarySql`（`entries(word, phonetic, translation, definition)`、
     * `forms(form, lemma)`），两个查询都不会报错。
     */
    @Before
    fun stubDictionaryToKeepTheHeapFlat() {
        val dir = File(context.filesDir, "dictionary").apply { mkdirs() }
        val target = File(dir, "ecdict-v2.sqlite")
        if (target.length() > 0L) return
        SQLiteDatabase.openOrCreateDatabase(target, null).use { db ->
            db.execSQL(
                "CREATE TABLE entries(word TEXT PRIMARY KEY, phonetic TEXT, translation TEXT, definition TEXT)"
            )
            db.execSQL("CREATE TABLE forms(form TEXT, lemma TEXT)")
        }
    }

    private fun writeBook(id: String, dir: File, paragraphs: List<String>): Book {
        dir.mkdirs()
        val body = paragraphs.joinToString("") { "<p>" + it + "</p>" }
        File(dir, "chapter_000.xhtml").writeText("<html><body>" + body + "</body></html>")
        return Book(
            id = id,
            title = "Book " + id,
            author = "Author",
            extractedDir = dir.absolutePath,
            coverRelativePath = null,
            chapters = listOf(Chapter("Chapter 1", "chapter_000.xhtml")),
            addedAt = System.currentTimeMillis()
        )
    }

    private fun source(id: String) = writeBook(
        id,
        File(context.filesDir, "books/" + id),
        listOf("In 1926 he left the town.", "She carried a lantern.")
    )

    private fun translation(id: String) = writeBook(
        id,
        File(context.filesDir, "translations/" + id),
        listOf("1926年他离开了小镇。", "她提着一盏灯。")
    )

    private fun archiveFile(bookId: String) =
        File(context.filesDir, "translation-memory/$bookId.json")

    private fun archiveVersion(bookId: String): Int =
        JSONObject(archiveFile(bookId).readText()).optInt("alignerVersion")

    /** 把档案改写成「更旧的对齐器写出来的样子」，并确认替换真的发生了。 */
    private fun downgradeArchiveVersion(bookId: String, version: Int = 0) {
        val file = archiveFile(bookId)
        val original = file.readText()
        val patched = Regex("\"alignerVersion\":\\d+")
            .replace(original, "\"alignerVersion\":$version")
        assertTrue(patched != original, "夹具体没有 alignerVersion 字段，替换没生效")
        file.writeText(patched)
    }

    @Test
    fun publishedTranslationRealignsFromTheArchiveWithoutItsBody() = runBlocking<Unit> {
        val book = source("realign-published")
        val zh = translation("imported-abc123")
        val repository = TranslationMemoryRepository(context)
        repository.attachGenerated(book, zh)
        // 出版译本正文此刻已被丢弃：重对齐只能靠档案自带译文。
        assertFalse(File(zh.extractedDir).exists(), "出版译本正文应在对齐后被丢弃")
        assertFalse(repository.isMemoryOutdated(book.id), "刚对齐的档案就是当前版本")
        val pairsBefore = JSONObject(archiveFile(book.id).readText()).getJSONArray("pairs").length()

        downgradeArchiveVersion(book.id)
        assertTrue(repository.isMemoryOutdated(book.id), "旧版档案必须判为过期")

        val updated = assertNotNull(repository.realign(book), "有档案却没能重对齐")
        assertEquals(TranslationAligner.VERSION, updated.alignerVersion)
        assertEquals(pairsBefore, updated.pairs.size, "同一份文本用同一个对齐器应得到同样的句对数")
        // 译本元信息沿用旧档案，不是重新导入出来的。
        assertEquals("imported-abc123", updated.translationBookId)
        assertEquals(zh.title, updated.translationTitle)
        // 落盘 + 缓存都刷新了。
        assertEquals(TranslationAligner.VERSION, archiveVersion(book.id))
        assertFalse(repository.isMemoryOutdated(book.id))
        val hit = repository.lookup(
            book, 0,
            WordLookup("lantern", "She carried a lantern.", "She carried a lantern.", 12, 0f, 0f)
        )
        val result = assertNotNull(hit, "重对齐后缓存没刷新：查不到新索引")
        assertEquals(TranslationMatchLevel.SENTENCE, result.matchLevel)
    }

    @Test
    fun aiTranslationAlsoRealigns() = runBlocking<Unit> {
        val book = source("realign-ai")
        val zh = translation(Book.AI_TRANSLATION_ID_PREFIX + "realign-ai")
        val repository = TranslationMemoryRepository(context)
        repository.attachGenerated(book, zh)
        assertTrue(File(zh.extractedDir).isDirectory, "AI 译本正文应保留")
        downgradeArchiveVersion(book.id)

        val updated = assertNotNull(repository.realign(book))
        assertEquals(TranslationAligner.VERSION, updated.alignerVersion)
        assertTrue(updated.pairs.isNotEmpty())
        assertEquals(Book.AI_TRANSLATION_ID_PREFIX + "realign-ai", updated.translationBookId)
    }

    @Test
    fun realignWritesNothingWhenThereIsNoArchive() = runBlocking<Unit> {
        val book = source("realign-empty")
        val repository = TranslationMemoryRepository(context)

        assertNull(repository.realign(book), "没有档案就没有可重跑的东西")
        assertFalse(archiveFile(book.id).exists(), "没档案时不该凭空造一份出来")
        assertFalse(repository.isMemoryOutdated(book.id))
    }

    @Test
    fun realignKeepsTheExistingArchiveWhenNothingCanBeRecovered() = runBlocking<Unit> {
        val book = source("realign-unrecoverable")
        // 档案里没有任何可还原的中文段落（zhParagraph 全空 = 坏档案）。
        val broken = TranslationMemory(
            sourceBookId = book.id,
            sourceTitle = book.title,
            translationBookId = "imported-broken",
            translationTitle = "坏档案",
            alignedAt = 0L,
            pairs = listOf(
                AlignedSentencePair(
                    enChapter = 0,
                    zhChapter = 0,
                    enParagraph = "She carried a lantern.",
                    zhParagraph = "",
                    enSentence = "She carried a lantern.",
                    zhSentence = "",
                    confidence = 0.9f
                )
            ),
            alignerVersion = 0
        )
        archiveFile(book.id).apply { parentFile?.mkdirs() }.writeText(broken.toJson().toString())
        val before = archiveFile(book.id).readText()
        val repository = TranslationMemoryRepository(context)
        assertTrue(repository.isMemoryOutdated(book.id), "alignerVersion=0 天然判旧")

        assertNull(repository.realign(book), "没有可还原的中文段落时必须拒绝重跑")
        assertEquals(before, archiveFile(book.id).readText(), "拒绝重跑时不得改写档案")
    }

    @Test
    fun outdatedCheckFallsBackToTheWholeArchiveWhenTheFieldLeavesTheTail() = runBlocking<Unit> {
        val book = source("realign-tail")
        val zh = translation("imported-tail")
        val repository = TranslationMemoryRepository(context)
        repository.attachGenerated(book, zh)
        downgradeArchiveVersion(book.id)

        // 尾部快读只看最后 512 字节：把一个长字段追加到 alignerVersion 之后，
        // 快读必须落空并退回整份 load，而不是给出「不过期」的错判。
        val file = archiveFile(book.id)
        val padded = JSONObject(file.readText()).put("futureField", "x".repeat(600)).toString()
        assertTrue(padded.indexOf("alignerVersion") < padded.length - 512, "夹具体没把版本字段挤出尾部")
        file.writeText(padded)

        assertTrue(repository.isMemoryOutdated(book.id), "快读落空后必须退回整份档案判定")
    }

    @Test
    fun legacyArchiveWithoutTheFieldCountsAsOutdated() = runBlocking<Unit> {
        val book = source("realign-legacy")
        val zh = translation("imported-legacy")
        val repository = TranslationMemoryRepository(context)
        repository.attachGenerated(book, zh)
        val file = archiveFile(book.id)
        file.writeText(JSONObject(file.readText()).apply { remove("alignerVersion") }.toString())

        // 旧档案没有这个字段：读出即 0（TranslationMemory.fromJson 的默认值），天然判旧。
        assertTrue(repository.isMemoryOutdated(book.id))
    }
}
