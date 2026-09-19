package com.linguareader.shared.sync

import org.json.JSONObject
import java.io.File

/** 本地同步状态：已拉取游标 + 待推送 outbox + 已同步水位。 */
interface SyncStateStore {
    fun readCursor(): Long
    fun writeCursor(seq: Long)
    fun readOutbox(): List<SyncRecord>
    fun writeOutbox(records: List<SyncRecord>)

    /**
     * 每个记录键「本地已知已同步」的最大 updatedAt。
     * 用来避免每次同步把全量快照当变更重推（重推会触发无意义的合并与版本戳漂移）。
     */
    fun readSynced(): Map<String, Long>
    fun writeSynced(marks: Map<String, Long>)
}

/** 落盘实现：filesDir/sync/{cursor.json,outbox.json}，沿用 tmp + rename 的原子写纪律。 */
class FileSyncStateStore(private val dir: File) : SyncStateStore {
    private val cursorFile = File(dir, "cursor.json")
    private val outboxFile = File(dir, "outbox.json")
    private val syncedFile = File(dir, "synced.json")

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

    override fun readSynced(): Map<String, Long> {
        val json = readJson(syncedFile) ?: return emptyMap()
        val marks = LinkedHashMap<String, Long>()
        for (key in json.keys()) marks[key] = json.optLong(key)
        return marks
    }

    override fun writeSynced(marks: Map<String, Long>) {
        val json = JSONObject()
        for ((key, value) in marks) json.put(key, value)
        writeJson(syncedFile, json)
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
    private var synced: Map<String, Long> = emptyMap()

    override fun readCursor(): Long = cursor

    override fun writeCursor(seq: Long) {
        cursor = seq
    }

    override fun readOutbox(): List<SyncRecord> = outbox

    override fun writeOutbox(records: List<SyncRecord>) {
        outbox = records
    }

    override fun readSynced(): Map<String, Long> = synced

    override fun writeSynced(marks: Map<String, Long>) {
        synced = marks
    }
}
