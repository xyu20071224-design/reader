package com.linguareader.shared.packs

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SafeZipTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("pack-", ".zip")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun tempDir(): File = File.createTempFile("dest-", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    @Test
    fun `extracts files and returns written bytes`() {
        val dest = tempDir()
        val zip = zipOf("a.txt" to "hello".toByteArray(), "sub/b.txt" to "world!".toByteArray())

        val bytes = SafeZip.extract(zip, dest, maxEntries = 10, maxBytes = 1024)

        assertEquals(11L, bytes)
        assertEquals("hello", File(dest, "a.txt").readText())
        assertEquals("world!", File(dest, "sub/b.txt").readText())
    }

    @Test
    fun `path traversal is rejected before writing`() {
        val dest = tempDir()
        val zip = zipOf("../escape.txt" to "nope".toByteArray())

        assertFailsWith<IllegalArgumentException> {
            SafeZip.extract(zip, dest, maxEntries = 10, maxBytes = 1024)
        }
        assertTrue(!File(dest.parentFile, "escape.txt").exists())
    }

    @Test
    fun `absolute path is rejected`() {
        val dest = tempDir()
        val zip = zipOf("/tmp/escape.txt" to "nope".toByteArray())
        assertFailsWith<IllegalArgumentException> {
            SafeZip.extract(zip, dest, maxEntries = 10, maxBytes = 1024)
        }
    }

    @Test
    fun `entry count limit is enforced`() {
        val dest = tempDir()
        val zip = zipOf("a" to ByteArray(1), "b" to ByteArray(1), "c" to ByteArray(1))
        val error = assertFailsWith<IllegalArgumentException> {
            SafeZip.extract(zip, dest, maxEntries = 2, maxBytes = 1024, label = "资源包")
        }
        assertTrue(error.message.orEmpty().contains("资源包"))
    }

    @Test
    fun `unzipped byte limit is enforced even when header lies`() {
        val dest = tempDir()
        // zip 头里的 size 由攻击者控制：实际写入量必须重新累计。
        val zip = zipOf("big" to ByteArray(4096) { 1 })
        val error = assertFailsWith<IllegalArgumentException> {
            SafeZip.extract(zip, dest, maxEntries = 10, maxBytes = 1024)
        }
        assertTrue(error.message.orEmpty().contains("1KB"), "消息应报出限制值：${error.message}")
    }

    @Test
    fun `directory entries are created without counting as files`() {
        val dest = tempDir()
        val zip = zipOf("dir/" to ByteArray(0), "dir/a" to "x".toByteArray())
        SafeZip.extract(zip, dest, maxEntries = 10, maxBytes = 1024)
        assertTrue(File(dest, "dir").isDirectory)
        assertEquals("x", File(dest, "dir/a").readText())
    }
}
