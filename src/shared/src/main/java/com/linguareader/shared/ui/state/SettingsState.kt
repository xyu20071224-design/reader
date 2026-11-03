package com.linguareader.shared.ui.state

import com.linguareader.shared.ai.AiSettings
import com.linguareader.shared.data.StorageReport
import com.linguareader.shared.sync.CloudBook
import com.linguareader.shared.sync.SyncSettings
import com.linguareader.shared.update.AppUpdateUiState

/**
 * 设置类状态：AI、云同步、自动更新、存储体检。
 *
 * 这些字段的共同点：背后都是「设置页/弹层」里的异步流程，生命周期长于任何一个
 * 屏幕（更新检查在书架跑、同步在两处跑）。所以它们归设置切片而不是某个屏幕切片。
 *
 * **资源包（`AppUiState.packs`）不在这里**：它的载荷类型（`PackUiState` /
 * `PackUiItem` / `PackResidual` / `RemotePacksState`）目前住在 `:app/packs`，
 * 带 `java.io.File` 与 `:app` 专属枚举，下沉属于资源包线的工作。迁移期由
 * `:app` 的 `AppUiState.split()` 兜在 `AppOnlyState` 里，字段不会丢。
 *
 * 纯 Kotlin，可单测。
 */
data class SettingsState(
    val aiSettings: AiSettings = AiSettings(),
    val update: AppUpdateUiState = AppUpdateUiState(),
    /** 存储体检结果；null = 还没扫过（扫盘只在用户打开存储页面时跑）。 */
    val storage: StorageReport? = null,
    val storageScanning: Boolean = false,
    val syncSettings: SyncSettings = SyncSettings(),
    /** 同步最近一次状态文案。 */
    val syncStatus: String? = null,
    val syncBusy: Boolean = false,
    /** 云端书单：远端有完整正文、本机没有的书。 */
    val cloudBooks: List<CloudBook> = emptyList(),
    /** 是否已成功拉取过一次云端书单（区分「没拉过」与「拉过但为空」）。 */
    val cloudBooksLoaded: Boolean = false,
    val cloudBooksLoading: Boolean = false
)
