package com.linguareader.app.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * LruInstancePool 的策略测试（D3：Piper 多角色的实例驻留/驱逐）。
 * 池本身不依赖 Android，用假实例直接在 JVM 上验证 LRU 与引用计数语义。
 */
class LruInstancePoolTest {

    private class FakeTts(val id: String)

    @Test
    fun acquireCreatesOnceAndReuses() = runBlocking {
        var created = 0
        val pool = LruInstancePool<String, FakeTts>(capacity = 2, create = {
            created++
            FakeTts(it)
        }, destroy = {})

        assertEquals("a", pool.acquire("a")?.id)
        assertEquals("a", pool.acquire("a")?.id)
        assertEquals(1, created)
        pool.release("a")
        pool.release("a")
    }

    @Test
    fun createFailureReturnsNullWithoutCaching() = runBlocking {
        val pool = LruInstancePool<String, FakeTts>(capacity = 2, create = { null }, destroy = {})
        assertNull(pool.acquire("missing"))
        assertEquals(0, pool.size)
    }

    @Test
    fun evictsLeastRecentlyUsedIdleEntry() = runBlocking {
        val destroyed = mutableListOf<String>()
        val pool = LruInstancePool<String, FakeTts>(capacity = 2, create = { FakeTts(it) }, destroy = {
            destroyed.add(it.id)
        })

        assertNotNull(pool.acquire("a"))
        assertNotNull(pool.acquire("b"))
        pool.release("a")
        pool.release("b")
        // 访问 a 使其变为最近使用，b 成为 LRU。
        assertNotNull(pool.acquire("a"))
        pool.release("a")
        assertNotNull(pool.acquire("c"))

        assertEquals(listOf("b"), destroyed)
        assertEquals(2, pool.size)
    }

    @Test
    fun neverEvictsBusyEntriesEvenIfOldest() = runBlocking {
        val destroyed = mutableListOf<String>()
        val pool = LruInstancePool<String, FakeTts>(capacity = 2, create = { FakeTts(it) }, destroy = {
            destroyed.add(it.id)
        })

        assertNotNull(pool.acquire("a")) // 最旧但在用
        assertNotNull(pool.acquire("b"))
        pool.release("b")
        assertNotNull(pool.acquire("c")) // 需要驱逐一个：只能驱逐空闲的 b

        assertEquals(listOf("b"), destroyed)
        // 空闲的 b 被驱逐，忙碌的 a 保留：池回到容量内（2 个）。
        assertEquals(2, pool.size)
    }

    @Test
    fun trimsOnReleaseAfterTemporaryOverflow() = runBlocking {
        val destroyed = mutableListOf<String>()
        val pool = LruInstancePool<String, FakeTts>(capacity = 1, create = { FakeTts(it) }, destroy = {
            destroyed.add(it.id)
        })

        assertNotNull(pool.acquire("a"))
        assertNotNull(pool.acquire("b")) // a、b 都在用，超容
        pool.release("a") // 释放后 a 变为可驱逐的 LRU

        assertEquals(listOf("a"), destroyed)
        assertEquals(1, pool.size)
    }

    @Test
    fun pinnedDefaultVoiceSurvivesPressure() = runBlocking {
        val destroyed = mutableListOf<String>()
        val pool = LruInstancePool<String, FakeTts>(capacity = 2, create = { FakeTts(it) }, destroy = {
            destroyed.add(it.id)
        })

        // 常驻默认音色：acquire 后不再 release（模拟合成器持有引用）。
        assertNotNull(pool.pin("default"))
        for (id in listOf("x", "y", "z")) {
            assertNotNull(pool.acquire(id))
            pool.release(id)
        }
        assertEquals(0, destroyed.count { it == "default" })
    }

    @Test
    fun concurrentAcquireOfSameKeyCreatesOneInstance() = runBlocking {
        val created = Collections.synchronizedList(mutableListOf<String>())
        val gate = CountDownLatch(1)
        val pool = LruInstancePool<String, FakeTts>(capacity = 4, create = { key ->
            created.add(key)
            gate.await() // 拉长创建窗口，制造并发竞争
            FakeTts(key)
        }, destroy = {})

        val first = async(Dispatchers.IO) { pool.acquire("v") }
        val second = async(Dispatchers.IO) { pool.acquire("v") }
        gate.countDown()
        assertNotNull(first.await())
        assertNotNull(second.await())
        assertEquals(listOf("v"), created)
        pool.release("v")
        pool.release("v")
    }

    @Test
    fun closeDestroysEverything() = runBlocking {
        val destroyed = mutableListOf<String>()
        val pool = LruInstancePool<String, FakeTts>(capacity = 4, create = { FakeTts(it) }, destroy = {
            destroyed.add(it.id)
        })
        assertNotNull(pool.acquire("a"))
        assertNotNull(pool.acquire("b"))
        pool.close()
        assertEquals(setOf("a", "b"), destroyed.toSet())
        assertEquals(0, pool.size)
    }
}
