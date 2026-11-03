/**
 * 屏幕状态的纯 Kotlin 切片（方案 §3.2）。
 *
 * ## 这是什么
 *
 * `AppViewModel.AppUiState` 是一个 31 字段的全局 data class，所有屏幕都从它读，
 * 于是每个屏幕都拿着用不上的字段（`BookshelfScreen` 收 54 个参数）。这里按归属
 * 把它切成五份：
 *
 * | 切片 | 归属 |
 * | --- | --- |
 * | `ShellState` | 外壳：当前页面、加载、全局提示 |
 * | `BookshelfState` | 书架：书库列表、书卡上的异步状态 |
 * | `ReaderState` | 阅读页：当前书 + 查词/复习需要的业务数据 |
 * | `VocabState` | 生词本/复习：词表、复习节奏、提醒渠道 |
 * | `SettingsState` | 设置：AI、同步、更新、存储 |
 *
 * ## 三条约定
 *
 * 1. **切片是投影，不是搬家**。`AppUiState` 暂时原样保留（另有一条开发线在改它），
 *    `AppUiState.split()`（在 `:app`）每次从同一份状态投影出五个切片。所以
 *    「复习节奏」可以同时出现在 `ReaderState` 与 `VocabState` —— 它们读到的是同一份
 *    真相，不存在两份拷贝漂移。
 * 2. **纯 Kotlin**：不 import `android.*` / `androidx.*`，桌面壳复用同一份模型。
 *    有新字段时先想清楚它属于哪个切片；不属于任何一个就说明它要么该下沉成参数，
 *    要么是纯 UI 瞬态（应留在界面层的 `rememberSaveable`）。
 * 3. **字段不丢**：`:app` 的 `AppUiStateSplitTest` 用反射盯着 `AppUiState` 的每个
 *    字段都必须有归属，加了字段不拆会红——这条是这次拆分的验收标准。
 *
 * ## 相关的两个纯模型
 *
 * - 页面：[com.linguareader.shared.ui.nav.Route] + `Navigator`（`ui/nav`）
 * - 弹层：[com.linguareader.shared.ui.state.Overlay] + `OverlayStack`（本包）
 */
internal object StatePackageDoc
