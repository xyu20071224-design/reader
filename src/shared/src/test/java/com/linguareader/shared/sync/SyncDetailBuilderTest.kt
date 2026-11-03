package com.linguareader.shared.sync

import com.linguareader.shared.data.Book
import com.linguareader.shared.data.SavedWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SyncDetailBuilder]：状态判定（outbox → 水位 → 无水位）、老词 addedAt 回退、
 * 正文 blob 三态、确定性排序、summary 计数。
 */
class SyncDetailBuilderTest {

    private fun book(id: String, title: String = id, progressUpdatedAt: Long = 0L) = Book(
        id = id,
        title = title,
        author = "Ada",
        extractedDir = "/device/books/" + id,
        coverRelativePath = null,
        chapters = emptyList(),
        addedAt = 1L,
        progressUpdatedAt = progressUpdatedAt
    )

    private fun word(
        id: String,
        headword: String = id,
        addedAt: Long = 100L,
        updatedAt: Long = 0L
    ) = SavedWord(
        id = id,
        headword = headword,
        phonetic = "",
        meaning = "n. 释义",
        sentence = "He studied hard.",
        bookId = "book-1",
        bookTitle = "The Lantern Library",
        chapterTitle = "One",
        addedAt = addedAt,
        updatedAt = updatedAt
    )

    private fun build(
        books: List<Book> = emptyList(),
        words: List<SavedWord> = emptyList(),
        syncedMarks: Map<String, Long> = emptyMap(),
        pendingKeys: Set<String> = emptySet(),
        remoteBlobSizes: Map<String, Long>? = null,
        remoteOnlyBooks: List<CloudBook> = emptyList()
    ) = SyncDetailBuilder.build(books, words, syncedMarks, pendingKeys, remoteBlobSizes, remoteOnlyBooks)

    @Test
    fun keysMatchSyncRecordKeys() {
        assertEquals("progress/book-1", SyncDetailBuilder.progressKey("book-1"))
        assertEquals("vocabulary/study", SyncDetailBuilder.wordKey("study"))
    }

    @Test
    fun outboxEntryIsPendingEvenWhenWatermarkIsCurrent() {
        // 水位 ≥ T 本应 SYNCED，但记录还在 outbox 里等推送 → PENDING 优先。
        val detail = build(
            books = listOf(book("b1", progressUpdatedAt = 100L)),
            syncedMarks = mapOf("progress/b1" to 500L),
            pendingKeys = setOf("progress/b1")
        )
        assertEquals(SyncItemState.PENDING, detail.books.single().progressState)
    }

    @Test
    fun watermarkAtOrAboveLocalTimestampIsSynced() {
        val detail = build(
            books = listOf(
                book("b1", progressUpdatedAt = 100L),
                book("b2", progressUpdatedAt = 100L)
            ),
            syncedMarks = mapOf("progress/b1" to 100L, "progress/b2" to 300L)
        )
        assertEquals(SyncItemState.SYNCED, detail.books.first { it.bookId == "b1" }.progressState)
        assertEquals(SyncItemState.SYNCED, detail.books.first { it.bookId == "b2" }.progressState)
    }

    @Test
    fun watermarkBehindLocalTimestampIsPending() {
        // 本地在水位之后又更新过，还没推上去。
        val detail = build(
            books = listOf(book("b1", progressUpdatedAt = 200L)),
            syncedMarks = mapOf("progress/b1" to 100L)
        )
        assertEquals(SyncItemState.PENDING, detail.books.single().progressState)
    }

    @Test
    fun missingWatermarkAndOutboxIsLocalOnly() {
        val detail = build(books = listOf(book("b1", progressUpdatedAt = 200L)))
        assertEquals(SyncItemState.LOCAL_ONLY, detail.books.single().progressState)
        assertEquals(1, detail.summary.localOnlyCount)
    }

    @Test
    fun wordWithZeroUpdatedAtFallsBackToAddedAt() {
        // addedAt=300 是参与判定的 T：水位 300 算已同步，299 算本地改过。
        val synced = build(
            words = listOf(word("w1", addedAt = 300L, updatedAt = 0L)),
            syncedMarks = mapOf("vocabulary/w1" to 300L)
        ).words.single()
        assertEquals(SyncItemState.SYNCED, synced.state)
        assertEquals(300L, synced.updatedAt)

        val pending = build(
            words = listOf(word("w2", addedAt = 300L, updatedAt = 0L)),
            syncedMarks = mapOf("vocabulary/w2" to 299L)
        ).words.single()
        assertEquals(SyncItemState.PENDING, pending.state)
    }

    @Test
    fun wordWithUpdatedAtUsesItInsteadOfAddedAt() {
        val item = build(
            words = listOf(word("w1", addedAt = 100L, updatedAt = 500L)),
            syncedMarks = mapOf("vocabulary/w1" to 400L)
        ).words.single()
        assertEquals(500L, item.updatedAt)
        assertEquals(SyncItemState.PENDING, item.state)
    }

    @Test
    fun wordWithoutWatermarkAndOutboxIsLocalOnly() {
        val item = build(words = listOf(word("w1", updatedAt = 500L))).words.single()
        assertEquals(SyncItemState.LOCAL_ONLY, item.state)
    }

    @Test
    fun sourceStateIsNullWhenRemoteBlobListUnknown() {
        val item = build(books = listOf(book("b1"))).books.single()
        assertNull(item.sourceState, "未拉到云端 blob 列表时只能是 null，不能当成没同步")
        assertEquals(0L, item.sourceSizeBytes)
    }

    @Test
    fun sourceStateReflectsRemoteBlobSizes() {
        val detail = build(
            books = listOf(book("a"), book("empty"), book("missing")),
            remoteBlobSizes = mapOf("a" to 1234L, "empty" to 0L)
        )
        val a = detail.books.first { it.bookId == "a" }
        assertEquals(SyncItemState.SYNCED, a.sourceState)
        assertEquals(1234L, a.sourceSizeBytes)

        val empty = detail.books.first { it.bookId == "empty" }
        assertEquals(SyncItemState.LOCAL_ONLY, empty.sourceState, "size=0 不算云端有完整正文")
        assertEquals(0L, empty.sourceSizeBytes)

        val missing = detail.books.first { it.bookId == "missing" }
        assertEquals(SyncItemState.LOCAL_ONLY, missing.sourceState)
        assertEquals(0L, missing.sourceSizeBytes)
    }

    @Test
    fun progressStateIsIndependentOfSourceState() {
        // 正文不在云端不代表进度没同步，两者必须分别判定。
        val item = build(
            books = listOf(book("b1", progressUpdatedAt = 100L)),
            syncedMarks = mapOf("progress/b1" to 100L),
            remoteBlobSizes = emptyMap()
        ).books.single()
        assertEquals(SyncItemState.SYNCED, item.progressState)
        assertEquals(SyncItemState.LOCAL_ONLY, item.sourceState)
    }

    @Test
    fun sortsBooksByTitleThenId() {
        val detail = build(
            books = listOf(book("id-2", "Same"), book("id-1", "Same"), book("id-3", "Aardvark"))
        )
        assertEquals(listOf("id-3", "id-1", "id-2"), detail.books.map { it.bookId })
    }

    @Test
    fun sortsWordsByLowercasedHeadwordThenId() {
        val detail = build(
            words = listOf(word("w2", "Study"), word("w1", "study"), word("w3", "Apple"))
        )
        assertEquals(listOf("w3", "w1", "w2"), detail.words.map { it.wordId })
    }

    @Test
    fun remoteOnlyBooksArePassedThrough() {
        val remote = listOf(CloudBook("cloud-1", 42L, "Remote Only"))
        val detail = build(remoteOnlyBooks = remote)
        assertEquals(remote, detail.remoteOnlyBooks)
    }

    @Test
    fun summaryCountsAllThreeStatesAcrossBooksAndWords() {
        val detail = build(
            books = listOf(
                book("b1", "Alpha", progressUpdatedAt = 100L),
                book("b2", "Beta", progressUpdatedAt = 300L)
            ),
            words = listOf(
                word("w1", "cat", addedAt = 10L, updatedAt = 100L),
                word("w2", "dog", addedAt = 500L, updatedAt = 0L),
                word("w3", "fox", addedAt = 500L, updatedAt = 0L)
            ),
            syncedMarks = mapOf(
                "progress/b1" to 100L,   // 书1进度：水位=本地 → SYNCED
                "progress/b2" to 200L,   // 书2进度：水位<本地 → PENDING
                "vocabulary/w1" to 100L  // 词1：水位=本地 → SYNCED
            ),
            pendingKeys = setOf("vocabulary/w2"),
            remoteBlobSizes = mapOf("b1" to 999L) // 书1正文 SYNCED；书2正文 LOCAL_ONLY
        )

        // 计入的状态共 2*2 + 3 = 7 条：
        // SYNCED = b1进度 + b1正文 + w1；PENDING = b2进度 + w2；LOCAL_ONLY = b2正文 + w3。
        assertEquals(
            SyncDetailSummary(syncedCount = 3, pendingCount = 2, localOnlyCount = 2),
            detail.summary
        )
        assertEquals(
            7,
            detail.summary.syncedCount + detail.summary.pendingCount + detail.summary.localOnlyCount
        )
    }

    @Test
    fun unreadBookWithZeroWatermarkIsNotSynced() {
        // 回归：从未读过的书 progressUpdatedAt=0，同步收尾会给它写水位 0；
        // 若直接按水位比较，0 >= 0 会把「从未推送过」误报成「已同步」。
        val detail = build(
            books = listOf(book("unread", progressUpdatedAt = 0L)),
            syncedMarks = mapOf("progress/unread" to 0L)
        )
        assertEquals(SyncItemState.LOCAL_ONLY, detail.books.single().progressState)
        assertEquals(1, detail.summary.localOnlyCount)
        assertEquals(0, detail.summary.syncedCount)
    }

    @Test
    fun zeroWatermarkMeansNeverUploaded() {
        // 水位 0 = 从未成功上行：即便本地时间戳很大也不能算已同步。
        val detail = build(
            books = listOf(book("b1", progressUpdatedAt = 500L)),
            syncedMarks = mapOf("progress/b1" to 0L)
        )
        assertEquals(SyncItemState.LOCAL_ONLY, detail.books.single().progressState)
    }

    @Test
    fun outboxStillWinsForUnreadBook() {
        // outbox 优先级在「时间戳/水位为 0」新规则之前，不能被吃掉。
        val detail = build(
            books = listOf(book("unread", progressUpdatedAt = 0L)),
            syncedMarks = mapOf("progress/unread" to 0L),
            pendingKeys = setOf("progress/unread")
        )
        assertEquals(SyncItemState.PENDING, detail.books.single().progressState)
    }

    @Test
    fun emptyInputsYieldEmptyDetail() {
        val detail = build()
        assertTrue(detail.books.isEmpty())
        assertTrue(detail.words.isEmpty())
        assertTrue(detail.remoteOnlyBooks.isEmpty())
        assertEquals(SyncDetailSummary(0, 0, 0), detail.summary)
    }
}
