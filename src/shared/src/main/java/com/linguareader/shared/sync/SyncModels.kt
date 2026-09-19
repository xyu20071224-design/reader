package com.linguareader.shared.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条可同步记录（协议层信封，见 阶段2-同步协议与部署-提案.md §4）。
 * 纯数据，不含任何平台概念。
 */
data class SyncRecord(
    val collection: String,
    val id: String,
    val updatedAt: Long,
    val deletedAt: Long = 0L,
    val payload: JSONObject,
    val serverSeq: Long = 0L
) {
    /** outbox 内去重与冲突匹配的键。 */
    val key: String get() = collection + "/" + id

    fun toJson(): JSONObject = JSONObject()
        .put("collection", collection)
        .put("id", id)
        .put("updatedAt", updatedAt)
        .put("deletedAt", deletedAt)
        .put("payload", payload)
        .put("serverSeq", serverSeq)

    companion object {
        fun fromJson(json: JSONObject): SyncRecord = SyncRecord(
            collection = json.optString("collection"),
            id = json.optString("id"),
            updatedAt = json.optLong("updatedAt"),
            deletedAt = json.optLong("deletedAt"),
            payload = json.optJSONObject("payload") ?: JSONObject(),
            serverSeq = json.optLong("serverSeq")
        )

        fun listToJson(records: List<SyncRecord>): JSONArray =
            JSONArray().apply { records.forEach { put(it.toJson()) } }

        fun listFromJson(array: JSONArray?): List<SyncRecord> =
            if (array == null) emptyList()
            else (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { fromJson(it) }
            }
    }
}

/** 一次拉取页。hasMore=true 时应以 nextSeq 继续拉。 */
data class PullPage(val records: List<SyncRecord>, val nextSeq: Long, val hasMore: Boolean)

/** 一次推送结果。conflicts 是服务端上更新、未被覆盖的版本，调用方需合并后重推。 */
data class PushResult(val applied: List<SyncRecord>, val conflicts: List<SyncRecord>, val nextSeq: Long)

/** 一次同步的结果报告，用于 UI 与验证留痕。 */
data class SyncReport(
    val uploaded: Int,
    val downloaded: Int,
    val unresolved: List<SyncRecord>,
    val cursor: Long,
    val pending: Int
)

/** 传输或协议层错误。status=0 表示本地/网络失败（如离线）。 */
class SyncException(message: String, val status: Int = 0) : Exception(message)
