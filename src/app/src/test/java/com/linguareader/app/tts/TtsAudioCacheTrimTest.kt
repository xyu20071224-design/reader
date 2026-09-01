package com.linguareader.app.tts

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D2.3 音频缓存的容量上限与淘汰。
 *
 * 背景：tts_cache 此前**无上限、无淘汰、无清理入口**，且在 filesDir 而非 cacheDir，
 * 系统的低存储回收够不着它 —— 长期听书会无界增长（估算 20–50 MB/本，十几本整书
 * 缓存就逼近 GB）。
 */
@RunWith(RobolectricTestRunner::class)
class TtsAudioCacheTrimTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val cache = TtsAudioCache(context)

    /** 引擎身份：本组用例只关心淘汰，固定一个即可。 */
    private val ENGINE = "server:http://localhost:8000"

    /** 写一个淘汰单元：<book>/<chapter>/<voice>/ 下若干句，并指定最后修改时间。 */
    private fun seed(bookId: String, chapter: Int, voice: String, bytes: Int, modifiedAt: Long) {
        val file = cache.fileFor(bookId, chapter, 0, voice, ENGINE)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
        file.setLastModified(modifiedAt)
    }

    private fun book(id: String) = Book(
        id = id,
        title = id,
        author = "",
        extractedDir = File(context.filesDir, "books/" + id).absolutePath,
        coverRelativePath = null,
        chapters = listOf(Chapter("c", "c.xhtml")),
        addedAt = 0L
    )

    @Test
    fun oldestEntriesAreEvictedUntilUnderTheLimit() {
        seed("b1", 0, "v", 400, modifiedAt = 1_000L)
        seed("b1", 1, "v", 400, modifiedAt = 2_000L)
        seed("b2", 0, "v", 400, modifiedAt = 3_000L)
        assertEquals(1200L, cache.totalBytes())

        val freed = cache.trimTo(900L)

        assertEquals(400L, freed)
        assertEquals(800L, cache.totalBytes())
        // 最旧的那个单元被清掉，其余原样
        assertFalse(cache.fileFor("b1", 0, 0, "v", ENGINE).exists())
        assertTrue(cache.fileFor("b1", 1, 0, "v", ENGINE).exists())
        assertTrue(cache.fileFor("b2", 0, 0, "v", ENGINE).exists())
    }

    /** 正在听的东西被删掉会当场触发重新合成 —— 云 TTS 那是花钱的。 */
    @Test
    fun theChapterBeingPlayedIsNeverEvicted() {
        seed("playing", 3, "v", 500, modifiedAt = 1_000L) // 最旧，但正在听
        seed("other", 0, "v", 500, modifiedAt = 2_000L)

        cache.trimTo(600L, protectBookId = "playing", protectChapterIndex = 3)

        assertTrue(cache.fileFor("playing", 3, 0, "v", ENGINE).exists())
        assertFalse(cache.fileFor("other", 0, 0, "v", ENGINE).exists())
    }

    /** 用户可以选「不限」——离线听书是核心场景，硬上限会伤到「出门前缓存整本」。 */
    @Test
    fun unlimitedQuotaNeverEvicts() {
        seed("b1", 0, "v", 4_096, modifiedAt = 1_000L)

        assertEquals(0L, cache.trimTo(0L))
        assertEquals(0L, cache.trimTo(-1L))
        assertTrue(cache.fileFor("b1", 0, 0, "v", ENGINE).exists())
    }

    /** 目录名不合规（章号不是数字）时跳过，不猜、也不误删。 */
    @Test
    fun malformedDirectoriesAreIgnoredNotGuessed() {
        seed("b1", 0, "v", 100, modifiedAt = 1_000L)
        val junk = File(context.filesDir, "tts_cache/b1/not-a-chapter/v/0.mp3")
        junk.parentFile?.mkdirs()
        junk.writeBytes(ByteArray(999))

        assertEquals(100L, cache.totalBytes())
        cache.trimTo(1L)
        assertTrue(junk.exists(), "不认识的目录不该被当成淘汰对象删掉")
    }

    /** 删书仍然整本清掉，与配额无关。 */
    @Test
    fun deletingABookStillClearsItsWholeCache() = kotlinx.coroutines.runBlocking<Unit> {
        seed("gone", 0, "v", 100, modifiedAt = 1_000L)
        cache.deleteBookData(book("gone"))
        assertFalse(cache.fileFor("gone", 0, 0, "v", ENGINE).exists())
    }
}