package com.linguareader.app.sync

import android.content.Context
import com.linguareader.app.AppUiState
import com.linguareader.app.R
import com.linguareader.app.StatusTone
import com.linguareader.app.data.Book
import com.linguareader.app.data.LibraryRepository
import com.linguareader.app.data.UnsupportedBookFormatException
import com.linguareader.shared.sync.AutoSyncScheduler
import com.linguareader.shared.sync.CloudBook
import com.linguareader.shared.sync.CloudBookList
import com.linguareader.shared.sync.SyncDetailBuilder
import com.linguareader.shared.sync.SyncErrorKind
import com.linguareader.shared.sync.SyncException
import com.linguareader.shared.sync.SyncSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 协调器与宿主之间的最小契约（宿主目前只有 `AppViewModel`）。
 *
 * 只暴露四件事：读状态快照、写状态、起协程、刷新书库。
 *
 * ## 为什么是接口，而不是直接传 `AppViewModel`
 *
 * 因为**协调器的可测性从构造上就不该依赖 ViewModel 的存在**。
 *
 * 协调器不自己 `new` 状态、不自己起 scope、也不 `getApplication()` 去读文案——
 * 它每一步都问宿主。于是单测里只要给一个 40 行的假宿主（一个 `AppUiState` 变量 +
 * 一个 `runBlocking`/`TestScope`），就能把「自动同步到点该不该触发」「AUTH 只提示
 * 一次」「云端书单拉失败时要不要保留旧快照」这些**真正的分支**验掉，
 * 而不必构造整个 ViewModel（那要 Robolectric + 真实 Application + 一堆磁盘前置）。
 *
 * 反过来，如果这里收 `AppViewModel`：协调器立刻变成 ViewModel 的附庸——
 * 想测它就得把整个 Android 环境拖进来，也无法在桌面/其它外壳里复用。
 * 这层接口就是**把「状态是谁的」和「逻辑怎么写」切开的那道缝**。
 */
interface SyncHost {
    /** 当前 UI 状态快照（只读）。 */
    fun snapshot(): AppUiState

    /** 以「当前状态 → 新状态」的方式写回（与 `MutableStateFlow.value = ...` 等价）。 */
    fun updateState(transform: (AppUiState) -> AppUiState)

    /** 宿主生命周期内的协程作用域（ViewModel 里就是 viewModelScope）。 */
    val scope: CoroutineScope

    /** 书库数据被同步改写后重读书架（宿主侧还要顺带播种 AI 状态、排复习提醒）。 */
    fun refreshLibrary()
}

/**
 * 云同步协调器：**同步动作 / 自动同步调度 / 同步明细 / 书籍正文 blob** 四块从
 * `AppViewModel`（1999 行上帝对象）里抽出来后的归属地。
 *
 * 名字带 `App` 前缀是为了与 `:shared` 的 [com.linguareader.shared.sync.SyncCoordinator]
 * （传输与冲突语义的真相）区分：**那一个是协议编排水位，这一个只是 Android 侧的状态接线**，
 * 与 `AndroidSyncController` 同一层级。
 *
 * 拆分原则是「提取类 + 保留转发」，不是「搬走删掉」：
 * `AppViewModel` 里这些方法名的签名、语义、副作用一字未改，只是实现换了个住处。
 * 调用点（`MainActivity`、`BookshelfScreen`）零改动。
 *
 * **本类可脱离 Robolectric 单测**：宿主接口见 [SyncHost]。给一个假宿主
 * （一个 `AppUiState` 变量 + 一个 `TestScope`/`runBlocking` 即可，不必构造整个
 * `AppViewModel`、不必起真实 `Application` 与磁盘前置），就能验「防抖到点该不该触发」
 * 「AUTH 只提示一次」「书单拉失败要不要保留旧快照」这些分支。
 * 尚未补这类单测——**这是后续接手时该做的第一件事**。
 */
class AppSyncCoordinator(
    context: Context,
    /** 书库的 Android 壳（云端正文下载要落临时文件再重跑导入器）。 */
    private val library: LibraryRepository,
    private val host: SyncHost
) {
    private val appContext = context.applicationContext

    /** 装配 `:shared` 的同步编排（令牌走 Android Keystore）。 */
    private val sync = AndroidSyncController(appContext)

    /** 自动同步：纯逻辑防抖状态机 + 驱动它的 ticker；无排队时 ticker 立即退出，不空转。 */
    private val autoSyncScheduler = AutoSyncScheduler()
    private var autoSyncTicker: Job? = null

    /** 令牌失效（AUTH）在状态区只提示一次；登录成功/手动同步成功后复位。 */
    private var autoAuthWarned = false

    /** 取应用资源文案（协调器里没有 Composable 上下文）。 */
    private fun string(id: Int, vararg args: Any): String = appContext.getString(id, *args)

    /** 启动时把已保存的同步设置读进 UI 状态。 */
    fun settings(): SyncSettings = sync.settings()

    // --- 同步动作（自托管服务端，F-160）----------------------------------------

    /** 设置页改动后落盘；令牌不放这里（走 Android Keystore）。 */
    fun saveSyncSettings(settings: SyncSettings) {
        sync.saveSettings(settings)
        host.updateState { it.copy(syncSettings = settings) }
    }

    /**
     * 登录用界面传来的**当前输入**设置（不是已保存的旧值）：改了地址没点保存也能登录。
     * 落库由 [AndroidSyncController.login] 在成功后完成（失败不写设置）。
     */
    fun syncLogin(settings: SyncSettings, password: String) {
        if (settings.serverUrl.isBlank() || settings.username.isBlank()) {
            host.updateState { it.copy(syncStatus = string(R.string.sync_need_credentials)) }
            return
        }
        host.updateState { it.copy(syncBusy = true, syncStatus = string(R.string.sync_logging_in)) }
        host.scope.launch {
            val outcome = runCatching { sync.login(settings, password) }
            host.updateState {
                it.copy(
                    syncBusy = false,
                    syncSettings = outcome.getOrElse { settings },
                    syncStatus = outcome.fold(
                        onSuccess = { logged -> string(R.string.sync_logged_in, logged.username) },
                        onFailure = { syncFailureText(it) }
                    )
                )
            }
            if (outcome.isSuccess) refreshSyncDetail()
        }
    }

    fun syncNow() {
        val settings = host.snapshot().syncSettings
        if (settings.serverUrl.isBlank()) {
            host.updateState { it.copy(syncStatus = string(R.string.sync_need_credentials)) }
            return
        }
        // 与自动同步互斥：手动优先（自动链看到 busy 会跳过），并把已排队的防抖一并取消。
        if (host.snapshot().syncBusy) return
        autoSyncScheduler.cancel()
        host.updateState { it.copy(syncBusy = true, syncStatus = string(R.string.sync_syncing)) }
        host.scope.launch {
            val outcome = runCatching { sync.sync(settings) }
            host.updateState {
                it.copy(
                    syncBusy = false,
                    syncStatus = outcome.fold(
                        onSuccess = { report ->
                            if (report.unresolved.isEmpty()) {
                                string(R.string.sync_done, report.uploaded, report.downloaded, report.pending)
                            } else {
                                string(
                                    R.string.sync_done_unresolved,
                                    report.uploaded, report.downloaded, report.pending,
                                    report.unresolved.size
                                )
                            }
                        },
                        onFailure = { syncFailureText(it) }
                    )
                )
            }
            // 同步会改动书库/生词本落盘内容，刷新界面数据。
            host.refreshLibrary()
            if (outcome.isSuccess) {
                // 手动同步成功即令牌有效：复位 AUTH 提示标记，并顺手刷新云端书单
                // （规格：同步成功或弹窗内主动刷新）。
                autoAuthWarned = false
                refreshSyncDetail()
                refreshCloudBooks()
            }
        }
    }

    fun syncLogout() {
        sync.logout(host.snapshot().syncSettings)
        // 登出后云端正文列表即失效（不同账号/未登录不应沿用旧快照），明细回落到「未知」。
        host.updateState {
            it.copy(syncStatus = string(R.string.sync_logged_out), cloudBlobSizes = null)
        }
        refreshSyncDetail()
    }

    /**
     * 同步失败文案：错误类别由 :shared 的 [SyncException.kind] 给出，这里只做文案映射。
     * 未知类别（非同步异常）才回落到「失败：<原因>」。
     */
    private fun syncFailureText(error: Throwable): String = when ((error as? SyncException)?.kind) {
        SyncErrorKind.AUTH -> string(R.string.sync_error_auth)
        SyncErrorKind.NETWORK -> string(R.string.sync_error_network)
        SyncErrorKind.SERVER -> when ((error as? SyncException)?.status) {
            // 上传 blob 超服务端配额是 413，给专门文案而不是笼统「服务端错误」。
            413 -> string(R.string.sync_error_quota)
            else -> string(R.string.sync_error_server, error.message ?: error.javaClass.simpleName)
        }
        null -> string(R.string.sync_failed, error.message ?: error.javaClass.simpleName)
    }

    // --- 自动同步（task-4）：冷启动一次 + 本地变更后防抖 -------------------------

    /**
     * 本地数据变更（阅读进度/生词增删改/删书）**写盘成功后**调用，排一次 30 秒防抖同步。
     * 调度决策全在 :shared 的 [AutoSyncScheduler]；这里只是哑驱动：
     * - 未启用/未登录 → 清空排队并取消 ticker（同步默认关闭的大前提：这条链永不出网）；
     * - 已有 ticker 在跑 → 只重置到期时刻，不重复起协程；
     * - ticker 只在有排队时存活，到点/取消/禁用即退出，不常驻空转。
     */
    fun scheduleAutoSync() {
        val enabled = host.snapshot().let { it.syncSettings.enabled && sync.hasSession() }
        if (!autoSyncScheduler.onLocalChange(enabled, System.currentTimeMillis())) {
            autoSyncTicker?.cancel()
            autoSyncTicker = null
            return
        }
        if (autoSyncTicker?.isActive == true) return
        autoSyncTicker = host.scope.launch {
            try {
                while (true) {
                    val now = System.currentTimeMillis()
                    val stillEnabled = host.snapshot().let { it.syncSettings.enabled && sync.hasSession() }
                    if (autoSyncScheduler.consumeIfDue(stillEnabled, now)) {
                        runAutoSync()
                        break
                    }
                    val remaining = autoSyncScheduler.remainingMs(now) ?: break
                    delay(remaining)
                }
            } finally {
                autoSyncTicker = null
            }
        }
    }

    /**
     * 自动同步：冷启动一次 + 防抖到点各走这里。
     *
     * 与手动同步互斥（busy 守卫）；失败**安静**：
     * - NETWORK（离线/超时）：静默跳过，不写状态不打扰；
     * - AUTH（令牌失效）：状态区提示一次（[autoAuthWarned]），不反复；
     * - 其余：同样静默 —— 完整反馈只保留给手动「立即同步」。
     *
     * 已知边界（勿当 bug 修）：听书进度由 TtsPlaybackService 直接落盘、不经 AppViewModel，
     * 因此不触发本防抖链；它会在下次冷启动或手动同步时一并上行。
     */
    fun runAutoSync() {
        val settings = host.snapshot().syncSettings
        if (!settings.enabled || !sync.hasSession()) return
        if (host.snapshot().syncBusy) return
        host.updateState { it.copy(syncBusy = true) }
        host.scope.launch {
            val outcome = runCatching { sync.sync(settings) }
            val authFailed = (outcome.exceptionOrNull() as? SyncException)?.kind == SyncErrorKind.AUTH
            if (outcome.isSuccess) autoAuthWarned = false
            host.updateState {
                it.copy(
                    syncBusy = false,
                    syncStatus = if (authFailed && !autoAuthWarned) {
                        autoAuthWarned = true
                        string(R.string.sync_error_auth)
                    } else {
                        it.syncStatus
                    }
                )
            }
            if (outcome.isSuccess) {
                host.refreshLibrary()
                refreshSyncDetail()
            }
        }
    }

    // --- 同步明细（数据层）-------------------------------------------------------

    /**
     * 重建同步明细：**纯本地、零网络**——只读书库/生词本快照与本地水位文件
     * （`filesDir/sync/{synced,outbox}.json`），未登录时也能算出进度/生词的三态。
     *
     * 调用时机：init 结尾、登录成功后、手动/自动同步成功后、登出后，
     * 以及云端书单刷新成功/未登录早退之后；界面打开明细页时也可主动调一次。
     */
    fun refreshSyncDetail() {
        host.updateState { it.copy(syncDetailLoading = true) }
        host.scope.launch {
            val built = withContext(Dispatchers.IO) {
                val snapshot = host.snapshot()
                SyncDetailBuilder.build(
                    books = snapshot.books,
                    words = snapshot.savedWords,
                    syncedMarks = sync.syncedMarks(),
                    pendingKeys = sync.pendingKeys(),
                    remoteBlobSizes = snapshot.cloudBlobSizes,
                    remoteOnlyBooks = snapshot.cloudBooks
                ) to sync.lastSyncAt()
            }
            host.updateState {
                it.copy(syncDetail = built.first, syncDetailLoading = false, lastSyncAt = built.second)
            }
        }
    }

    // --- 书籍正文（blob，task-3）-------------------------------------------------

    /**
     * 导入成功后自动上传源文件。未启用/未登录/老书没有留存源文件都静默跳过；
     * 只有真正的网络/服务端失败才发 Snackbar（规格：失败必须可见）。
     */
    fun uploadBookSourceInBackground(book: Book) {
        val settings = host.snapshot().syncSettings
        if (!settings.enabled || !sync.hasSession()) return
        host.scope.launch {
            runCatching { sync.uploadSource(settings, book) }
                .onFailure { error ->
                    host.updateState {
                        it.copy(
                            notice = string(R.string.sync_upload_failed, book.title, syncFailureText(error)),
                            noticeTone = StatusTone.DANGER
                        )
                    }
                }
        }
    }

    /**
     * 拉取云端书单：远端有完整正文、本机书库没有的书。
     * 书名轻量增强（远端进度记录里的 title）失败时静默回退 bookId 前 8 位。
     * 顺带把云端正文清单（全部完整 blob 的 bookId → size）写进
     * [AppUiState.cloudBlobSizes]，供同步明细判定「正文」状态。
     */
    fun refreshCloudBooks() {
        val settings = host.snapshot().syncSettings
        if (!settings.enabled || !sync.hasSession()) {
            host.updateState {
                it.copy(
                    cloudBooks = emptyList(),
                    cloudBooksLoaded = true,
                    cloudBooksLoading = false,
                    // 未登录时旧快照已失效：明细的「正文」状态应回落到「未知」而不是沿用上一账号的结果。
                    cloudBlobSizes = null,
                    syncStatus = string(R.string.sync_need_login_for_cloud)
                )
            }
            refreshSyncDetail()
            return
        }
        host.updateState { it.copy(cloudBooksLoading = true) }
        host.scope.launch {
            val outcome = runCatching {
                val blobs = sync.listRemoteBlobs(settings)
                val titles = runCatching { sync.remoteBookTitles(settings) }.getOrDefault(emptyMap())
                val missing = CloudBookList.missing(blobs, host.snapshot().books.map { it.id }.toSet(), titles)
                // 只收 size > 0 的完整 blob（服务端 listBlobs 本就只返回完整 blob，这里再兜一层）。
                missing to blobs.filter { it.size > 0L }.associate { it.bookId to it.size }
            }
            val fetched = outcome.getOrNull()
            host.updateState {
                it.copy(
                    cloudBooksLoading = false,
                    cloudBooksLoaded = true,
                    // 失败时保留上一份快照（书单与正文状态都不因一次网络失败倒退）。
                    cloudBooks = fetched?.first ?: it.cloudBooks,
                    cloudBlobSizes = fetched?.second ?: it.cloudBlobSizes,
                    syncStatus = outcome.exceptionOrNull()?.let { e -> syncFailureText(e) } ?: it.syncStatus
                )
            }
            if (outcome.isSuccess) refreshSyncDetail()
        }
    }

    /** 下载云端书籍正文 → 临时文件 → 重跑导入器 → 刷新书库/书单。 */
    fun downloadCloudBook(cloud: CloudBook) {
        val settings = host.snapshot().syncSettings
        if (!settings.enabled || !sync.hasSession()) {
            host.updateState { it.copy(syncStatus = string(R.string.sync_need_login_for_cloud)) }
            return
        }
        host.updateState {
            it.copy(
                syncBusy = true,
                syncStatus = string(R.string.sync_cloud_downloading, cloud.displayName)
            )
        }
        host.scope.launch {
            val target = library.downloadScratchFile()
            val outcome = runCatching {
                sync.downloadBlob(settings, cloud.bookId, target)
                library.importDownloadedFile(target, cloud.displayName)
            }
            target.delete()
            outcome.fold(
                onSuccess = { imported ->
                    host.refreshLibrary()
                    host.updateState {
                        it.copy(
                            syncBusy = false,
                            syncStatus = null,
                            cloudBooks = it.cloudBooks.filterNot { item -> item.bookId == cloud.bookId },
                            notice = string(R.string.sync_cloud_downloaded, imported.title),
                            noticeTone = StatusTone.SUCCESS
                        )
                    }
                },
                onFailure = { error ->
                    val text = if (error is UnsupportedBookFormatException) {
                        string(R.string.sync_cloud_unknown_format)
                    } else {
                        syncFailureText(error)
                    }
                    host.updateState {
                        it.copy(
                            syncBusy = false,
                            syncStatus = null,
                            notice = string(R.string.sync_download_failed, text),
                            noticeTone = StatusTone.DANGER
                        )
                    }
                }
            )
        }
    }
}
