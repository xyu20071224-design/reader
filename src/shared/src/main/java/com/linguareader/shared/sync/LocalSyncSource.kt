package com.linguareader.shared.sync

import com.linguareader.shared.data.Book
import com.linguareader.shared.data.LibraryRepository
import com.linguareader.shared.data.VocabularyRepository

/**
 * 用共享数据仓库实现的本地接缝：阅读进度（书库元数据）+ 生词本。
 *
 * 边界：
 * - 书籍正文不参与（各设备自行导入）；本机没有这本书时跳过它的进度记录。
 * - 术语备注与偏好在各自平台补齐后加入 [snapshot]/[apply]。
 */
class LocalSyncSource(
    private val library: LibraryRepository,
    private val vocabulary: VocabularyRepository
) : SyncSource {

    override suspend fun snapshot(): List<SyncRecord> {
        val records = ArrayList<SyncRecord>()
        for (book in library.loadBooks()) records += SyncPayloadCodec.progressRecord(book)
        for (word in vocabulary.load()) records += SyncPayloadCodec.savedWordRecord(word)
        return records
    }

    override suspend fun apply(remote: List<SyncRecord>) {
        if (remote.isEmpty()) return
        var books: Map<String, Book>? = null
        val words = vocabulary.load().associateBy { it.id }

        for (record in remote) {
            when (record.collection) {
                SyncCollections.PROGRESS -> {
                    if (record.deletedAt > 0) continue
                    books = books ?: library.loadBooks().associateBy { it.id }
                    val book = books[record.id] ?: continue
                    val merged = ConflictResolver.mergeProgress(book, SyncPayloadCodec.bookFromProgress(record))
                    library.saveProgressFromRemote(
                        book = book,
                        chapterIndex = merged.chapterIndex,
                        pageIndex = merged.pageIndex,
                        progress = merged.progress,
                        locusBlockIndex = merged.locusBlockIndex,
                        locusCharOffset = merged.locusCharOffset,
                        locusAnchor = merged.locusAnchor,
                        ttsChapterIndex = merged.ttsChapterIndex,
                        ttsSentenceIndex = merged.ttsSentenceIndex,
                        updatedAt = record.updatedAt
                    )
                }

                SyncCollections.VOCABULARY -> {
                    if (record.deletedAt > 0) {
                        vocabulary.remove(record.id)
                        continue
                    }
                    val local = words[record.id]
                    // 本机更新的版本还没推上去时，不要被远端旧版本回灌。
                    if (local != null && local.updatedAt > record.updatedAt) continue
                    val remoteWord = SyncPayloadCodec.savedWordFrom(record)
                    val merged = if (local == null) remoteWord else ConflictResolver.mergeSavedWord(local, remoteWord)
                    vocabulary.upsert(merged.copy(updatedAt = maxOf(merged.updatedAt, record.updatedAt)))
                }

                else -> Unit
            }
        }
    }
}
