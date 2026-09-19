package com.linguareader.shared.sync

/**
 * 平台侧要实现的本地数据接缝：把本机可同步数据导出为记录，并把远端记录落回本地。
 * 实现由 Android（`:app`）与桌面（`:desktopApp`）各自提供，内核不认平台。
 */
interface SyncSource {
    /** 当前本机全部可同步记录（快照语义，幂等）。 */
    suspend fun snapshot(): List<SyncRecord>

    /** 应用远端记录；实现方负责用 [SyncMerger] 与本地版本合并后落盘。 */
    suspend fun apply(remote: List<SyncRecord>)
}
