package com.linguareader.app.state

import com.linguareader.app.AppUiState
import com.linguareader.app.data.Book
import com.linguareader.shared.ui.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拆分的验收标准：**AppUiState 的每个字段都有归属**。
 *
 * 用反射而不是手写断言：手写的字段清单只能证明「我今天记得」，反射能证明
 * 「以后加字段忘了分片会红」。项目里已有同类先例（ReaderScreenStateTest 的字段数守卫）。
 */
class AppUiStateSplitTest {

    @Test
    fun everyAppUiStateFieldHasExactlyOneHome() {
        val actual = AppUiStateMapping.actualFields()
        val ledger = AppUiStateMapping.ALL_FIELDS

        val unowned = actual - ledger
        assertTrue(
            "这些字段还没分片（加字段必须同时更新 AppUiStateMapping 与对应切片）：$unowned",
            unowned.isEmpty()
        )

        val stale = ledger - actual
        assertTrue(
            "账本里有、AppUiState 里没有的字段（字段被删了却没清账）：$stale",
            stale.isEmpty()
        )
    }

    @Test
    fun ledgerHasNoDuplicatesAndCoversTheWholeClass() {
        // 重叠字段在 ALL_FIELDS 里会被去重，所以两边数量必须相等；
        // 不等于就说明账本写重了（同一个字段被记到两个互斥切片里）。
        assertEquals(
            "账本去重后的字段数必须等于 AppUiState 的字段数",
            AppUiStateMapping.actualFields().size,
            AppUiStateMapping.ALL_FIELDS.size
        )
        assertTrue("AppUiState 至少要有一个字段", AppUiStateMapping.actualFields().isNotEmpty())
    }

    @Test
    fun splitProjectsEverySliceFromOneState() {
        val state = AppUiState(
            books = listOf(
                Book(
                    id = "book-1",
                    title = "The Hobbit",
                    author = "Tolkien",
                    extractedDir = "dir",
                    coverRelativePath = null,
                    chapters = emptyList(),
                    addedAt = 0L
                )
            ),
            loading = false,
            notice = "已导入",
            message = "提示",
            messageTitle = "标题"
        )

        val slices = state.split()

        assertEquals(1, slices.bookshelf.books.size)
        assertEquals("已导入", slices.shell.notice)
        assertEquals("提示", slices.shell.message)
        assertEquals("标题", slices.shell.messageTitle)
        assertFalse(slices.shell.loading)
        // 没有当前书 → 书架；有书 → 阅读页。这就是 MainActivity.kt:155 的二态判断。
        assertEquals(Route.Bookshelf, slices.shell.route)
        assertFalse(slices.shell.isReading)

        val reading = state.copy(currentBook = state.books.single()).split()
        assertEquals(Route.Reader("book-1"), reading.shell.route)
        assertTrue(reading.shell.isReading)
        assertEquals("book-1", reading.reader.book?.id)
        assertTrue(reading.reader.isOpen)
    }

    @Test
    fun overlappingFieldsStayConsistentAcrossSlices() {
        val state = AppUiState(
            reviewPreset = null,
            savedWords = emptyList()
        )
        val slices = state.split()

        // 复习节奏同时进 Reader/Vocab 两个切片：必须是同一份真相，不能各算一套。
        assertEquals(slices.reader.reviewPace, slices.vocab.reviewPace)
        assertEquals(state.reviewPace, slices.reader.reviewPace)
        assertEquals(slices.reader.savedWords, slices.vocab.savedWords)
        assertEquals(slices.reader.aiSettings, slices.settings.aiSettings)
    }

    @Test
    fun settingsSliceCarriesDefaultsUnchanged() {
        val slices = AppUiState().split()
        assertFalse("出厂不得联网", slices.settings.aiSettings.enabled)
        assertFalse(slices.settings.syncSettings.enabled)
        assertTrue(slices.settings.cloudBooks.isEmpty())
        assertFalse(slices.settings.cloudBooksLoaded)
        // 应用专属残留：迁移期照原样搬运，不做改造。
        assertNull(slices.appOnly.launchPrompt)
        assertTrue(slices.appOnly.aiTranslationProgress.isEmpty())
        assertTrue(slices.appOnly.packs.items.isEmpty())
    }

    @Test
    fun noticeToneMapsAcrossTheBoundary() {
        listOf(
            com.linguareader.app.StatusTone.SUCCESS,
            com.linguareader.app.StatusTone.DANGER,
            com.linguareader.app.StatusTone.NEUTRAL
        ).forEach { tone ->
            val mapped = AppUiState(noticeTone = tone).split().shell.noticeTone
            assertEquals("提示语义跨层后必须保持一一对应", tone.name, mapped.name)
        }
    }
}
