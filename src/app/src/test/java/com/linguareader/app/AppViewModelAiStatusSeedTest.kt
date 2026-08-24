package com.linguareader.app

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.ai.AiBookStatus
import com.linguareader.app.ai.BookContextProfile
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * 书架「AI 语境」标签的状态漂移修复（2026-08-24 真机观察：待生成↔就绪往返）。
 *
 * 根因：aiStatuses 只活在内存里，进程被系统回收后重建即清零 —— 磁盘上明明有
 * 档案的书也显示「待生成」，打开书时 generate() 命中磁盘档案又变「就绪」。
 * 修复：refresh() 以磁盘档案存在性播种就绪态。这里模拟「冷启动重建」。
 */
@RunWith(RobolectricTestRunner::class)
class AppViewModelAiStatusSeedTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun book(id: String): Book {
        val dir = File(context.filesDir, "books/$id").apply { mkdirs() }
        val extracted = File(dir, "content").apply { mkdirs() }
        return Book(
            id = id,
            title = "Book $id",
            author = "Author",
            extractedDir = extracted.absolutePath,
            coverRelativePath = null,
            chapters = listOf(Chapter("Chapter 1", "chapter1.xhtml")),
            addedAt = System.currentTimeMillis()
        )
    }

    private fun writeBook(book: Book) {
        File(File(context.filesDir, "books/${book.id}"), "metadata.json")
            .writeText(book.toJson().toString())
    }

    private fun writeProfile(book: Book) {
        val dir = File(context.filesDir, "ai/book-context").apply { mkdirs() }
        File(dir, "${book.id}.json")
            .writeText(BookContextProfile(bookId = book.id, bookTitle = book.title).toJson().toString())
    }

    /** AppViewModel 的 refresh() 在 viewModelScope（Main）上异步跑完。 */
    private fun awaitRefresh(viewModel: AppViewModel) {
        val shadow = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.state.value.loading && System.currentTimeMillis() < deadline) {
            shadow.idle()
            if (!viewModel.state.value.loading) break
            Thread.sleep(20)
        }
        assertFalse("refresh() 未在超时内完成", viewModel.state.value.loading)
    }

    @Test
    fun processRestartSeedsReadyFromExistingProfileFile() {
        val withProfile = book("seed-ready")
        val withoutProfile = book("seed-pending")
        writeBook(withProfile)
        writeBook(withoutProfile)
        writeProfile(withProfile)

        // 冷启动等价物：全新 ViewModel，内存 aiStatuses 为空。
        val viewModel = AppViewModel(context)
        awaitRefresh(viewModel)

        assertEquals(
            AiBookStatus(ready = true),
            viewModel.state.value.aiStatuses[withProfile.id]
        )
        // 没有档案文件的书保持无状态（书架显示「待生成」是真实的）。
        assertFalse(viewModel.state.value.aiStatuses.containsKey(withoutProfile.id))
    }

    @Test
    fun seedingDoesNotClobberExistingErrorStatus() {
        val failing = book("seed-error")
        writeBook(failing)
        // 磁盘上也有档案文件，但内存里已有 error 状态：不得被「就绪」播种覆盖。
        writeProfile(failing)
        val viewModel = AppViewModel(context)
        awaitRefresh(viewModel)
        viewModel.setAiStatus(failing.id, AiBookStatus(error = "boom"))

        viewModel.refresh()
        awaitRefresh(viewModel)

        assertEquals(
            AiBookStatus(error = "boom"),
            viewModel.state.value.aiStatuses[failing.id]
        )
        assertTrue(viewModel.state.value.books.any { it.id == failing.id })
    }
}
