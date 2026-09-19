package com.linguareader.shared.sync

import java.io.File

/** 服务端上的一本书正文。 */
data class BlobInfo(val bookId: String, val size: Long, val sha256: String)

/** 断点续传的续传点。 */
data class BlobStatus(val complete: Boolean, val uploaded: Long)

/**
 * 书籍正文传输（服务端已支持分片 + 断点续传 + Range 下载）。
 *
 * 同步单元 = 用户导入的**原始书籍文件**（bookId = 源文件 SHA-256 前 20 hex）；
 * 新设备拿到文件后本地重跑导入器生成解压产物，避免目录树同步与路径耦合。
 */
interface SyncBlobApi {
    suspend fun listBlobs(): List<BlobInfo>

    suspend fun blobStatus(bookId: String): BlobStatus

    /** 上传（自动续传）；已完整存在时直接返回，不重复传。 */
    suspend fun uploadBlob(bookId: String, file: File, chunkSize: Int = DEFAULT_CHUNK): BlobStatus

    /** 下载（自动续传）；返回最终字节数。 */
    suspend fun downloadBlob(bookId: String, target: File, chunkSize: Int = DEFAULT_CHUNK): Long

    suspend fun deleteBlob(bookId: String)

    companion object {
        const val DEFAULT_CHUNK: Int = 1 shl 20
    }
}
