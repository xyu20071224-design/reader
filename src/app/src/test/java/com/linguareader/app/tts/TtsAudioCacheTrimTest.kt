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
        val file = cache.fileFor(bookId, chapter, 0, 0, voice, ENGINE)
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

    /**
     * 第四轮审查 M6：整书缓存路径此前 `protectChapterIndex = null` ⇒ 保护**整本**，
     * 单本书超过上限时永不被淘汰，配额形同失效。改为只保该书最近写入的一章。
     */
    @Test
    fun wholeBookPathProtectsOnlyTheNewestChapterNotTheWholeBook() {
        // 同一本书 4 章，共 1600B；上限压到 400B
        seed("big", 0, "v", 400, modifiedAt = 1_000L)
        seed("big", 1, "v", 400, modifiedAt = 2_000L)
        seed("big", 2, "v", 400, modifiedAt = 3_000L)
        seed("big", 3, "v", 400, modifiedAt = 4_000L)
        assertEquals(1600L, cache.totalBytes())

        val freed = cache.trimTo(
            limitBytes = 400L,
            protectBookId = "big",
            protectChapterIndex = null,
            protectNewestChapterOnly = true
        )

        // 必须真的释放到上限附近（旧行为会保护整本 ⇒ freed = 0）
        assertEquals(1200L, freed)
        assertEquals(400L, cache.totalBytes())
        // 只有最近写入的第 3 章留下
        assertTrue(cache.fileFor("big", 3, 0, 0, "v", ENGINE).exists())
        listOf(0, 1, 2).forEach { chapter ->
            assertFalse(
                cache.fileFor("big", chapter, 0, 0, "v", ENGINE).exists(),
                "第 $chapter 章应被淘汰（整书路径只保最近一章）"
            )
        }
    }

    /** 显式指定章号时语义不变：只保护那一章，与 [protectNewestChapterOnly] 无关。 */
    @Test
    fun explicitProtectedChapterStillWins() {
        seed("b", 0, "v", 400, modifiedAt = 1_000L)
        seed("b", 1, "v", 400, modifiedAt = 2_000L)
        seed("b", 2, "v", 400, modifiedAt = 3_000L)

        val freed = cache.trimTo(
            limitBytes = 400L,
            protectBookId = "b",
            protectChapterIndex = 0,
            protectNewestChapterOnly = true
        )

        assertTrue(cache.fileFor("b", 0, 0, 0, "v", ENGINE).exists(), "显式保护的章必须在")
        assertTrue(freed > 0L, "其余章仍应被淘汰以收敛到上限")
    }

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
        assertFalse(cache.fileFor("b1", 0, 0, 0, "v", ENGINE).exists())
        assertTrue(cache.fileFor("b1", 1, 0, 0, "v", ENGINE).exists())
        assertTrue(cache.fileFor("b2", 0, 0, 0, "v", ENGINE).exists())
    }

    /** 正在听的东西被删掉会当场触发重新合成 —— 云 TTS 那是花钱的。 */
    @Test
    fun theChapterBeingPlayedIsNeverEvicted() {
        seed("playing", 3, "v", 500, modifiedAt = 1_000L) // 最旧，但正在听
        seed("other", 0, "v", 500, modifiedAt = 2_000L)

        cache.trimTo(600L, protectBookId = "playing", protectChapterIndex = 3)

        assertTrue(cache.fileFor("playing", 3, 0, 0, "v", ENGINE).exists())
        assertFalse(cache.fileFor("other", 0, 0, 0, "v", ENGINE).exists())
    }

    /** 用户可以选「不限」——离线听书是核心场景，硬上限会伤到「出门前缓存整本」。 */
    @Test
    fun unlimitedQuotaNeverEvicts() {
        seed("b1", 0, "v", 4_096, modifiedAt = 1_000L)

        assertEquals(0L, cache.trimTo(0L))
        assertEquals(0L, cache.trimTo(-1L))
        assertTrue(cache.fileFor("b1", 0, 0, 0, "v", ENGINE).exists())
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
        assertFalse(cache.fileFor("gone", 0, 0, 0, "v", ENGINE).exists())
    }

    /**
     * 第四轮审查 6-13：淘汰应按**最后访问**而不是最后写入。
     *
     * 场景：A 写得很早（1_000）但刚被听过；B 写得晚（9_000）却从没播过。
     * 旧行为（按 lastModified）会先删 A —— 天天在听的书先被淘汰。现在 A 应存活。
     */
    @Test
    fun evictionPrefersNeverPlayedOverRecentlyPlayedEvenIfWrittenEarlier() {
        seed("listened", 0, "v", 600, modifiedAt = 1_000L)
        seed("neverPlayed", 0, "v", 600, modifiedAt = 9_000L)
        // 播放路径会把「刚听过」记下来
        cache.markAccessed(cache.fileFor("listened", 0, 0, 0, "v", ENGINE))

        val freed = cache.trimTo(600L)

        assertEquals(600L, freed, "应恰好淘汰一个单元")
        assertTrue(
            cache.fileFor("listened", 0, 0, 0, "v", ENGINE).exists(),
            "刚被访问过的单元必须保留（真 LRU）"
        )
        assertFalse(
            cache.fileFor("neverPlayed", 0, 0, 0, "v", ENGINE).exists(),
            "写得晚但从未播放的单元应被淘汰"
        )
    }

    /**
     * 第四轮审查 6-13：`clearAll` 的 freed 必须在**删除成功后**统计。
     *
     * 这里断言返回值等于删除前实际占用、且清空后占用归零（旧实现用删除前估算，
     * 删除失败也会谎报；此用例锁定"统计与结果一致"）。
     */
    @Test
    fun clearAllReportsTheBytesItActuallyFreed() {
        seed("b1", 0, "v", 300, modifiedAt = 1_000L)
        seed("b2", 0, "v", 700, modifiedAt = 2_000L)
        val before = cache.totalBytes()
        assertEquals(1_000L, before)

        val freed = cache.clearAll()

        assertEquals(before, freed, "释放量应等于删除前的实际占用")
        assertEquals(0L, cache.totalBytes(), "清空后占用应为 0")
    }

    /** 访问标记是 0 字节的记账文件：不得计入占用，也不得把「最近写入」顶到最新。 */
    @Test
    fun accessMarkerDoesNotCountTowardsUsageOrLastWrite() {
        seed("b1", 0, "v", 500, modifiedAt = 1_000L)
        val file = cache.fileFor("b1", 0, 0, 0, "v", ENGINE)
        cache.markAccessed(file)

        assertEquals(500L, cache.totalBytes(), "标记文件不得计入占用")
        val entry = cache.entries().single()
        assertEquals(1_000L, entry.lastModified, "标记不是内容，不应改写 lastModified")
        assertTrue(entry.lastAccessed > 0L, "应记下访问时间")
    }
}