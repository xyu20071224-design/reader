package com.linguareader.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [CloudBookList]：远端有/本机没有的比对、去重、书名回退。 */
class CloudBookListTest {

    private fun blob(id: String, size: Long) = BlobInfo(id, size, "sha")

    @Test
    fun keepsOnlyBlobsMissingLocally() {
        val result = CloudBookList.missing(
            remote = listOf(blob("book-a", 100), blob("book-b", 200), blob("book-c", 300)),
            localBookIds = setOf("book-b")
        )
        assertEquals(listOf("book-a", "book-c"), result.map { it.bookId })
        assertEquals(listOf(100L, 300L), result.map { it.sizeBytes })
    }

    @Test
    fun dropsBlankIdsIncompleteAndDuplicates() {
        val result = CloudBookList.missing(
            remote = listOf(
                blob("", 10),
                blob("zero", 0),
                blob("dup", 5),
                blob("dup", 5),
                blob("ok", 7)
            ),
            localBookIds = emptySet()
        )
        assertEquals(listOf("dup", "ok"), result.map { it.bookId })
    }

    @Test
    fun displayNamePrefersTitleThenFallsBackToShortId() {
        val withTitle = CloudBook("a1b2c3d4e5f6", 10, "  The Hobbit  ")
        // title 已 trim
        assertEquals("The Hobbit", CloudBookList.missing(
            listOf(BlobInfo("a1b2c3d4e5f6", 10, "sha")), emptySet(), mapOf("a1b2c3d4e5f6" to "  The Hobbit  ")
        ).single().displayName)
        assertEquals("The Hobbit", withTitle.displayName)

        val noTitle = CloudBook("a1b2c3d4e5f6", 10)
        assertEquals("a1b2c3d4", noTitle.displayName)
    }

    @Test
    fun sortsByDisplayName() {
        val result = CloudBookList.missing(
            remote = listOf(blob("ffffffff", 1), blob("00000000", 1)),
            localBookIds = emptySet(),
            titles = mapOf("ffffffff" to "Alpha", "00000000" to "Beta")
        )
        assertEquals(listOf("Alpha", "Beta"), result.map { it.displayName })
    }

    @Test
    fun emptyRemoteYieldsEmptyList() {
        assertTrue(CloudBookList.missing(emptyList(), setOf("x")).isEmpty())
    }
}
