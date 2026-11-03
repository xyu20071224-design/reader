package com.linguareader.shared.ui.state

import com.linguareader.shared.ai.AiBookStatus
import com.linguareader.shared.data.Book

/**
 * 书架状态：书库列表 + 书卡上的异步状态。
 *
 * 现状里这 16 个字段只被 `BookshelfScreen` 读（方案 §0 表格），却挂在全局
 * `AppUiState` 上，于是 `BookshelfScreen` 要收 54 个参数（`MainActivity.kt:156-213`）。
 * 拆出来之后，书架将来只需要「1 个 BookshelfState + 1 个 BookshelfActions」（§3.2）。
 *
 * 纯 Kotlin，可单测；不持有弹层状态（弹层在
 * [com.linguareader.shared.ui.state.OverlayStack]，页面在
 * [com.linguareader.shared.ui.nav.Navigator]）。
 */
data class BookshelfState(
    val books: List<Book> = emptyList(),
    /** 每本书的 AI 语境档案生成状态（书卡上的「就绪/降级」徽标）。 */
    val aiStatuses: Map<String, AiBookStatus> = emptyMap(),
    /** 正在对齐中文译本的书 id（数十秒级操作，界面据此显示进度并防重复触发）。 */
    val attachingTranslation: Set<String> = emptySet(),
    /** 对齐档案已旧于当前对齐器版本的书 id（版本书架提示「对照待更新」）。 */
    val outdatedTranslations: Set<String> = emptySet(),
    /** 最近一次手动导入的逐条拒绝原因；非空时弹详情对话框。 */
    val manualImportRejected: List<String>? = null
) {
    /** 空书架引导页的判据（加载完成且确实没有书）。 */
    fun isEmptyAfterLoad(loading: Boolean): Boolean = !loading && books.isEmpty()
}
