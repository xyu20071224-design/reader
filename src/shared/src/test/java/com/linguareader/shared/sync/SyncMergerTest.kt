package com.linguareader.shared.sync

import com.linguareader.shared.data.Book
import com.linguareader.shared.data.SavedWord
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 领域适配层：编解码往返 + 按集合分派的合并语义（进度/生词/术语备注/偏好/墓碑）。 */
class SyncMergerTest {

    private fun book(
        id: String = "book-1",
        progressUpdatedAt: Long = 0L,
        chapterIndex: Int = 0,
        locusBlockIndex: Int = -1,
        progress: Float = 0f
    ) = Book(
        id = id,
        title = "The Lantern Library",
        author = "Ada",
        extractedDir = "/device/books/" + id,
        coverRelativePath = "cover.jpg",
        chapters = emptyList(),
        addedAt = 1L,
        chapterIndex = chapterIndex,
        locusBlockIndex = locusBlockIndex,
        progress = progress,
        progressUpdatedAt = progressUpdatedAt
    )

    private fun word(
        id: String = "study",
        meaning: String = "n. 学习",
        level: Int = 0,
        count: Int = 0,
        updatedAt: Long = 0L
    ) = SavedWord(
        id = id, headword = id, phonetic = "", meaning = meaning,
        sentence = "He studied hard.", bookId = "book-1", bookTitle = "The Lantern Library",
        chapterTitle = "One", addedAt = 100L, reviewLevel = level, reviewCount = count, updatedAt = updatedAt
    )

    @Test
    fun codecRoundTripsProgressWithoutDeviceFields() {
        val record = SyncPayloadCodec.progressRecord(book(progressUpdatedAt = 500, chapterIndex = 7, locusBlockIndex = 33, progress = 0.5f))

        assertTrue(!record.payload.has("extractedDir"), "设备本地路径不得进入同步 payload")
        assertTrue(!record.payload.has("chapters"))
        val restored = SyncPayloadCodec.bookFromProgress(record)
        assertEquals("book-1", restored.id)
        assertEquals("The Lantern Library", restored.title)
        assertEquals(7, restored.chapterIndex)
        assertEquals(33, restored.locusBlockIndex)
        assertEquals(500L, restored.progressUpdatedAt)
    }

    @Test
    fun codecRoundTripsSavedWord() {
        val original = word(meaning = "n. 学习；研究", level = 2, count = 5, updatedAt = 900)
        val restored = SyncPayloadCodec.savedWordFrom(SyncPayloadCodec.savedWordRecord(original))
        assertEquals(original, restored)
    }

    @Test
    fun progressMergeKeepsNewerLocus() {
        val local = SyncPayloadCodec.progressRecord(book(progressUpdatedAt = 100, chapterIndex = 1, locusBlockIndex = 5, progress = 0.1f))
        val remote = SyncPayloadCodec.progressRecord(book(progressUpdatedAt = 200, chapterIndex = 9, locusBlockIndex = 40, progress = 0.8f))

        val merged = SyncMerger.merge(local, remote, now = 1000)

        assertEquals(9, merged.payload.getInt("chapterIndex"))
        assertEquals(40, merged.payload.getInt("locusBlockIndex"))
        assertEquals(0.8, merged.payload.getDouble("progress"), 1e-6)
        assertTrue(merged.updatedAt > 200, "合并后的版本戳必须超过双方，才能在下一次推送胜出")
    }

    @Test
    fun savedWordMergeCombinesTextAndReviewState() {
        val local = SyncPayloadCodec.savedWordRecord(word(meaning = "n. 学习；研究", level = 0, count = 0, updatedAt = 400))
        val remote = SyncPayloadCodec.savedWordRecord(word(meaning = "n. 学习", level = 3, count = 7, updatedAt = 200))

        val merged = SyncMerger.merge(local, remote, now = 1000)
        val payload = merged.payload

        assertEquals("n. 学习；研究", payload.getString("meaning"))
        assertEquals(3, payload.getInt("reviewLevel"))
        assertEquals(7, payload.getInt("reviewCount"))
    }

    @Test
    fun glossaryNoteKeepsLatestEdit() {
        val local = SyncPayloadCodec.glossaryRecord("book-1", "Gandalf", "character", "灰袍", 100)
        val remote = SyncPayloadCodec.glossaryRecord("book-1", "Gandalf", "character", "白袍（重生后）", 300)

        val merged = SyncMerger.merge(local, remote, now = 1000)
        assertEquals("白袍（重生后）", merged.payload.getString("note"))
        assertEquals("book-1::Gandalf", merged.id)
    }

    @Test
    fun preferenceKeepsLatestValue() {
        val local = SyncPayloadCodec.preferenceRecord("reader_preferences.fontPercent", "100", 100)
        val remote = SyncPayloadCodec.preferenceRecord("reader_preferences.fontPercent", "130", 250)

        // LWW 与参数顺序无关：两次调用都必须收敛到更晚的值。
        assertEquals("130", SyncMerger.merge(local, remote, now = 1000).payload.getString("value"))
        assertEquals("130", SyncMerger.merge(remote, local, now = 1000).payload.getString("value"))
    }

    @Test
    fun deletionWinsOverOlderEdit() {
        val local = SyncPayloadCodec.savedWordRecord(word(updatedAt = 100))
        val remote = SyncRecord(
            collection = SyncCollections.VOCABULARY,
            id = "study",
            updatedAt = 200,
            deletedAt = 200,
            payload = JSONObject()
        )

        val merged = SyncMerger.merge(local, remote, now = 1000)
        assertEquals(200L, merged.deletedAt)
        assertTrue(merged.updatedAt > 200)
    }

    @Test
    fun localDeletionIsNotResurrectedByOlderEdit() {
        val local = SyncRecord(SyncCollections.VOCABULARY, "study", 300, 300, JSONObject())
        val remote = SyncPayloadCodec.savedWordRecord(word(updatedAt = 100))

        val merged = SyncMerger.merge(local, remote, now = 1000)
        assertEquals(300L, merged.deletedAt)
    }

    @Test
    fun mergeRejectsDifferentKeys() {
        val local = SyncPayloadCodec.preferenceRecord("a", "1", 1)
        val remote = SyncPayloadCodec.preferenceRecord("b", "2", 2)
        assertFailsWith<IllegalArgumentException> { SyncMerger.merge(local, remote, now = 3) }
    }
}
