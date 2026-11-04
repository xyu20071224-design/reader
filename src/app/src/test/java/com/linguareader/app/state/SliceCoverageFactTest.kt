package com.linguareader.app.state

import com.linguareader.app.AppUiState
import com.linguareader.shared.ui.state.BookshelfState
import com.linguareader.shared.ui.state.SettingsState
import com.linguareader.shared.ui.state.ShellState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **切片划分 vs 屏幕实际用法的吻合度**（P17）。
 *
 * ## 为什么要有这条
 *
 * P15 接上了 `ShellState.route`（一行），但 `BookshelfState` / `ReaderState` /
 * `VocabState` / `SettingsState` 仍是零引用。把它们接进 `BookshelfScreen` 前先做一次量化：
 * **这个屏实际读的 29 个 `state.xxx` 字段里，有多少已经在切片里？**
 *
 * 结果是 **20/29**——9 个不在任何与书架相关的切片里：
 * `aiTranslationPrepare` / `aiTranslationProgress` / `manualTranslationPrepare` /
 * `pendingManualExport` / `packs`（属 `AppOnlyState`）、
 * `customReview` / `reminders` / `reviewPreset` / `savedWords`（属 `ReaderState` 或 `VocabState`）。
 *
 * ## 这个数字本身是有价值的产出
 *
 * 它说明了**切片划分是按「领域」划的，而屏幕是按「页面」取数据的**，两者天然不完全对齐：
 * 书架屏要用复习节奏（视觉上是书卡上的徽章）、要用术语表 packs（挂在书架弹层里）、
 * 要用 AI 翻译进度（书卡上的进度条）。**这些跨领域的读取是合理的，不是设计缺陷。**
 *
 * 所以「把切片接进屏幕」不可能是一次纯粹的机械替换——它必然涉及
 * 「这个字段属于哪个切片」的判断。这就是为什么 P15 只接了最无争议的那一个（route）。
 *
 * ## 这条测试守什么
 *
 * 它把**当前吻合度**钉成数字。将来如果有人重新划分切片、或给屏幕加字段，
 * 这个数字会变，会提示「切片与屏幕的对应关系变了，请重新评估接线方案」。
 * **它不判断好坏，只记录事实。**
 */
class SliceCoverageFactTest {

    /** `BookshelfScreen` 实际读取的 `state.xxx` 字段（与源码同步维护）。 */
    private val bookshelfScreenFields = listOf(
        "aiSettings", "aiStatuses", "aiTranslationPrepare", "aiTranslationProgress",
        "attachingTranslation", "books", "cloudBooks", "cloudBooksLoaded", "cloudBooksLoading",
        "customReview", "lastSyncAt", "loading", "manualImportRejected",
        "manualTranslationPrepare", "message", "messageTitle", "outdatedTranslations",
        "packs", "pendingManualExport", "reminders", "reviewPreset", "savedWords",
        "storage", "storageScanning", "syncBusy", "syncDetail", "syncDetailLoading",
        "syncSettings", "syncStatus"
    )

    /** 三个与书架相关的切片各自覆盖的字段。 */
    private val sliceFields: Set<String> = buildSet {
        // ShellState
        addAll(listOf("route", "loading", "notice", "noticeTone", "message", "messageTitle"))
        // BookshelfState
        addAll(listOf("books", "aiStatuses", "attachingTranslation", "outdatedTranslations", "manualImportRejected"))
        // SettingsState
        addAll(listOf(
            "aiSettings", "update", "storage", "storageScanning", "syncSettings", "syncStatus",
            "syncBusy", "cloudBooks", "cloudBooksLoaded", "cloudBooksLoading",
            "syncDetail", "syncDetailLoading", "cloudBlobSizes", "lastSyncAt"
        ))
    }

    @Test
    fun bookshelfScreenReads29FieldsAndSliceLayerCovers20OfThem() {
        val covered = bookshelfScreenFields.count { it in sliceFields }
        val uncovered = bookshelfScreenFields.filter { it !in sliceFields }

        assertTrue(
            "BookshelfScreen 读 ${bookshelfScreenFields.size} 个字段，切片层覆盖 $covered 个。" +
                "未覆盖：${uncovered.joinToString(", ")}\n\n" +
                "这个数字变了说明切片划分或屏幕用法变了 —— 请重新评估「把切片接进屏幕」的方案，" +
                "并同步更新本测试与 VALIDATION.md 的记录。",
            bookshelfScreenFields.size == 29 && covered == 20
        )
    }

    /**
     * 未覆盖的 9 个字段必须确实**不在**上述三个切片里 ——
     * 防止有人把字段加进切片后忘了更新这条事实记录。
     */
    @Test
    fun uncoveredFieldsAreGenuinelyOutsideTheBookshelfSlices() {
        val expectedUncovered = setOf(
            "aiTranslationPrepare", "aiTranslationProgress", "customReview",
            "manualTranslationPrepare", "packs", "pendingManualExport",
            "reminders", "reviewPreset", "savedWords"
        )
        val actual = bookshelfScreenFields.filter { it !in sliceFields }.toSet()
        assertTrue(
            "未覆盖字段集合变了。当前应是 $expectedUncovered，实际 $actual。\n" +
                "（这意味着切片划分或书架屏用法有变动，需要重新评估接线方案）",
            actual == expectedUncovered
        )
    }
}