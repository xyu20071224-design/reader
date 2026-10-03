package com.linguareader.shared.sync

/**
 * 云端书单条目：**远端有完整 blob、本机书库没有**的书。
 * 服务端不存书名（[BlobInfo] 只有 bookId/size/sha256），[title] 仅在能从进度记录
 * 拿到时才有值；展示用 [displayName] 回退到 bookId 前 8 位。
 */
data class CloudBook(
    val bookId: String,
    val sizeBytes: Long,
    val title: String = ""
) {
    val displayName: String get() = title.trim().ifBlank { bookId.take(8) }
}

/**
 * 云端书单比对（纯逻辑、可单测）：远端 blob 列表 + 本机书 id 集合 → 待下载条目。
 * 大小超过 0 才认为服务端 blob 完整（服务端 listBlobs 只返回完整 blob，这里再兜一层）。
 */
object CloudBookList {

    fun missing(
        remote: List<BlobInfo>,
        localBookIds: Set<String>,
        titles: Map<String, String> = emptyMap()
    ): List<CloudBook> = remote
        .filter { it.bookId.isNotBlank() && it.bookId !in localBookIds && it.size > 0L }
        .distinctBy { it.bookId }
        .map { CloudBook(it.bookId, it.size, titles[it.bookId].orEmpty().trim()) }
        .sortedWith(compareBy({ it.displayName }, { it.bookId }))
}
