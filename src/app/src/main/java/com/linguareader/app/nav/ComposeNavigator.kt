package com.linguareader.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.linguareader.shared.ui.nav.NavStack
import com.linguareader.shared.ui.nav.Navigator
import com.linguareader.shared.ui.nav.Route

/**
 * [Navigator]（`:shared`，纯 Kotlin，可单测）的 Compose 外壳。
 *
 * 为什么要有这一层：`:shared` 不许 import `androidx.compose.*`，所以导航状态本身
 * 只能是个普通可变对象；Compose 要重组，就需要一个 `mutableStateOf` 的镜像。
 * 这一层就是那面镜子——订阅 [Navigator] 的监听器，把栈镜像进 Compose 状态。
 *
 * 单测覆盖在 `:shared`（[com.linguareader.shared.ui.nav.NavigatorTest]）；
 * 这里只用 [androidx.compose.runtime.mutableStateOf] 做同步，逻辑一行都不多写。
 */
@Stable
class ComposeNavigator internal constructor(
    /** 下层纯逻辑导航器；测试与适配层需要拿到它。 */
    val navigator: Navigator,
    initial: NavStack
) {

    /** 当前导航栈（Compose 状态，变化会触发重组）。 */
    var stack: NavStack by mutableStateOf(initial)
        private set

    val current: Route get() = stack.current
    val canGoBack: Boolean get() = stack.canGoBack

    private val listener: (NavStack) -> Unit = { stack = it }

    private var attached = false

    /** 开始跟随 [navigator]。幂等：重复 attach 不会让监听器叠加。 */
    fun attach() {
        if (attached) return
        attached = true
        navigator.addListener(listener)
        // 挂上之前如果有变化（例如构造后直接 push 过），先同步一次。
        stack = navigator.stack
    }

    fun detach() {
        if (!attached) return
        attached = false
        navigator.removeListener(listener)
    }

    companion object {
        /**
         * 建一个已挂上监听器的导航器。
         *
         * [rememberNavigator] 由 `DisposableEffect` 负责 attach/detach；**非组合场景**
         * （单测、桌面壳）用它，否则镜像状态不会跟着 [Navigator] 变——
         * 那是这套设计里最容易踩空的一处，所以给一个不会踩的入口。
         */
        fun of(navigator: Navigator = Navigator()): ComposeNavigator =
            ComposeNavigator(navigator, navigator.stack).also { it.attach() }
    }

    /** 跳到 [route]（压栈）。重复跳当前页无效果。 */
    fun navigate(route: Route) {
        navigator.push(route)
    }

    /** 关闭当前页，返回被关掉的那一页；已在根页时返回 null。 */
    fun goBack(): Route? = navigator.pop()

    /** 原地换页（保留栈深）。 */
    fun replaceTop(route: Route) {
        navigator.replace(route)
    }

    /** 一路退到 [route] 成为当前页；目标不在栈里时什么都不做。 */
    fun popTo(route: Route): Boolean = navigator.popTo(route)

    /** 回到根页。 */
    fun popToRoot() {
        navigator.popToRoot()
    }

    /** 重置为以 [root] 为根的单层栈。 */
    fun reset(root: Route) {
        navigator.reset(root)
    }
}

/**
 * 当前导航器。屏幕里用 `LocalNavigator.current.navigate(Route.Vocabulary)` 跳转，
 * 不再像现在这样把 `onOpen`/`onClose`/`onShowXxx` 一个个往下传
 * （`MainActivity.kt:156-213` 那 54 个参数的根源之一）。
 *
 * 用 `staticCompositionLocalOf`：导航器引用本身稳定，变化走 [ComposeNavigator.stack]
 * 的 `mutableStateOf`，不需要 Local 变化触发整棵子树重组。
 */
val LocalNavigator = staticCompositionLocalOf<ComposeNavigator> {
    error("LocalNavigator 未提供：在根部用 rememberNavigator() + CompositionLocalProvider 包一层")
}

/**
 * 建（并记住）一个导航器，随组合生命周期绑定监听器。
 *
 * @param initial 根路由。
 */
@Composable
fun rememberNavigator(initial: Route = Route.Bookshelf): ComposeNavigator {
    val holder = remember { Navigator(initial) }
    val composeNavigator = remember(holder) { ComposeNavigator(holder, holder.stack) }
    DisposableEffect(composeNavigator) {
        composeNavigator.attach()
        onDispose { composeNavigator.detach() }
    }
    return composeNavigator
}
