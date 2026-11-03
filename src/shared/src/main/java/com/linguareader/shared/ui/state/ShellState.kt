package com.linguareader.shared.ui.state

import com.linguareader.shared.ui.nav.Route

/** 一次性轻提示（Snackbar）的语义。与文案解耦，渲染端据此换容器配色。 */
enum class NoticeTone { SUCCESS, DANGER, NEUTRAL }

/**
 * 壳层状态：跨屏幕的外壳（进程级加载、全局提示、当前页面）。
 *
 * 这是方案 §3.2 五切片之一。拆分原则见 [com.linguareader.shared.ui.state] 包说明：
 * **切片是投影不是搬家**——同一个 AppUiState 可以同时投影到多个切片，每个屏幕
 * 只收自己真正读的那些字段，超集仍留在 `AppUiState` 里。
 *
 * 纯 Kotlin：不依赖 Android/Compose，桌面壳直接复用。
 */
data class ShellState(
    /**
     * 当前页面。
     *
     * 迁移期由 `AppUiState.currentBook` 推导（null → 书架，非空 → 阅读页），
     * 也就是把 `MainActivity.kt:155` 的 `if (currentBook == null)` 二态判断
     * 收成一个字段。P3 接线后由 `Navigator` 直接给值。
     */
    val route: Route = Route.Bookshelf,
    /** 书库首屏加载中（`AppUiState.loading`）。 */
    val loading: Boolean = true,
    /** 一次性轻提示；显示后由界面调用 `clearNotice()` 清空。 */
    val notice: String? = null,
    val noticeTone: NoticeTone = NoticeTone.NEUTRAL,
    /** 需要用户确认的一次性消息（带标题），与 [notice] 的 Snackbar 语义区分。 */
    val message: String? = null,
    val messageTitle: String = ""
) {
    /** 是否应该渲染阅读页（迁移期等价于「当前书非空」）。 */
    val isReading: Boolean get() = route is Route.Reader
}
