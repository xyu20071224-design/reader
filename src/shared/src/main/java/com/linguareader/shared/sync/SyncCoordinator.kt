package com.linguareader.shared.sync

/**
 * 应用侧同步编排：令牌存取 + 本地快照变更入队 + 引擎驱动 + 已同步水位维护。
 *
 * 纯逻辑；平台只负责提供 [SyncSource] / [SecretStore] 实现以及触发时机（前台、手动、定时）。
 * **同步默认关闭**，是否调用由设置页决定。
 */
class SyncCoordinator(
    private val api: SyncApi,
    private val source: SyncSource,
    private val state: SyncStateStore,
    private val secrets: SecretStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val engine = SyncEngine(api, state, SyncMerger::merge, clock)

    fun hasSession(): Boolean = !secrets.get(TOKEN_KEY).isNullOrBlank()

    /** 进程重启后把已保存的令牌装回 [api]；返回是否已有会话。 */
    fun restoreSession(): Boolean {
        val token = secrets.get(TOKEN_KEY)?.takeIf { it.isNotBlank() } ?: return false
        api.setToken(token)
        return true
    }

    suspend fun login(username: String, password: String) {
        val token = api.login(username, password)
        secrets.put(TOKEN_KEY, token)
    }

    fun logout() {
        secrets.remove(TOKEN_KEY)
    }

    /**
     * 一次完整同步：
     * 1. 把本地快照里「比已同步水位更新」的记录并入 outbox；
     * 2. 交给引擎（推送 → 冲突合并重推 → 游标拉取并 apply 回本地）；
     * 3. 用同步后的本地快照推进水位；仍 pending 的键水位回退，保证下次还会重推。
     */
    suspend fun sync(): SyncReport {
        val synced = state.readSynced().toMutableMap()
        val outbox = state.readOutbox().associateByTo(LinkedHashMap()) { it.key }
        for (record in source.snapshot()) {
            if (record.updatedAt > (synced[record.key] ?: 0L)) outbox[record.key] = record
        }
        state.writeOutbox(outbox.values.toList())

        val report = engine.sync { remote -> source.apply(remote) }

        val stillPending = state.readOutbox().mapTo(HashSet()) { it.key }
        for (record in source.snapshot()) {
            if (record.key !in stillPending) {
                synced[record.key] = maxOf(synced[record.key] ?: 0L, record.updatedAt)
            }
        }
        for (key in stillPending) synced.remove(key)
        state.writeSynced(synced)
        return report
    }

    companion object {
        const val TOKEN_KEY = "sync.token"
    }
}
