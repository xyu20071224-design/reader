package com.linguareader.app

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import com.linguareader.app.data.SavedWord
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * D0 安全网：把「删一本书到底清掉了什么」原样钉住。
 *
 * 现状是一张**手写的级联表**，住在 UI 层（AppViewModel.deleteBook:292-314），
 * 没有编译期保证、没有测试、没有对账；新增一处 per-book 存储时也没有任何机制
 * 提醒你回来加一行。它已经漏了一处（生词本）。
 *
 * 这组用例在把级联改成「遍历权威清单」之前先立好基准：
 * - 该删的漏了 → 红；
 * - 不该删的删了 → 红（[deletingOneBookNeverTouchesAnotherBooksData]）；
 * - 其中 [savedWordsSurviveBookDeletion_currentBehaviour] 是**故意记录缺陷现状**的，
 *   D1.4 落地「删书连带删生词」时连同断言一起翻面 —— 翻面本身就是那次行为变更的留痕。
 *
 * 见「重构方案-数据所有权与生命周期.md」D0 / D1。
 */
@RunWith(RobolectricTestRunner::class)
class BookDeletionCascadeTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    /** 生产形态：extractedDir 就是 books/<id> 本身（EpubImporter.kt:19-20），metadata.json 在其中。 */
    private fun book(id: String, translationId: String = "trans-$id"): Book = Book(
        id = id,
        title = "Book $id",
        author = "Author",
        extractedDir = File(context.filesDir, "books/$id").absolutePath,
        coverRelativePath = null,
        chapters = listOf(Chapter("Chapter 1", "chapter1.xhtml")),
        addedAt = System.currentTimeMillis(),
        translationBookId = translationId,
        translationTitle = "译本 $id",
        translationAlignedAt = System.currentTimeMillis()
    )

    /**
     * 一本书在磁盘上的全部落点。key 是人话名字，断言失败时能一眼看出漏了哪处。
     * 这张表就是「一本书的数据由什么构成」——现在它只存在于测试里，D1 要把它变成
     * 生产代码里的权威清单。
     */
    private fun perBookStores(book: Book): Map<String, File> = mapOf(
        "books/<id>" to File(context.filesDir, "books/${book.id}"),
        "ai/book-context" to File(context.filesDir, "ai/book-context/${book.id}.json"),
        "ai/glossary" to File(context.filesDir, "ai/glossary/${book.id}.json"),
        "ai/speaker-tags" to File(context.filesDir, "ai/speaker-tags/${book.id}"),
        "translation-memory" to File(context.filesDir, "translation-memory/${book.id}.json"),
        "translations/<译本id>" to File(context.filesDir, "translations/${book.translationBookId}"),
        "ai/ai-translations" to File(context.filesDir, "ai/ai-translations/${book.id}"),
        "tts_cache" to File(context.filesDir, "tts_cache/${book.id}"),
        "voice_maps" to File(context.filesDir, "voice_maps/${book.id}.json")
    )

    private fun seed(book: Book) {
        val root = File(context.filesDir, "books/${book.id}").apply { mkdirs() }
        File(root, "metadata.json").writeText(book.toJson().toString())
        File(root, "chapter1.xhtml").writeText("<html><body><p>hi</p></body></html>")
        writeFile("ai/book-context/${book.id}.json", "{}")
        writeFile("ai/glossary/${book.id}.json", "[]")
        writeFile("ai/speaker-tags/${book.id}/0.json", "[]")
        writeFile("translation-memory/${book.id}.json", "{}")
        writeFile("translations/${book.translationBookId}/chapter_000.xhtml", "<html/>")
        writeFile("ai/ai-translations/${book.id}/0-0.json", "{}")
        writeFile("tts_cache/${book.id}/0/voice/0.mp3", "id3")
        writeFile("voice_maps/${book.id}.json", "{}")
    }

    private fun writeFile(relative: String, content: String) {
        val file = File(context.filesDir, relative)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun savedWord(id: String, bookId: String) = SavedWord(
        id = id,
        headword = "lantern",
        phonetic = "",
        meaning = "灯笼",
        sentence = "She carried a lantern.",
        bookId = bookId,
        bookTitle = "Book $bookId",
        chapterTitle = "Chapter 1",
        addedAt = System.currentTimeMillis()
    )

    private fun seedVocabulary(vararg words: SavedWord) {
        val array = JSONArray()
        words.forEach { array.put(it.toJson()) }
        File(context.filesDir, "vocabulary.json").writeText(array.toString())
    }

    private fun loadVocabularyBookIds(): List<String> {
        val text = File(context.filesDir, "vocabulary.json").takeIf { it.isFile }?.readText() ?: return emptyList()
        val array = JSONArray(text)
        return (0 until array.length()).map { array.getJSONObject(it).optString("bookId") }
    }

    private fun pump(condition: () -> Boolean) {
        val shadow = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadow.idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("等待超时：删书流程未在 5s 内跑完", condition())
    }

    private fun deleteAndAwait(viewModel: AppViewModel, target: Book) {
        pump { !viewModel.state.value.loading }
        viewModel.deleteBook(target)
        pump { viewModel.state.value.books.none { it.id == target.id } }
    }

    @Test
    fun deletingABookClearsEveryPerBookStore() {
        val target = book("cascade-a")
        seed(target)
        val stores = perBookStores(target)
        stores.forEach { (name, file) -> assertTrue("$name 播种失败", file.exists()) }

        val viewModel = AppViewModel(context)
        deleteAndAwait(viewModel, target)

        val leftovers = stores.filterValues { it.exists() }.keys
        assertEquals("删书后仍有残留：$leftovers", emptySet<String>(), leftovers)
    }

    /**
     * **故意钉住的缺陷现状**：生词不随书删除。
     *
     * VocabularyRepository 保存时记了 bookId，却只有按词 id 删的 remove(id)，
     * 没有按书删的入口；VocabularyScreen 也不按「书还在不在」过滤，于是删完书
     * 生词照旧显示，还带着一个打不开的书名。
     *
     * D1.4 落地后本用例应翻面成「该书生词已清空、别的书不受影响」。
     */
    @Test
    fun savedWordsSurviveBookDeletion_currentBehaviour() {
        val target = book("cascade-b")
        seed(target)
        seedVocabulary(savedWord("w1", target.id), savedWord("w2", "other-book"))

        val viewModel = AppViewModel(context)
        deleteAndAwait(viewModel, target)

        assertEquals(listOf(target.id, "other-book"), loadVocabularyBookIds())
    }

    /** 级联改成遍历清单后最容易出的新事故是「删过头」，这条守住边界。 */
    @Test
    fun deletingOneBookNeverTouchesAnotherBooksData() {
        val target = book("cascade-c")
        val bystander = book("cascade-d")
        seed(target)
        seed(bystander)

        val viewModel = AppViewModel(context)
        deleteAndAwait(viewModel, target)

        val missing = perBookStores(bystander).filterValues { !it.exists() }.keys
        assertTrue("删 A 书误伤了 B 书：$missing", missing.isEmpty())
        assertFalse(File(context.filesDir, "books/${target.id}").exists())
    }

    /**
     * D1.6 漂移守卫：权威清单必须覆盖全部已知的 per-book 落点。
     *
     * 这条不可能自动发现「谁新加了一处存储却没登记」——没有代码引用的目录，测试也
     * 看不见。它买到的是另一件事：**清单被改动时必须有人确认**。你加了一处存储并
     * 登记了，这条会红，红在这里就是提醒你顺带更新 D0 的落点表与孤儿对账。
     */
    @Test
    fun registryCoversEveryKnownPerBookStore() {
        val viewModel = AppViewModel(context)
        val ids = viewModel.bookDataStores.map { it.storeId }.toSet()

        assertEquals(
            "per-book 存储清单变了。新增存储请同时更新：本用例、perBookStores() 落点表、孤儿对账。",
            setOf(
                "books",
                "ai/book-context",
                "ai/glossary",
                "ai/speaker-tags",
                "translations",
                "ai/ai-translations",
                "tts_cache",
                "voice_maps"
            ),
            ids
        )
    }

    /** 每处存储的根都必须落在 filesDir 里 —— 删除是递归的，根指错地方后果不可逆。 */
    @Test
    fun everyStoreRootStaysInsideFilesDir() {
        val viewModel = AppViewModel(context)
        val outside = viewModel.bookDataStores.flatMap { store ->
            store.storageRoots().map { store.storeId to it }
        }.filterNot { (_, root) ->
            root.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)
        }
        assertTrue("这些存储的根不在 filesDir 下：$outside", outside.isEmpty())
    }
}
