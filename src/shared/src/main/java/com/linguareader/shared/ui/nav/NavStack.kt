package com.linguareader.shared.ui.nav

/**
 * 导航栈：不可变值对象，所有操作都返回新实例（无操作时返回 `this`）。
 *
 * 风格照 `app/reader/ReaderScreenState.kt` 的 `ReaderPosition`/`ReaderOverlays`：
 * 状态是 data class，迁移是纯函数，于是返回键优先级、栈深上限这类容易改错的东西
 * 可以在 JVM 单测里断言，而不是靠在真实界面上点。
 *
 * 不变量：
 * - 栈**永不为空**（至少有一个根路由）——「空栈」在 UI 层没有可用语义，
 *   构造时就拒绝，比事后每处判空更省事；
 * - 根路由（`entries.first()`）不会被弹出，[canGoBack] 为 false 时退出由壳层决定。
 */
data class NavStack(val entries: List<Route>) {

    init {
        require(entries.isNotEmpty()) { "导航栈至少要有根路由，实际为空" }
    }

    /** 栈深；1 表示只有根路由。 */
    val depth: Int get() = entries.size

    /** 当前页面 = 栈顶。 */
    val current: Route get() = entries.last()

    /** 根路由，永远不会被 [pop]。 */
    val root: Route get() = entries.first()

    /** 还能不能返回（根路由之上还有页面）。false 时返回键应当交给系统决定。 */
    val canGoBack: Boolean get() = entries.size > 1

    fun contains(route: Route): Boolean = entries.contains(route)

    /**
     * 压入一个页面。
     *
     * 两种情况返回 `this`（无变化，调用方可用 `=== ` 判断）：
     * 1. 目标就是当前页——重复点击/重组重入不应该把同一个页面叠两层；
     * 2. 已到 [maxDepth] 上限——防止某个回调循环把栈顶到内存爆掉。
     */
    fun push(route: Route, maxDepth: Int = MAX_DEPTH): NavStack = when {
        route == current -> this
        entries.size >= maxDepth -> this
        else -> NavStack(entries + route)
    }

    /**
     * 弹出栈顶。
     *
     * @return 被弹出的页面 + 新栈；已在根路由时返回 null（调用方据此把返回键交给系统）。
     */
    fun pop(): NavPop? = if (!canGoBack) {
        null
    } else {
        NavPop(current, NavStack(entries.dropLast(1)))
    }

    /** 用 [route] 替换栈顶（保留栈深）。栈顶就等于它时返回 `this`。 */
    fun replace(route: Route): NavStack =
        if (route == current) this else NavStack(entries.dropLast(1) + route)

    /**
     * 一路弹到 [route] 成为栈顶。
     *
     * @return 新栈；[route] 不在栈里时返回 null（**不做**「找不到就当根」的兜底：
     *   那会把用户静默丢回书架，调用方应当明确处理）。
     */
    fun popTo(route: Route): NavStack? {
        val index = entries.indexOf(route)
        if (index < 0) return null
        val trimmed = entries.take(index + 1)
        return if (trimmed.size == entries.size) this else NavStack(trimmed)
    }

    /** 回到根路由。 */
    fun popToRoot(): NavStack = if (entries.size == 1) this else NavStack(listOf(root))

    companion object {
        /** 栈深上限。16 层远超实际层级（书架 → 阅读页 → …），只用来兜住回调环。 */
        const val MAX_DEPTH = 16

        /** 以 [root] 为根建栈。 */
        fun of(root: Route): NavStack = NavStack(listOf(root))
    }
}

/** [NavStack.pop] 的结果：弹掉了谁 + 弹完之后的栈。 */
data class NavPop(val route: Route, val stack: NavStack)
