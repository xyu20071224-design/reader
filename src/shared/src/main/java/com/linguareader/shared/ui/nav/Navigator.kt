package com.linguareader.shared.ui.nav

/**
 * 导航状态持有者：把 [NavStack] 的纯迁移包一层可变外壳 + 监听器。
 *
 * 为什么拆两层（`NavStack` 纯函数 / `Navigator` 可变外壳）：
 * 纯函数层可以穷举单测（栈深上限、pop 到根、popTo 找不到目标…），
 * 可变外壳只负责「改完通知谁」。Compose 侧（`:app/nav`）订阅监听器把状态
 * 镜像进 `mutableStateOf`，于是共享层不需要认识 Compose。
 *
 * 线程约定：只在主线程用（UI 导航本来如此），监听器在调用线程同步回调。
 */
class Navigator(
    root: Route = Route.Bookshelf,
    private val maxDepth: Int = NavStack.MAX_DEPTH
) {

    var stack: NavStack = NavStack.of(root)
        private set

    val current: Route get() = stack.current
    val canGoBack: Boolean get() = stack.canGoBack

    /**
     * 压入页面。
     *
     * @return 栈是否真的变了（重复压当前页 / 触顶时 false）。
     */
    fun push(route: Route): Boolean = update(stack.push(route, maxDepth))

    /**
     * 弹出栈顶。
     *
     * @return 被弹出的页面；已在根路由时 null。
     */
    fun pop(): Route? {
        val popped = stack.pop() ?: return null
        update(popped.stack)
        return popped.route
    }

    /** 替换栈顶。 */
    fun replace(route: Route) = update(stack.replace(route))

    /** 弹到指定页面成为栈顶；目标不在栈里时不做任何事并返回 false。 */
    fun popTo(route: Route): Boolean {
        val next = stack.popTo(route) ?: return false
        return update(next)
    }

    /** 回到根路由。 */
    fun popToRoot() = update(stack.popToRoot())

    /** 重置为以 [root] 为根的单层栈（换账号/退出阅读等场景）。 */
    fun reset(root: Route) {
        val next = NavStack.of(root)
        if (next != stack) {
            stack = next
            notifyChanged()
        }
    }

    /** 直接套用一个已经算好的栈（返回键统一处理用：`BackAction.next`）。 */
    fun setStack(next: NavStack) = update(next)

    private fun update(next: NavStack): Boolean {
        if (next == stack) return false
        // 同一实例/同内容都算无变化：避免无谓重组。
        stack = next
        notifyChanged()
        return true
    }

    private fun notifyChanged() {
        val snapshot = listeners.toList()
        for (listener in snapshot) listener(stack)
    }

    private val listeners = mutableListOf<(NavStack) -> Unit>()

    fun addListener(listener: (NavStack) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (NavStack) -> Unit) {
        listeners.remove(listener)
    }
}
