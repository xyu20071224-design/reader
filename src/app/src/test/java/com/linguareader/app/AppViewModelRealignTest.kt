package com.linguareader.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import com.linguareader.shared.translation.AlignedSentencePair
import com.linguareader.shared.translation.TranslationAligner
import com.linguareader.shared.translation.TranslationMemory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * 阶段 2 ViewModel 侧：档案版本闸门要**看得见**、重对齐要**有进度、有反馈**。
 *
 * 项目硬规则是「用户反馈走全局 Snackbar」——长任务（整本重对齐是数十秒级 CPU
 * 动作）期间必须有可感知的进行中状态，结束后无论成败都必须出一条提示。
 */
@RunWith(RobolectricTestRunner::class)
class AppViewModelRealignTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    /**
     * 内置词典 60 MB 的落盘副本在 Robolectric 下要整块读进堆：重对齐会经
     * `EcdictMeaningIndex` 触发它。本组用例不关心词义锚点，放一份**空表**迷你词典
     * 顶掉拷贝（列名照抄 `DictionarySql`），既省时间也避开 `OutOfMemoryError`。
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

    /** Robolectric 的 Main looper 是暂停的：泵它并等条件成立。 */
    private fun awaitCondition(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val shadow = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadow.idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun awaitRefresh(viewModel: AppViewModel) {
        assertTrue(
            "refresh() 未在超时内完成",
            awaitCondition { !viewModel.state.value.loading }
        )
    }

    /** 盘上造一本可打开、已配译本、档案由旧版对齐器写出的书。 */
    private fun bookWithArchive(id: String, translationBookId: String): Book {
        val dir = File(context.filesDir, "books/$id").apply { mkdirs() }
        val content = File(dir, "content").apply { mkdirs() }
        File(content, "chapter_000.xhtml")
            .writeText("<html><body><p>She carried a lantern.</p></body></html>")
        val book = Book(
            id = id,
            title = "Book $id",
            author = "Author",
            extractedDir = content.absolutePath,
            coverRelativePath = null,
            chapters = listOf(Chapter("Chapter 1", "chapter_000.xhtml")),
            addedAt = System.currentTimeMillis(),
            translationBookId = translationBookId,
            translationTitle = "旧译本",
            translationAlignedAt = 1L
        )
        File(dir, "metadata.json").writeText(book.toJson().toString())
        val memoryDir = File(context.filesDir, "translation-memory").apply { mkdirs() }
        File(memoryDir, "$id.json").writeText(
            TranslationMemory(
                sourceBookId = id,
                sourceTitle = book.title,
                translationBookId = translationBookId,
                translationTitle = "旧译本",
                alignedAt = 1L,
                pairs = listOf(
                    AlignedSentencePair(
                        enChapter = 0,
                        zhChapter = 0,
                        enParagraph = "She carried a lantern.",
                        zhParagraph = "她提着一盏灯。",
                        enSentence = "She carried a lantern.",
                        zhSentence = "她提着一盏灯。",
                        confidence = 0.9f
                    )
                ),
                // 旧版对齐器写出的档案（当前版本是 TranslationAligner.VERSION）。
                alignerVersion = 0
            ).toJson().toString()
        )
        return book
    }

    private fun archiveFile(bookId: String) =
        File(context.filesDir, "translation-memory/$bookId.json")

    private fun archiveVersion(bookId: String): Int =
        JSONObject(archiveFile(bookId).readText()).optInt("alignerVersion")

    @Test
    fun outdatedArchiveIsFlaggedAndRealignReportsProgressThenSuccess() {
        val book = bookWithArchive("gate-outdated", "imported-old")
        val viewModel = AppViewModel(context)
        awaitRefresh(viewModel)

        // ① 书架侧：过期档案被标出来（书卡提示「对照待更新」、译本菜单显示重对齐入口）。
        assertTrue("旧版档案应进 outdatedTranslations", book.id in viewModel.state.value.outdatedTranslations)

        viewModel.realignTranslation(book)

        // ② 进行中：立刻可见（书卡据此显示「对齐中…」并禁用按钮），不是静默长任务。
        assertTrue(
            "重对齐期间应进入 attachingTranslation",
            book.id in viewModel.state.value.attachingTranslation
        )
        assertEquals(
            context.getString(R.string.notice_translation_realigning, book.title),
            viewModel.state.value.notice
        )

        // ③ 完成：出成功 Snackbar，档案升到当前版本，过期标记消失。
        assertTrue(
            "重对齐没有在超时内报成功",
            awaitCondition {
                viewModel.state.value.attachingTranslation.isEmpty() &&
                    viewModel.state.value.noticeTone == StatusTone.SUCCESS
            }
        )
        assertEquals(TranslationAligner.VERSION, archiveVersion(book.id))
        assertNotNull(viewModel.state.value.notice)
        assertTrue(
            "重对齐成功后过期标记应清掉",
            awaitCondition { book.id !in viewModel.state.value.outdatedTranslations }
        )
    }

    @Test
    fun realignFailureIsReportedAsAnErrorNotice() {
        // 元数据说有译本，档案却不在（例如被清理掉了）：必须报错，不能静默。
        val book = bookWithArchive("gate-missing", "imported-gone")
        archiveFile(book.id).delete()
        val viewModel = AppViewModel(context)
        awaitRefresh(viewModel)
        assertFalse("没有档案不该判过期", book.id in viewModel.state.value.outdatedTranslations)

        viewModel.realignTranslation(book)

        assertTrue(
            "失败的 Snackbar 没出来",
            awaitCondition {
                viewModel.state.value.attachingTranslation.isEmpty() &&
                    viewModel.state.value.noticeTone == StatusTone.DANGER
            }
        )
        assertEquals(
            context.getString(R.string.notice_translation_realign_failed),
            viewModel.state.value.notice
        )
        assertFalse("失败不该凭空造出档案", archiveFile(book.id).exists())
    }
}
