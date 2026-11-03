package com.linguareader.shared.ui.nav

/**
 * 应用内的一级页面（屏幕），类型安全的导航目标。
 *
 * 为什么不是字符串路由：现有实现靠 `AppUiState.currentBook == null` 的二态切换
 * （`MainActivity.kt:155`），加一个屏幕就多一个布尔/多一个 when 分支，编译器帮不上忙。
 * 改成 sealed interface 后，`when` 穷举会在加屏时强制每个消费点表态。
 *
 * 纯 Kotlin、零依赖：桌面壳（`desktopApp`）可直接复用同一份导航模型；
 * 也刻意**不引入** `androidx.navigation:navigation-compose`（见方案 §6 决策 1）。
 *
 * 加屏的做法：在这里加一个变体，然后让 `when (route)` 的消费点编译失败——
 * 那是设计的一部分，不是麻烦。
 */
sealed interface Route {

    /** 书架（导航栈的根，永远不弹出）。 */
    data object Bookshelf : Route

    /** 生词本。现为书架内的次级视图（`showVocabulary` 布尔），迁移后是一级页面。 */
    data object Vocabulary : Route

    /** 云同步详情页。现为 `SyncSheet` 弹层，迁移后是一级页面。 */
    data object SyncDetail : Route

    /**
     * 阅读页。
     *
     * @param bookId 书库里的书 id。阅读页的全部状态（位置/弹层）由阅读页自己的
     *   状态机持有（见 `app/reader/ReaderScreenState.kt`），导航层只认 id。
     */
    data class Reader(val bookId: String) : Route
}
