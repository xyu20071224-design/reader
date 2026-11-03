package com.linguareader.shared.ui.state

import com.linguareader.shared.ui.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 切片本身的行为断言：默认值、派生字段、投影重叠。 */
class StateSlicesTest {

    @Test
    fun shellDefaultsToBookshelfAndReadingFlag() {
        val shell = ShellState()
        assertEquals(Route.Bookshelf, shell.route)
        assertFalse(shell.isReading)
        assertTrue(shell.loading)
        assertEquals(NoticeTone.NEUTRAL, shell.noticeTone)

        val reading = shell.copy(route = Route.Reader("book-1"), loading = false)
        assertTrue(reading.isReading)
        assertFalse(reading.loading)
    }

    @Test
    fun readerAndVocabShareTheSameEffectivePace() {
        val reader = ReaderState()
        val vocab = VocabState()
        assertEquals(reader.reviewPace, vocab.reviewPace, "两个切片读的是同一份复习节奏")
        assertEquals(com.linguareader.shared.data.ReviewMode.DEFAULT.toPace(), reader.reviewPace)

        val custom = ReaderState(reviewPreset = null)
        assertEquals(custom.customReview, custom.reviewPace, "预设为空时回落到自定义节奏")
    }

    @Test
    fun readerIsOpenOnlyWithABook() {
        assertFalse(ReaderState().isOpen)
        val book = com.linguareader.shared.data.Book(
            id = "b1",
            title = "T",
            author = "A",
            extractedDir = "dir",
            coverRelativePath = null,
            chapters = emptyList(),
            addedAt = 0L
        )
        assertTrue(ReaderState(book = book).isOpen)
    }

    @Test
    fun bookshelfEmptyStateDependsOnLoading() {
        val state = BookshelfState()
        assertFalse(state.isEmptyAfterLoad(loading = true), "还在加载时不能显示空书架引导")
        assertTrue(state.isEmptyAfterLoad(loading = false))
    }

    @Test
    fun settingsDefaultsAreOffline() {
        val settings = SettingsState()
        assertFalse(settings.aiSettings.enabled, "出厂不得联网")
        assertFalse(settings.syncSettings.enabled)
        assertFalse(settings.syncBusy)
        assertTrue(settings.cloudBooks.isEmpty())
    }
}
