package com.linguareader.shared.update

import java.io.File

/** 更新流程的可见阶段，驱动 UpdateSheet 的状态行与书架顶栏红点。 */
enum class AppUpdatePhase {
    /** 空闲：本会话还没查过，或刚清掉结果。 */
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}

data class AppUpdateUiState(
    val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val autoCheckEnabled: Boolean = false,
    val info: AppUpdateInfo? = null,
    /** 已下载待安装的 APK；非空且 phase=DOWNLOADED 时可拉起安装器。 */
    val downloadedApk: File? = null,
    /** 下载进度 0..100，仅 DOWNLOADING 有效。 */
    val progress: Int = 0,
    /** ERROR 阶段的失败原因（用户可读）。 */
    val error: String? = null
) {
    /** 本会话发现过新版（含下载中/待安装），书架顶栏据此显示红点。 */
    val updateAvailable: Boolean
        get() = phase in setOf(
            AppUpdatePhase.AVAILABLE,
            AppUpdatePhase.DOWNLOADING,
            AppUpdatePhase.DOWNLOADED
        )
}
