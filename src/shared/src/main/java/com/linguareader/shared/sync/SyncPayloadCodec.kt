package com.linguareader.shared.sync

import com.linguareader.shared.data.Book
import com.linguareader.shared.data.SavedWord
import org.json.JSONObject

/**
 * 领域模型 <-> 同步记录的双向编解码。
 *
 * **只编码可跨设备的数据**：书籍解压路径、封面相对路径、章节表等设备本地字段一律不进 payload
 * （各设备自己导入书，同步的是阅读位置与用户数据）。
 */
object SyncPayloadCodec {

    fun progressRecord(book: Book): SyncRecord = SyncRecord(
        collection = SyncCollections.PROGRESS,
        id = book.id,
        updatedAt = book.progressUpdatedAt,
        payload = progressPayload(book)
    )

    fun progressPayload(book: Book): JSONObject = JSONObject()
        .put("bookId", book.id)
        .put("title", book.title)
        .put("author", book.author)
        .put("sourceFormat", book.sourceFormat)
        .put("chapterIndex", book.chapterIndex)
        .put("pageIndex", book.pageIndex)
        .put("progress", book.progress.toDouble())
        .put("locusBlockIndex", book.locusBlockIndex)
        .put("locusCharOffset", book.locusCharOffset)
        .put("locusAnchor", book.locusAnchor)
        .put("ttsChapterIndex", book.ttsChapterIndex)
        .put("ttsSentenceIndex", book.ttsSentenceIndex)

    /** 仅用于取「位置向量」做冲突裁决；设备本地字段取占位值，合并结果不回写这些字段。 */
    fun bookFromProgress(record: SyncRecord): Book {
        val payload = record.payload
        return Book(
            id = payload.optString("bookId", record.id),
            title = payload.optString("title"),
            author = payload.optString("author"),
            extractedDir = "",
            coverRelativePath = null,
            chapters = emptyList(),
            addedAt = 0L,
            chapterIndex = payload.optInt("chapterIndex"),
            pageIndex = payload.optInt("pageIndex"),
            progress = payload.optDouble("progress").toFloat(),
            locusBlockIndex = payload.optInt("locusBlockIndex", Book.NO_LOCUS),
            locusCharOffset = payload.optInt("locusCharOffset"),
            locusAnchor = payload.optString("locusAnchor", Book.ANCHOR_EXACT),
            ttsChapterIndex = payload.optInt("ttsChapterIndex"),
            ttsSentenceIndex = payload.optInt("ttsSentenceIndex"),
            progressUpdatedAt = record.updatedAt
        )
    }

    fun savedWordRecord(word: SavedWord): SyncRecord = SyncRecord(
        collection = SyncCollections.VOCABULARY,
        id = word.id,
        // 老数据 updatedAt=0：退到 addedAt，保证至少有单调依据。
        updatedAt = if (word.updatedAt > 0) word.updatedAt else word.addedAt,
        payload = word.toJson()
    )

    fun savedWordFrom(record: SyncRecord): SavedWord = SavedWord.fromJson(record.payload)

    fun glossaryRecord(
        bookId: String,
        term: String,
        kind: String,
        note: String,
        updatedAt: Long
    ): SyncRecord = SyncRecord(
        collection = SyncCollections.GLOSSARY,
        id = bookId + "::" + term,
        updatedAt = updatedAt,
        payload = JSONObject().put("bookId", bookId).put("term", term).put("kind", kind).put("note", note)
    )

    fun preferenceRecord(key: String, value: String, updatedAt: Long): SyncRecord = SyncRecord(
        collection = SyncCollections.PREFERENCE,
        id = key,
        updatedAt = updatedAt,
        payload = JSONObject().put("value", value)
    )
}
