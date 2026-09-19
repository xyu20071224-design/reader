package com.linguareader.shared.sync

import org.json.JSONObject

/**
 * 按集合分派的领域合并（阶段2 提案 §7 / D-03）。
 *
 * 引擎只负责传输与游标，真正的字段级语义在这里；语义本身来自 [ConflictResolver]，
 * 这里是它在「协议记录」层的适配。
 */
object SyncMerger {

    fun merge(local: SyncRecord, remote: SyncRecord, now: Long): SyncRecord {
        require(local.key == remote.key) { "merge requires matching keys: " + local.key + " vs " + remote.key }

        // 墓碑不可逆：谁更晚谁说了算，且不参与字段合并。
        if (remote.deletedAt > 0 && remote.updatedAt >= local.updatedAt) {
            return remote.copy(updatedAt = stamp(local, remote, now))
        }
        if (local.deletedAt > 0 && local.updatedAt >= remote.updatedAt) {
            return local.copy(updatedAt = stamp(local, remote, now))
        }

        return when (local.collection) {
            SyncCollections.PROGRESS -> mergeProgress(local, remote, now)
            SyncCollections.VOCABULARY -> mergeVocabulary(local, remote, now)
            SyncCollections.GLOSSARY -> mergeGlossary(local, remote, now)
            SyncCollections.PREFERENCE -> lastWriteWins(local, remote, now)
            else -> lastWriteWins(local, remote, now)
        }
    }

    private fun stamp(local: SyncRecord, remote: SyncRecord, now: Long): Long =
        maxOf(local.updatedAt, remote.updatedAt, now) + 1

    private fun lastWriteWins(local: SyncRecord, remote: SyncRecord, now: Long): SyncRecord {
        val winner = if (remote.updatedAt > local.updatedAt) remote else local
        return winner.copy(updatedAt = stamp(local, remote, now))
    }

    private fun mergeProgress(local: SyncRecord, remote: SyncRecord, now: Long): SyncRecord {
        val localBook = runCatching { SyncPayloadCodec.bookFromProgress(local) }.getOrNull()
        val remoteBook = runCatching { SyncPayloadCodec.bookFromProgress(remote) }.getOrNull()
        if (localBook == null || remoteBook == null) return lastWriteWins(local, remote, now)
        val merged = ConflictResolver.mergeProgress(localBook, remoteBook)
        return SyncRecord(
            collection = SyncCollections.PROGRESS,
            id = local.id,
            updatedAt = stamp(local, remote, now),
            payload = SyncPayloadCodec.progressPayload(merged)
        )
    }

    private fun mergeVocabulary(local: SyncRecord, remote: SyncRecord, now: Long): SyncRecord {
        val localWord = runCatching { SyncPayloadCodec.savedWordFrom(local) }.getOrNull()
        val remoteWord = runCatching { SyncPayloadCodec.savedWordFrom(remote) }.getOrNull()
        if (localWord == null || remoteWord == null) return lastWriteWins(local, remote, now)
        val merged = ConflictResolver.mergeSavedWord(localWord, remoteWord)
        return SyncRecord(
            collection = SyncCollections.VOCABULARY,
            id = local.id,
            updatedAt = stamp(local, remote, now),
            payload = merged.toJson()
        )
    }

    private fun mergeGlossary(local: SyncRecord, remote: SyncRecord, now: Long): SyncRecord {
        val note = ConflictResolver.mergeText(
            local.payload.optString("note"), local.updatedAt,
            remote.payload.optString("note"), remote.updatedAt
        )
        val winner = if (remote.updatedAt > local.updatedAt) remote else local
        return SyncRecord(
            collection = SyncCollections.GLOSSARY,
            id = local.id,
            updatedAt = stamp(local, remote, now),
            payload = JSONObject(winner.payload.toString()).put("note", note)
        )
    }
}
