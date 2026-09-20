package com.linguareader.app.sync

import android.content.Context
import com.linguareader.app.data.AndroidAppContext
import com.linguareader.shared.data.LibraryRepository
import com.linguareader.shared.data.VocabularyRepository
import com.linguareader.shared.sync.FileSyncStateStore
import com.linguareader.shared.sync.HttpSyncApi
import com.linguareader.shared.sync.LocalSyncSource
import com.linguareader.shared.sync.SyncCoordinator
import com.linguareader.shared.sync.SyncReport
import com.linguareader.shared.sync.SyncSettings
import java.io.File

/**
 * Android 端同步接线：把 :shared 的同步编排接到 Android 的私有目录与 Keystore。
 *
 * 只做「装配」：设置读写、登录、同步、登出；冲突与传输语义全在 :shared。
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
}
