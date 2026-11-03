package com.linguareader.app.state

import com.linguareader.app.AppUiState

/**
 * 「AppUiState 的每个字段去了哪个切片」的账本。
 *
 * 它唯一的用途是被 AppUiStateSplitTest 用反射对账：
 * - 账本里列了、AppUiState 里没有 → 字段被删了却没清账（测试红）；
 * - AppUiState 里有、账本没列 → 加了字段却没分片（测试红）。
 *
 * 加字段时先想清楚它属于哪个切片；真的只能留在 :app 的（载荷带 Android 类型），
 * 记到 APP_ONLY 并在 AppOnlyState 里加字段。
 *
 * 注：这不是「运行时校验」，是编译期清单 + 测试期对账。运行时不读它。
 */
object AppUiStateMapping {

    /** 落进 ShellState。 */
    val SHELL = setOf(
        "currentBook", // 迁移期由它推导出 Route；P3 后改由 Navigator 直接给值
        "loading",
        "notice",
        "noticeTone",
        "message",
        "messageTitle"
    )

    /** 落进 BookshelfState。 */
    val BOOKSHELF = setOf(
        "books",
        "aiStatuses",
        "attachingTranslation",
        "outdatedTranslations",
        "manualImportRejected"
    )

    /** 落进 ReaderState（与 VocabState 有意重叠）。 */
    val READER = setOf(
        "currentBook",
        "aiSettings",
        "savedWords",
        "reviewPreset",
        "customReview",
        "reminders"
    )

    /** 落进 VocabState（与 ReaderState 有意重叠）。 */
    val VOCAB = setOf(
        "savedWords",
        "reviewPreset",
        "customReview",
        "reminders"
    )

    /** 落进 SettingsState。 */
    val SETTINGS = setOf(
        "aiSettings",
        "update",
        "storage",
        "storageScanning",
        "syncSettings",
        "syncStatus",
        "syncBusy",
        "cloudBooks",
        "cloudBooksLoaded",
        "cloudBooksLoading",
        // F-160 云同步明细（2026-11-04 从 main 合入）。归属 SETTINGS：它们是云同步
        // 这一功能的状态，与 syncSettings/syncStatus 同族。
        "syncDetail",
        "syncDetailLoading",
        "cloudBlobSizes",
        "lastSyncAt"
    )

    /**
     * **暂时**留在 :app 的字段（载荷类型带 Android 依赖，见 [AppOnlyState]）。
     *
     * 这不是永久归属，是搬家清单。每个字段的解锁条件：
     * - `launchPrompt`：`LaunchPromptUi`/`Greeting`/`UpdateNote` 里的 `@StringRes Int`
     *   改成 `com.linguareader.shared.res.SharedString` 之后，可以并入 [SHELL]
     *   （它就是个覆盖在壳上的提示）。
     * - `packs`：`PackUiState`/`PackUiItem`/`PackResidual`/`RemotePacksState` 从
     *   `:app/packs` 下沉到 `:shared/packs` 之后（它们带 `java.io.File` 与 `:app` 专属枚举，
     *   资源包契约的下沉属于资源包线），并入 [SETTINGS]。
     * - `aiTranslationProgress` / `aiTranslationPrepare` / `manualTranslationPrepare` /
     *   `pendingManualExport`：这些是 AI 整本翻译的流程状态，正确归属是
     *   **新的 `TranslationState` 切片**（P3 迁书架/AI 弹层时再切），不是 `SettingsState`。
     *   在那之前先留在 `AppOnlyState`，避免硬塞进不合适的切片。
     *
     * **何时可以删掉 `AppOnlyState`**：上面三条全部完成——即
     * `grep -r "AppOnlyState" src/app` 只剩 `AppSlices.kt` 一处、且
     * `APP_ONLY` 集合为空。届时 `AppUiStateSplitTest` 的账本自动全绿，
     * `AppOnlyState.kt` 与 `APP_ONLY` 一起删除。
     */
    val APP_ONLY = setOf(
        "launchPrompt",
        "aiTranslationProgress",
        "aiTranslationPrepare",
        "manualTranslationPrepare",
        "pendingManualExport",
        "packs"
    )

    /** 账本覆盖的全部字段名（去重后）。 */
    val ALL_FIELDS: Set<String>
        get() = SHELL + BOOKSHELF + READER + VOCAB + SETTINGS + APP_ONLY

    /**
     * AppUiState 的**实例**字段名全集。
     *
     * 必须排除两类编译器产物，否则账本永远对不上：
     * - `synthetic` 字段（Kotlin 的 `$default` 之类）；
     * - `static` 字段——**Compose 编译器会给参与组合的类塞一个 `public static final int $stable`**，
     *   它不是 data class 的属性，却被 `declaredFields` 读出来（实测就是它让账本从 31 变成 32）。
     */
    fun actualFields(): Set<String> = AppUiState::class.java.declaredFields
        .filterNot { it.isSynthetic }
        .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .map { it.name }
        .toSet()
}
