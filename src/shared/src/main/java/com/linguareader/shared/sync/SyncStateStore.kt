package com.linguareader.shared.sync

import org.json.JSONObject
import java.io.File

/** 本地同步状态：已拉取游标 + 待推送 outbox。 */
interface SyncStateStore {
    fun readCursor(): Long
    fun writeCursor(seq: Long)
    fun readOutbox(): List<SyncRecord>
    fun writeOutbox(records: List<SyncRecord>)
}

/** 落盘实现：filesDir/sync/{cursor.json,outbox.json}，沿用 tmp + rename 的原子写纪律。 */
class FileSyncStateStore(private val dir: File) : SyncStateStore {
    private val cursorFile = File(dir, "cursor.json")
    private val outboxFile = File(dir, "outbox.json")

    override fun readCursor(): Long = readJson(cursorFile)?.optLong("cursor") ?: 0L

    override fun writeCursor(seq: Long) {
        writeJson(cursorFile, JSONObject().put("cursor", seq))
    }

    override fun readOutbox(): List<SyncRecord> {
        val json = readJson(outboxFile) ?: return emptyList()
        return SyncRecord.listFromJson(json.optJSONArray("records"))
    }

    override fun writeOutbox(records: List<SyncRecord>) {
        writeJson(outboxFile, JSONObject().put("records", SyncRecord.listToJson(records)))
    }

    private fun readJson(file: File): JSONObject? =
        if (!file.isFile) null else runCatching { JSONObject(file.readText()) }.getOrNull()

    private fun writeJson(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }
}

/** 测试与内存场景用。 */
class InMemorySyncStateStore : SyncStateStore {
    private var cursor = 0L
    private var outbox: List<SyncRecord> = emptyList()

    override fun readCursor(): Long = cursor

    override fun writeCursor(seq: Long) {
        cursor = seq
    }

    override fun readOutbox(): List<SyncRecord> = outbox

    override fun writeOutbox(records: List<SyncRecord>) {
        outbox = records
    }
}
