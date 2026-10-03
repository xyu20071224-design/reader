package com.linguareader.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AutoSyncScheduler]：防抖重置、到点只触发一次、取消、禁用不出网。 */
class AutoSyncSchedulerTest {

    private val delay = AutoSyncScheduler.DEFAULT_DEBOUNCE_MS

    @Test
    fun defaultsToThirtySeconds() {
        assertEquals(30_000L, delay)
        assertEquals(delay, AutoSyncScheduler().debounceMs)
    }

    @Test
    fun firesExactlyOnceWhenDue() {
        val scheduler = AutoSyncScheduler(delay)
        assertTrue(scheduler.onLocalChange(enabled = true, now = 1_000))
        assertEquals(1_000 + delay, scheduler.pendingDueAt())

        assertFalse(scheduler.consumeIfDue(enabled = true, now = 1_000 + delay - 1))
        assertTrue(scheduler.consumeIfDue(enabled = true, now = 1_000 + delay))
        // 已消费，不再触发
        assertFalse(scheduler.consumeIfDue(enabled = true, now = 1_000 + delay + 10_000))
        assertFalse(scheduler.isPending)
    }

    @Test
    fun repeatedChangesPushTheDeadlineInsteadOfFiringEarly() {
        val scheduler = AutoSyncScheduler(delay)
        scheduler.onLocalChange(enabled = true, now = 1_000)
        scheduler.onLocalChange(enabled = true, now = 20_000)
        assertEquals(20_000 + delay, scheduler.pendingDueAt())

        // 原本的到期时刻不再触发
        assertFalse(scheduler.consumeIfDue(enabled = true, now = 1_000 + delay))
        assertTrue(scheduler.consumeIfDue(enabled = true, now = 20_000 + delay))
    }

    @Test
    fun cancelClearsQueuedSync() {
        val scheduler = AutoSyncScheduler(delay)
        scheduler.onLocalChange(enabled = true, now = 1_000)
        scheduler.cancel()
        assertFalse(scheduler.isPending)
        assertNull(scheduler.pendingDueAt())
        assertFalse(scheduler.consumeIfDue(enabled = true, now = 999_999))
    }

    @Test
    fun disabledNeverSchedules() {
        val scheduler = AutoSyncScheduler(delay)
        assertFalse(scheduler.onLocalChange(enabled = false, now = 1_000))
        assertFalse(scheduler.isPending)
        assertNull(scheduler.remainingMs(1_000))
        assertFalse(scheduler.consumeIfDue(enabled = false, now = 999_999))
    }

    @Test
    fun disablingClearsAnAlreadyQueuedSync() {
        val scheduler = AutoSyncScheduler(delay)
        assertTrue(scheduler.onLocalChange(enabled = true, now = 1_000))
        // 排队期间用户关掉同步：不得出网，且排队被清空
        assertFalse(scheduler.consumeIfDue(enabled = false, now = 1_000 + delay))
        assertFalse(scheduler.isPending)
        assertFalse(scheduler.consumeIfDue(enabled = true, now = 1_000 + delay + 1))
    }

    @Test
    fun remainingMsDrivesTheTickerAndIsNullWhenIdle() {
        val scheduler = AutoSyncScheduler(delay)
        assertNull(scheduler.remainingMs(0))
        scheduler.onLocalChange(enabled = true, now = 1_000)
        assertEquals(delay, scheduler.remainingMs(1_000))
        assertEquals(1L, scheduler.remainingMs(1_000 + delay - 1))
        // 已过点不会返回负数
        assertEquals(0L, scheduler.remainingMs(1_000 + delay + 5_000))
    }
}
