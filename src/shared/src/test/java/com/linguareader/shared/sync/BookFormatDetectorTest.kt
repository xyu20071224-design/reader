package com.linguareader.shared.sync

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** [BookFormatDetector]：下载回来的无扩展名 blob 按魔数判格式。 */
class BookFormatDetectorTest {

    @Test
    fun detectsPdfByMagic() {
        assertEquals(BookFormatDetector.PDF, BookFormatDetector.detect("%PDF-1.7\n".toByteArray()))
    }

    @Test
    fun detectsEpubByZipHeader() {
        val epub = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00)
        assertEquals(BookFormatDetector.EPUB, BookFormatDetector.detect(epub))
    }

    @Test
    fun detectsFb2ByRootElement() {
        val fb2 = "<?xml version=\"1.0\" encoding=\"utf-8\"?><FictionBook xmlns=\"x\">".toByteArray()
        assertEquals(BookFormatDetector.FB2, BookFormatDetector.detect(fb2))
    }

    @Test
    fun fallsBackToTxtForPlainText() {
        assertEquals(BookFormatDetector.TXT, BookFormatDetector.detect("Chapter One\nIt was a dark night.\n".toByteArray()))
        assertEquals(BookFormatDetector.TXT, BookFormatDetector.detect(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'h'.code.toByte())))
    }

    @Test
    fun rejectsEmptyAndBinary() {
        assertEquals(BookFormatDetector.UNKNOWN, BookFormatDetector.detect(ByteArray(0)))
        assertEquals(BookFormatDetector.UNKNOWN, BookFormatDetector.detect(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
    }

    @Test
    fun detectFileHandlesMissingAndEmpty() {
        assertEquals(BookFormatDetector.UNKNOWN, BookFormatDetector.detect(File("/definitely/not/here.bin")))
        val dir = Files.createTempDirectory("lr-detect").toFile()
        try {
            val empty = File(dir, "empty")
            empty.writeBytes(ByteArray(0))
            assertEquals(BookFormatDetector.UNKNOWN, BookFormatDetector.detect(empty))

            val pdf = File(dir, "book")
            pdf.writeBytes("%PDF-1.4 rest".toByteArray())
            assertEquals(BookFormatDetector.PDF, BookFormatDetector.detect(pdf))

            val txt = File(dir, "note")
            txt.writeBytes("plain text body".toByteArray())
            assertEquals(BookFormatDetector.TXT, BookFormatDetector.detect(txt))
        } finally {
            dir.deleteRecursively()
        }
    }
}
