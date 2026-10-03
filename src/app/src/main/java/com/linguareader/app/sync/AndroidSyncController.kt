package com.linguareader.app.sync

import android.content.Context
import com.linguareader.app.data.AndroidAppContext
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.LibraryRepository
import com.linguareader.shared.data.VocabularyRepository
import com.linguareader.shared.sync.BlobInfo
import com.linguareader.shared.sync.BookSourceFile
import com.linguareader.shared.sync.FileSyncStateStore
import com.linguareader.shared.sync.HttpSyncApi
import com.linguareader.shared.sync.LocalSyncSource
import com.linguareader.shared.sync.SyncCollections
import com.linguareader.shared.sync.SyncCoordinator
import com.linguareader.shared.sync.SyncErrorKind
import com.linguareader.shared.sync.SyncException
import com.linguareader.shared.sync.SyncReport
import com.linguareader.shared.sync.SyncSettings
import java.io.File

/**
 * Android 端同步接线：把 :shared 的同步编排接到 Android 的私有目录与 Keystore。
 *
 * 只做「装配」：设置读写、登录、同步、登出、书籍正文（blob）上传/下载；
 * 冲突与传输语义全在 :shared。
 */
class AndroidSyncController(context: Context) {

    private val appContext = AndroidAppContext(context.applicationContext)
    private val prefs = appContext.prefs(SyncSettings.PREFS_NAME)
    private val stateStore = FileSyncStateStore(File(appContext.filesDir, "sync"))
    private val secrets = AndroidSecretStore(context.applicationContext)

    /** 与书库/生词本 facade 共用同一批文件（都落在 filesDir）。 */
    val library = LibraryRepository(appContext)
    val vocabulary = VocabularyRepository(appContext)
    private val source = LocalSyncSource(library, vocabulary)

    fun settings(): SyncSettings = SyncSettings.load(prefs)

    fun saveSettings(settings: SyncSettings) {
        SyncSettings.save(prefs, settings)
    }

    fun hasSession(): Boolean = !secrets.get(SyncCoordinator.TOKEN_KEY).isNullOrBlank()

    private fun coordinator(settings: SyncSettings): SyncCoordinator {
        val api = HttpSyncApi(settings.serverUrl, pinnedCertSha256 = settings.pinnedCertSha256)
        return SyncCoordinator(api, source, stateStore, secrets)
    }

    suspend fun login(settings: SyncSettings, password: String): SyncSettings {
        coordinator(settings).login(settings.username, password)
        val enabled = settings.copy(enabled = true)
        saveSettings(enabled)
        return enabled
    }

    /** 恢复会话后跑一次完整同步。 */
    suspend fun sync(settings: SyncSettings): SyncReport {
        val coordinator = coordinator(settings)
        coordinator.restoreSession()
        return coordinator.sync()
    }

    fun logout(settings: SyncSettings) {
        coordinator(settings).logout()
    }

    // --- 书籍正文（blob）------------------------------------------------------

    /**
     * 带已保存令牌的 blob API；未启用（[SyncSettings.enabled]）或未登录时返回 null，
     * 调用方据此静默跳过——出网只发生在显式启用 + 已登录之后。
     */
    private fun blobApi(settings: SyncSettings): HttpSyncApi? {
        if (!settings.enabled || settings.serverUrl.isBlank()) return null
        val token = secrets.get(SyncCoordinator.TOKEN_KEY)?.takeIf { it.isNotBlank() } ?: return null
        return HttpSyncApi(settings.serverUrl, pinnedCertSha256 = settings.pinnedCertSha256)
            .apply { setToken(token) }
    }

    /** 云端 blob 列表（远端有完整正文的书）。不可用时抛 [SyncException] 由 UI 映射文案。 */
    suspend fun listRemoteBlobs(settings: SyncSettings): List<BlobInfo> =
        requireBlobApi(settings).listBlobs()

    /**
     * 远端进度记录里的书名。服务端 blob 只有 bookId/size/sha256，**不存书名**；
     * 这是给云端书单的增强，拿不到时调用方回退 bookId 前 8 位。
     */
    suspend fun remoteBookTitles(settings: SyncSettings): Map<String, String> {
        val page = requireBlobApi(settings).fullState()
        return page.records
            .filter { it.collection == SyncCollections.PROGRESS }
            .mapNotNull { record ->
                val title = record.payload.optString("title").trim()
                if (title.isEmpty()) null else record.id to title
            }
            .toMap()
    }

    /**
     * 上传一本书留存的源文件（[BookSourceFile]）。已完整存在时服务端直接返回（去重）。
     * 未启用/未登录/老书没有留存源文件时返回 false，**不抛异常**。
     */
    suspend fun uploadSource(settings: SyncSettings, book: Book): Boolean {
        val api = blobApi(settings) ?: return false
        val file = BookSourceFile.locate(book) ?: return false
        api.uploadBlob(book.id, file)
        return true
    }

    /** 下载 blob 到 [target]（分片 + 断点续传），返回最终字节数。 */
    suspend fun downloadBlob(settings: SyncSettings, bookId: String, target: File): Long =
        requireBlobApi(settings).downloadBlob(bookId, target)

    private fun requireBlobApi(settings: SyncSettings): HttpSyncApi = blobApi(settings)
        ?: throw SyncException("云同步未启用或未登录", 0, SyncErrorKind.AUTH)
}
