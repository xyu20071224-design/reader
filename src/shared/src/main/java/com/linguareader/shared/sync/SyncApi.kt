package com.linguareader.shared.sync

/**
 * 同步传输接口。实现可以是 HTTP（生产）、内存假实现（测试）或未来的 WebDAV 适配器，
 * 引擎只依赖这个面。
 */
interface SyncApi {
    /** 账号密码换令牌；失败抛 [SyncException]。 */
    suspend fun login(username: String, password: String): String

    /** 增量拉取 serverSeq > since 的记录。 */
    suspend fun pull(since: Long, limit: Int = 500): PullPage

    /** 推送本地变更；冲突记录原样返回，由调用方合并后重推。 */
    suspend fun push(records: List<SyncRecord>): PushResult

    /** 全量快照，用于新设备首次引导。 */
    suspend fun fullState(): PullPage

    /**
     * 用已保存的令牌恢复会话（进程重启后 [login] 不会被调用）。
     * 默认空实现，方便测试假实现不关心鉴权。
     */
    fun setToken(token: String) {}
}
