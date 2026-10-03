package com.linguareader.shared.sync

import com.linguareader.shared.data.Book
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [BookSourceFile]：扩展名映射、留存文件定位、老书（无留存）返回 null。 */
class BookSourceFileTest {

    private fun book(dir: String, format: String) = Book(
        id = "a1b2c3d4e5f60718293a",
        title = "t",
        author = "a",
        extractedDir = dir,
        coverRelativePath = null,
        chapters = emptyList(),
        addedAt = 0L,
        sourceFormat = format
    )

    @Test
    fun extensionFollowsSourceFormat() {
        assertEquals("epub", BookSourceFile.extensionFor("epub"))
        assertEquals("txt", BookSourceFile.extensionFor("TXT"))
        assertEquals("fb2", BookSourceFile.extensionFor(" fb2 "))
        assertEquals("pdf", BookSourceFile.extensionFor("pdf"))
        // 未知格式退化为 bin，仍可上传
        assertEquals("bin", BookSourceFile.extensionFor("mobi"))
        assertEquals("bin", BookSourceFile.extensionFor(""))
        assertEquals("source.epub", BookSourceFile.fileNameFor("epub"))
        assertEquals("source.bin", BookSourceFile.fileNameFor("mobi"))
    }

    @Test
    fun locateFindsRetainedSourceFile() {
        val dir = Files.createTempDirectory("lr-source").toFile()
        try {
            File(dir, "source.epub").writeBytes(ByteArray(16) { 1 })
            val found = BookSourceFile.locate(book(dir.absolutePath, "epub"))
            assertEquals(File(dir, "source.epub"), found)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun locateReturnsNullForLegacyBooksWithoutSource() {
        val dir = Files.createTempDirectory("lr-source").toFile()
        try {
            // 只有解析产物（chapter_xhtml / metadata.json），没有留存源文件
            File(dir, "chapter_001.xhtml").writeText("<html/>")
            assertNull(BookSourceFile.locate(book(dir.absolutePath, "epub")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun locateRejectsBlankDirAndEmptyFile() {
        assertNull(BookSourceFile.locate(book("", "epub")))
        val dir = Files.createTempDirectory("lr-source").toFile()
        try {
            File(dir, "source.txt").writeText("")
            assertNull(BookSourceFile.locate(book(dir.absolutePath, "txt")))
        } finally {
            dir.deleteRecursively()
        }
    }
}
