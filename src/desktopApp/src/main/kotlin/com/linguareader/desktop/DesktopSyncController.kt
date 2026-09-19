package com.linguareader.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linguareader.shared.app.AppContext
import com.linguareader.shared.data.LibraryRepository
import com.linguareader.shared.data.VocabularyRepository
import com.linguareader.shared.sync.FileSyncStateStore
import com.linguareader.shared.sync.HttpSyncApi
import com.linguareader.shared.sync.LocalSyncSource
import com.linguareader.shared.sync.SyncCoordinator
import com.linguareader.shared.sync.SyncSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 桌面端同步 UI 状态与动作。真正的同步逻辑全在 :shared（SyncCoordinator），
 * 这里只做「读设置 -> 建 api/coordinator -> 报告结果」。
 */
class DesktopSyncController(
    context: AppContext,
    private val library: LibraryRepository,
    private val vocabulary: VocabularyRepository,
    home: File,
    private val scope: CoroutineScope
) {
    private val prefs = context.prefs(SyncSettings.PREFS_NAME)
    private val stateStore = FileSyncStateStore(File(home, "sync"))
    private val secrets = DesktopSecretStore(home)

    var settings by mutableStateOf(SyncSettings.load(prefs))
        private set

    var status by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    /** false = 令牌落在 0600 文件而不是系统凭据管理器，UI 必须提示。 */
    val secureStorage: Boolean get() = secrets.secure

    fun update(newSettings: SyncSettings) {
        settings = newSettings
        SyncSettings.save(prefs, newSettings)
    }

    fun login(password: String) {
        val current = settings
        if (current.serverUrl.isBlank() || current.username.isBlank()) {
            status = "请先填写服务器地址与用户名"
            return
        }
        run("登录中…") { coordinator ->
            coordinator.login(current.username, password)
            "已登录：" + current.username
        }
    }

    fun syncNow() {
        run("同步中…") { coordinator ->
            if (!coordinator.restoreSession()) error("未登录，请先登录")
            val report = coordinator.sync()
            val tail = if (report.unresolved.isEmpty()) "" else "，未收敛 " + report.unresolved.size + " 条"
            "同步完成：上传 " + report.uploaded + "，下载 " + report.downloaded + "，待推送 " + report.pending + tail
        }
    }

    fun logout() {
        coordinator().logout()
        status = "已登出"
    }

    private fun coordinator(): SyncCoordinator {
        val current = settings
        val api = HttpSyncApi(current.serverUrl, pinnedCertSha256 = current.pinnedCertSha256)
        return SyncCoordinator(api, LocalSyncSource(library, vocabulary), stateStore, secrets)
    }

    private fun run(busyText: String, block: suspend (SyncCoordinator) -> String) {
        if (settings.serverUrl.isBlank()) {
            status = "请先填写服务器地址"
            return
        }
        busy = true
        status = busyText
        scope.launch {
            status = try {
                block(coordinator())
            } catch (error: Throwable) {
                "失败：" + (error.message ?: error.javaClass.simpleName)
            }
            busy = false
        }
    }
}
