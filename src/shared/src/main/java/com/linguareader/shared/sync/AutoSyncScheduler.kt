package com.linguareader.shared.sync

/**
 * 「本地变更后延迟同步」的**纯逻辑**防抖状态机（task-4）。
 *
 * 不持线程/协程/作用域，只维护「下一次该触发的时刻」——真实计时由调用方（AppViewModel 的
 * ticker 协程）推进，与 `tts/TtsPlaybackEngine` 同一抽法：决策可单测，平台只做驱动。
 *
 * 两个硬约束：
 * - **enabled=false 永不出网**：[onLocalChange] / [consumeIfDue] 在 disabled 时一律返回 false
 *   并清空排队（同步默认关闭的大前提）。
 * - **不空转**：没有排队时 [isPending] 为 false，调用方据此结束 ticker。
 *
 * 已知边界（勿当 bug 修）：听书进度由 `TtsPlaybackService` 直接落盘、不经 AppViewModel，
 * 因此不参与本防抖链；它会在下次启动 / 手动同步时一并上行。
 */
class AutoSyncScheduler(val debounceMs: Long = DEFAULT_DEBOUNCE_MS) {

    private var dueAt: Long? = null

    /**
     * 记录一次本地变更（仅在**写盘成功后**调用）。
     * 返回 false 表示没有排队（未启用），调用方应取消 ticker。
     */
    fun onLocalChange(enabled: Boolean, now: Long): Boolean {
        if (!enabled) {
            dueAt = null
            return false
        }
        dueAt = now + debounceMs
        return true
    }

    /**
     * 到点且未被重置 → true（并清空，保证只触发一次）；未到点/未排队/未启用 → false。
     * 未启用时顺带清空排队：避免「排队期间用户关掉同步」后仍出网。
     */
    fun consumeIfDue(enabled: Boolean, now: Long): Boolean {
        if (!enabled) {
            dueAt = null
            return false
        }
        val at = dueAt ?: return false
        if (now < at) return false
        dueAt = null
        return true
    }

    /** 距离到期还有多少毫秒；没有排队时 null（调用方据此结束 ticker，避免空转）。 */
    fun remainingMs(now: Long): Long? = dueAt?.let { (it - now).coerceAtLeast(0L) }

    /** 取消排队（登出 / 关闭同步 / 手动同步完成后）。 */
    fun cancel() {
        dueAt = null
    }

    val isPending: Boolean get() = dueAt != null

    fun pendingDueAt(): Long? = dueAt

    companion object {
        /** 阅读进度/生词本变更后的防抖窗口。 */
        const val DEFAULT_DEBOUNCE_MS = 30_000L
    }
}
