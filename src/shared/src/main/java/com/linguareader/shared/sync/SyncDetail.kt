package com.linguareader.shared.sync

import com.linguareader.shared.data.Book
import com.linguareader.shared.data.SavedWord

/**
 * 单条记录的同步状态。
 *
 * 判定只依赖本地信息（已同步水位 + outbox），因此**离线也能算出**；
 * 「书正文」是否在云端的判定还要看最近一次拉到的远端 blob 列表（见 [SyncBookItem.sourceState]）。
 */
enum class SyncItemState {
    /** 本地时间戳 ≤ 已同步水位（或云端已有完整正文）。 */
    SYNCED,

    /** 本地更新过、或还在 outbox 里等推送，尚未确认同步到服务端。 */
    PENDING,

    /** 从未同步过（本地无水位、也不在 outbox）。 */
    LOCAL_ONLY
}

/**
 * 一本书的同步明细。
 *
 * [progressState] 是阅读进度记录（key = `progress/<bookId>`）；
 * [sourceState] 是书籍**正文 blob**（各设备自行导入，走 blob 通道而非记录通道）：
 * - `null` = 本次尚未拉到云端 blob 列表，无从判断（不是「没同步」）；
 * - [SyncItemState.SYNCED] = 云端已有完整正文；
 * - [SyncItemState.LOCAL_ONLY] = 云端没有这本正文。
 */
data class SyncBookItem(
    val bookId: String,
    val title: String,
    val progressState: SyncItemState,
    /** null = 本次尚未拉到云端 blob 列表。 */
    val sourceState: SyncItemState?,
    val sourceSizeBytes: Long = 0L,
    val progressUpdatedAt: Long = 0L
)

/**
 * 一条生词记录的同步明细（key = `vocabulary/<wordId>`）。
 * [updatedAt] 是参与判定的本地时间戳：`updatedAt > 0` 用它，否则回退 `addedAt`（老数据）。
 */
data class SyncWordItem(
    val wordId: String,
    val headword: String,
    val state: SyncItemState,
    val updatedAt: Long = 0L
)

/** 明细的三态计数。语义见 [SyncItemState]。 */
data class SyncDetailSummary(
    val syncedCount: Int,
    val pendingCount: Int,
    val localOnlyCount: Int
)

/**
 * 同步明细快照：本地全部书/生词逐条状态 + 只在云端的书 + 汇总计数。
 * 纯数据，供各平台界面直接渲染。
 */
data class SyncDetail(
    val books: List<SyncBookItem>,
    val words: List<SyncWordItem>,
    /** 远端有完整正文、本机没有的书（与 `AppUiState.cloudBooks` 同源）。 */
    val remoteOnlyBooks: List<CloudBook>,
    val summary: SyncDetailSummary
)

/**
 * 由本地数据 + 本地同步水位构建 [SyncDetail] 的纯函数（无 IO、无网络、可单测）。
 *
 * 状态判定（对记录 key=K、本地时间戳 T）：
 * 1. K 在 [pendingKeys][build]（outbox 待推送）里 → [SyncItemState.PENDING]；
 * 2. 否则 T ≤ 0（没有可上行的内容，如从未读过的书）→ [SyncItemState.LOCAL_ONLY]；
 * 3. 否则 [syncedMarks][build][K] 存在且 ≥ T → [SyncItemState.SYNCED]；
 * 4. 否则 [syncedMarks][build][K] 存在但 < T（本地改过、还没推上去）→ [SyncItemState.PENDING]；
 * 5. 否则（K 无水位，或水位为 0 = 从未真正上行）→ [SyncItemState.LOCAL_ONLY]。
 *
 * 规则 2 与 5 的「0」是同一个坑的两面：`SyncCoordinator.sync()` 收尾会给**每条**本地记录
 * 写水位，而从未读过的书 `progressUpdatedAt=0` 既不入 outbox、也从未真正推送，于是水位被
 * 写成 0；若只按 `mark >= T` 比较就得到 `0 >= 0` → 把「从未同步过」误报成「已同步」。
 *
 * 排序是确定性的（便于单测与稳定 UI）：书按 (title, id)，词按 (headword.lowercase(), id)。
 */
object SyncDetailBuilder {

    /** 阅读进度记录的键，与 [SyncPayloadCodec.progressRecord] / `SyncRecord.key` 一致。 */
    fun progressKey(bookId: String): String = SyncCollections.PROGRESS + "/" + bookId

    /** 生词记录的键，与 [SyncPayloadCodec.savedWordRecord] / `SyncRecord.key` 一致。 */
    fun wordKey(wordId: String): String = SyncCollections.VOCABULARY + "/" + wordId

    /**
     * @param books 本地书库
     * @param words 本地生词本
     * @param syncedMarks 已同步水位（key → 最后成功同步的 updatedAt），来自 `SyncStateStore.readSynced()`
     * @param pendingKeys outbox 里待推送记录的键，来自 `SyncStateStore.readOutbox()`
     * @param remoteBlobSizes 云端正文 blob 的 bookId → size；**null = 本次尚未拉到**（正文状态一律 null）
     * @param remoteOnlyBooks 远端有完整正文、本机没有的书
     */
    fun build(
        books: List<Book>,
        words: List<SavedWord>,
        syncedMarks: Map<String, Long>,
        pendingKeys: Set<String>,
        remoteBlobSizes: Map<String, Long>?,
        remoteOnlyBooks: List<CloudBook>
    ): SyncDetail {
        val bookItems = books
            .map { book ->
                val progressUpdatedAt = book.progressUpdatedAt
                SyncBookItem(
                    bookId = book.id,
                    title = book.title,
                    progressState = stateOf(progressKey(book.id), progressUpdatedAt, syncedMarks, pendingKeys),
                    sourceState = remoteBlobSizes?.let { sizes ->
                        // 服务端只保留完整 blob；size > 0 才算「云端有正文」。
                        if ((sizes[book.id] ?: 0L) > 0L) SyncItemState.SYNCED else SyncItemState.LOCAL_ONLY
                    },
                    sourceSizeBytes = remoteBlobSizes?.get(book.id) ?: 0L,
                    progressUpdatedAt = progressUpdatedAt
                )
            }
            .sortedWith(compareBy({ it.title }, { it.bookId }))

        val wordItems = words
            .map { word ->
                // 老数据 updatedAt=0：退到 addedAt，与 SyncPayloadCodec 的编码口径一致。
                val updatedAt = if (word.updatedAt > 0L) word.updatedAt else word.addedAt
                SyncWordItem(
                    wordId = word.id,
                    headword = word.headword,
                    state = stateOf(wordKey(word.id), updatedAt, syncedMarks, pendingKeys),
                    updatedAt = updatedAt
                )
            }
            .sortedWith(compareBy({ it.headword.lowercase() }, { it.wordId }))

        // 汇总覆盖三类：书的进度、书的正文（拿到云端列表时）、词。
        val states = ArrayList<SyncItemState>(bookItems.size * 2 + wordItems.size)
        for (item in bookItems) {
            states += item.progressState
            item.sourceState?.let { states += it }
        }
        for (item in wordItems) states += item.state

        return SyncDetail(
            books = bookItems,
            words = wordItems,
            remoteOnlyBooks = remoteOnlyBooks,
            summary = SyncDetailSummary(
                syncedCount = states.count { it == SyncItemState.SYNCED },
                pendingCount = states.count { it == SyncItemState.PENDING },
                localOnlyCount = states.count { it == SyncItemState.LOCAL_ONLY }
            )
        )
    }

    /**
     * 单条记录的状态判定，规则见 [SyncDetailBuilder] 的类注释。
     *
     * 时间戳 0 / 水位 0 都按「没有真正上行过」处理：从未读过的书 `progressUpdatedAt=0`，
     * 它既不入 outbox，也会在 `SyncCoordinator.sync()` 收尾时被写上水位 0（该记录从未推送），
     * 若只按 `mark >= T` 比较会得到 `0 >= 0` → 误判「已同步」。
     */
    private fun stateOf(
        key: String,
        localUpdatedAt: Long,
        syncedMarks: Map<String, Long>,
        pendingKeys: Set<String>
    ): SyncItemState {
        if (key in pendingKeys) return SyncItemState.PENDING
        if (localUpdatedAt <= 0L) return SyncItemState.LOCAL_ONLY
        val mark = syncedMarks[key] ?: return SyncItemState.LOCAL_ONLY
        if (mark <= 0L) return SyncItemState.LOCAL_ONLY
        return if (mark >= localUpdatedAt) SyncItemState.SYNCED else SyncItemState.PENDING
    }
}
