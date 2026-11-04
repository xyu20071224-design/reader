package com.linguareader.app.state

import com.linguareader.app.AppUiState
import com.linguareader.shared.ui.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **`ShellState.route` 与原二元判断的等价性**（P15）。
 *
 * 接线前 `MainActivity` 用的是：
 * ```
 * if (currentBook == null) Route.Bookshelf else Route.Reader(currentBook.id)
 * ```
 * 接线后用的是 `state.split().shell.route`。
 *
 * **接线不该改行为** —— 所以这里把两种写法在关键输入上的结果钉死，
 * 保证 `AppSlices.kt` 里那条投影写错了会被发现（而不是等真机上看出来）。
 */
class ShellRouteEquivalenceTest {

    private fun expected(bookId: String?): Route =
        if (bookId == null) Route.Bookshelf else Route.Reader(bookId)

    @Test
    fun noBookProjectsToBookshelf() {
        val state = AppUiState()
        assertEquals(Route.Bookshelf, state.split().shell.route)
        assertEquals(expected(null), state.split().shell.route)
    }

    @Test
    fun openBookProjectsToReaderWithSameId() {
        // 用真实 Book 构造状态（字段太多，只验证 route 的映射规则）。
        val book = com.linguareader.shared.data.Book(
            id = "book-42",
            title = "T",
            author = "A",
            extractedDir = "/tmp/nonexistent/book-42",
            coverRelativePath = null,
            chapters = emptyList(),
            addedAt = 0L
        )
        val state = AppUiState(currentBook = book)
        val route = state.split().shell.route
        assertEquals(Route.Reader("book-42"), route)
        assertEquals(expected("book-42"), route)
    }
}