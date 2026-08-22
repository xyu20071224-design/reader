package com.linguareader.app.tts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 引用计数 + LRU 的重量级实例池（Piper 多角色 D3）。
 *
 * sherpa-onnx 的一个 [OfflineTts] 实例绑定一个音色模型，创建要数秒、占几十 MB，
 * 所以「逐句换音色」只能靠预加载多个实例并按 LRU 驱逐。本类只管策略，不碰
 * Android/sherpa 类型：[create] 与 [destroy] 由调用方注入，因此可以在 JVM 上
 * 直接单测。
 *
 * 语义：
 * - [acquire] 命中则引用计数 +1 并刷新 LRU；未命中则在锁内创建（串行化避免
 *   同一音色被并发创建两份），失败返回 null，由调用方回退默认实例。
 * - 超出容量时按最久未用顺序驱逐**引用计数为 0** 的条目；全部在用时允许暂时
 *   超容，等 [release] 时再补驱逐——绝不销毁正在合成的实例。
 * - [pin] 常驻一个条目（默认英文音色），保证它永远不会被驱逐。
 */
class LruInstancePool<K : Any, V : Any>(
    private val capacity: Int,
    private val create: suspend (K) -> V?,
    private val destroy: (V) -> Unit
) {
    private class Entry<V>(val value: V) {
        var refs = 0
    }

    private val mutex = Mutex()

    /** accessOrder = true：迭代顺序即 LRU（最久未用在前）。 */
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    val size: Int get() = entries.size

    suspend fun acquire(key: K): V? = mutex.withLock {
        entries[key]?.let { entry ->
            entry.refs++
            return@withLock entry.value
        }
        val value = create(key) ?: return@withLock null
        entries[key] = Entry(value).apply { refs = 1 }
        trimLocked()
        value
    }

    suspend fun release(key: K) {
        mutex.withLock {
            entries[key]?.let { entry -> entry.refs = (entry.refs - 1).coerceAtLeast(0) }
            trimLocked()
        }
    }

    /** 常驻 [key]：已存在则引用计数 +1（由调用方负责对应的一次 [release]）。 */
    suspend fun pin(key: K): V? = acquire(key)

    suspend fun close() {
        mutex.withLock {
            entries.values.forEach { entry -> runCatching { destroy(entry.value) } }
            entries.clear()
        }
    }

    private fun trimLocked() {
        var excess = entries.size - capacity
        if (excess <= 0) return
        val iterator = entries.entries.iterator()
        while (excess > 0 && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.refs == 0) {
                runCatching { destroy(entry.value.value) }
                iterator.remove()
                excess--
            }
        }
    }
}
