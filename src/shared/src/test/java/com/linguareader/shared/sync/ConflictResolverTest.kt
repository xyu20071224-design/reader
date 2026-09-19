package com.linguareader.shared.sync

import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import com.linguareader.shared.data.SavedWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 冲突裁决的三类场景（对应验收标准 5 要在端到端复现的三个用例）：
 * 1) 离线修改后合并；2) 同一本书进度冲突；3) 同一笔记（自由文本）并发编辑。
 */
class ConflictResolverTest {

    private fun book(
        id: String = "book-1",
        extractedDir: String = "/local/books/book-1",
        chapterIndex: Int = 0,
        pageIndex: Int = 0,
        progress: Float = 0f,
        locusBlockIndex: Int = -1,
        locusAnchor: String = Book.ANCHOR_EXACT,
        ttsChapterIndex: Int = 0,
        ttsSentenceIndex: Int = 0,
        progressUpdatedAt: Long = 0L
    ) = Book(
        id = id,
        title = "A Test Book",
        author = "Ada",
        extractedDir = extractedDir,
        coverRelativePath = "cover.jpg",
        chapters = listOf(Chapter("One", "OPS/one.xhtml")),
        addedAt = 1L,
        chapterIndex = chapterIndex,
        pageIndex = pageIndex,
        progress = progress,
        locusBlockIndex = locusBlockIndex,
        locusAnchor = locusAnchor,
        ttsChapterIndex = ttsChapterIndex,
        ttsSentenceIndex = ttsSentenceIndex,
        progressUpdatedAt = progressUpdatedAt
    )

    private fun word(
        id: String = "study",
        meaning: String = "n. 学习",
        explanation: String = "",
        addedAt: Long = 100L,
        reviewLevel: Int = 0,
        reviewCount: Int = 0,
        nextReviewAt: Long = 0L,
        forms: List<String> = emptyList(),
        updatedAt: Long = 0L
    ) = SavedWord(
        id = id,
        headword = id,
        phonetic = "",
        meaning = meaning,
        aiExplanation = explanation,
        sentence = "He studied hard.",
        bookId = "book-1",
        bookTitle = "A Test Book",
        chapterTitle = "One",
        addedAt = addedAt,
        reviewLevel = reviewLevel,
        reviewCount = reviewCount,
        nextReviewAt = nextReviewAt,
        surfaceForms = forms,
        updatedAt = updatedAt
    )

    // ── 场景 1：离线修改后合并 ────────────────────────────────────────────

    @Test
    fun offlineProgressChangeMergesRemoteWhenNewer() {
        val local = book(chapterIndex = 1, pageIndex = 2, progressUpdatedAt = 1000L)
        val remote = book(chapterIndex = 5, pageIndex = 9, progress = .6f, locusBlockIndex = 41, progressUpdatedAt = 2000L)

        val merged = ConflictResolver.mergeProgress(local, remote)

        assertEquals(5, merged.chapterIndex)
        assertEquals(9, merged.pageIndex)
        assertEquals(41, merged.locusBlockIndex)
        assertEquals(2000L, merged.progressUpdatedAt)
    }

    @Test
    fun mergeProgressNeverTakesRemoteDeviceFields() {
        val local = book(extractedDir = "/local/books/book-1", progressUpdatedAt = 1000L)
        val remote = book(extractedDir = "/other-device/books/book-1", progressUpdatedAt = 2000L)

        val merged = ConflictResolver.mergeProgress(local, remote)

        assertEquals("/local/books/book-1", merged.extractedDir)
        assertEquals("cover.jpg", merged.coverRelativePath)
        assertEquals(local.chapters, merged.chapters)
    }

    // ── 场景 2：同一本书进度冲突 ──────────────────────────────────────────

    @Test
    fun sameBookProgressConflictKeepsNewerLocal() {
        val local = book(chapterIndex = 7, pageIndex = 3, progressUpdatedAt = 3000L)
        val remote = book(chapterIndex = 2, pageIndex = 1, progressUpdatedAt = 2000L)

        val merged = ConflictResolver.mergeProgress(local, remote)

        assertEquals(7, merged.chapterIndex)
        assertEquals(3, merged.pageIndex)
        assertEquals(3000L, merged.progressUpdatedAt)
    }

    @Test
    fun progressTieKeepsLocalButCanPreferRemote() {
        val local = book(chapterIndex = 1, progressUpdatedAt = 500L)
        val remote = book(chapterIndex = 9, progressUpdatedAt = 500L)

        assertEquals(1, ConflictResolver.mergeProgress(local, remote).chapterIndex)
        assertEquals(9, ConflictResolver.mergeProgress(local, remote, TieBreak.KEEP_REMOTE).chapterIndex)
    }

    @Test
    fun listeningPositionAlsoTravelsWithTheProgressVector() {
        val local = book(ttsChapterIndex = 1, ttsSentenceIndex = 2, progressUpdatedAt = 10L)
        val remote = book(ttsChapterIndex = 4, ttsSentenceIndex = 8, progressUpdatedAt = 20L)

        val merged = ConflictResolver.mergeProgress(local, remote)

        assertEquals(4, merged.ttsChapterIndex)
        assertEquals(8, merged.ttsSentenceIndex)
    }

    // ── 场景 3：同一笔记并发编辑（自由文本） ──────────────────────────────

    @Test
    fun sameNoteConcurrentEditKeepsLatestText() {
        assertEquals("remote edit", ConflictResolver.mergeText("local edit", 100L, "remote edit", 200L))
        assertEquals("local edit", ConflictResolver.mergeText("local edit", 200L, "remote edit", 100L))
        assertEquals("local edit", ConflictResolver.mergeText("local edit", 100L, "remote edit", 100L))
        assertEquals("remote edit", ConflictResolver.mergeText("local edit", 100L, "remote edit", 100L, TieBreak.KEEP_REMOTE))
    }

    // ── 生词卡字段级合并 ──────────────────────────────────────────────────

    @Test
    fun savedWordMergesReviewStateMonotonically() {
        val local = word(reviewLevel = 1, reviewCount = 2, nextReviewAt = 500L, updatedAt = 10L)
        val remote = word(reviewLevel = 3, reviewCount = 4, nextReviewAt = 900L, updatedAt = 20L)

        val merged = ConflictResolver.mergeSavedWord(local, remote)

        assertEquals(3, merged.reviewLevel)
        assertEquals(4, merged.reviewCount)
        assertEquals(900L, merged.nextReviewAt)
    }

    @Test
    fun savedWordKeepsEarlierAddedAtAndUnionsSurfaceForms() {
        val local = word(addedAt = 100L, forms = listOf("studied", "studying"), updatedAt = 10L)
        val remote = word(addedAt = 50L, forms = listOf("Studying", "studies"), updatedAt = 20L)

        val merged = ConflictResolver.mergeSavedWord(local, remote)

        assertEquals(50L, merged.addedAt)
        assertEquals(listOf("studied", "studying", "studies"), merged.surfaceForms)
    }

    @Test
    fun savedWordTextFollowsLatestEdit() {
        val local = word(meaning = "local meaning", explanation = "local why", updatedAt = 10L)
        val remote = word(meaning = "remote meaning", explanation = "remote why", updatedAt = 20L)

        val merged = ConflictResolver.mergeSavedWord(local, remote)

        assertEquals("remote meaning", merged.meaning)
        assertEquals("remote why", merged.aiExplanation)
        assertEquals(20L, merged.updatedAt)
    }

    // ── 老数据（无版本戳）───────────���──────────────────────────────────────

    @Test
    fun legacyZeroTimestampsFallBackToTieBreak() {
        val local = book(chapterIndex = 1, progressUpdatedAt = 0L)
        val remote = book(chapterIndex = 2, progressUpdatedAt = 0L)

        assertEquals(1, ConflictResolver.mergeProgress(local, remote).chapterIndex)
        assertEquals(2, ConflictResolver.mergeProgress(local, remote, TieBreak.KEEP_REMOTE).chapterIndex)

        val localWord = word(meaning = "local", updatedAt = 0L)
        val remoteWord = word(meaning = "remote", updatedAt = 0L)
        assertEquals("local", ConflictResolver.mergeSavedWord(localWord, remoteWord).meaning)
    }

    @Test
    fun genericLastWriteWinsIsStable() {
        assertEquals("a", ConflictResolver.lastWriteWins("a", 1L, "b", 0L))
        assertEquals("b", ConflictResolver.lastWriteWins("a", 1L, "b", 1L, TieBreak.KEEP_REMOTE))
        assertTrue(ConflictResolver.mergeText("x", 5L, "x", 5L) == "x")
    }
}
