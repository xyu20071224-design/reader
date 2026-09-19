package com.linguareader.shared.sync

/**
 * 同步引擎：先推送（含冲突合并后重推），再按游标增量拉取。
 *
 * 纯逻辑、无平台依赖；传输走 [SyncApi]，本地状态走 [SyncStateStore]，
 * 冲突合并由调用方注入 [merge]（领域层用 ConflictResolver 把 payload 解码成
 * 类型化模型再合并，见 阶段2 提案 §7）。
 *
 * 离线语义：push 抛 [SyncException] 时**不改动 outbox**，异常向上传播，
 * 调用方决定重试时机（指数退避在调用侧，见 2d 接线）。
 */
class SyncEngine(
    private val api: SyncApi,
    private val state: SyncStateStore,
    private val merge: (local: SyncRecord, remote: SyncRecord, now: Long) -> SyncRecord,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxConflictRounds: Int = 5
) {
    suspend fun sync(applyRemote: suspend (List<SyncRecord>) -> Unit = {}): SyncReport {
        // 同键多条本地变更只保留一条（后写覆盖先写）。
        val outbox = state.readOutbox().associateByTo(LinkedHashMap()) { it.key }
        var uploaded = 0
        var unresolved: List<SyncRecord> = emptyList()
        var rounds = 0

        while (outbox.isNotEmpty()) {
            val result = api.push(outbox.values.sortedBy { it.updatedAt })
            uploaded += result.applied.size
            for (applied in result.applied) {
                val pending = outbox[applied.key]
                // 服务端确认的版本不旧于本地待推版本时才移除，避免丢掉本地更新。
                if (pending != null && pending.updatedAt <= applied.updatedAt) outbox.remove(applied.key)
            }
            if (result.conflicts.isEmpty()) {
                unresolved = emptyList()
                break
            }
            for (remote in result.conflicts) {
                val local = outbox[remote.key] ?: continue
                outbox[remote.key] = merge(local, remote, clock())
            }
            rounds++
            if (rounds >= maxConflictRounds) {
                unresolved = result.conflicts
                break
            }
        }
        state.writeOutbox(outbox.values.toList())

        var cursor = state.readCursor()
        var downloaded = 0
        var hasMore = true
        while (hasMore) {
            val page = api.pull(cursor)
            if (page.records.isNotEmpty()) {
                applyRemote(page.records)
                downloaded += page.records.size
            }
            cursor = page.nextSeq
            hasMore = page.hasMore
        }
        state.writeCursor(cursor)

        return SyncReport(
            uploaded = uploaded,
            downloaded = downloaded,
            unresolved = unresolved,
            cursor = cursor,
            pending = outbox.size
        )
    }
}
