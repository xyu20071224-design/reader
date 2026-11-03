package com.linguareader.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.linguareader.shared.ui.state.Overlay
import com.linguareader.shared.ui.state.OverlayBackResult
import com.linguareader.shared.ui.state.OverlayStack

/**
 * 应用级导航 + 弹层的组合状态：导航器 + 弹层栈。
 *
 * 为什么弹层栈不放进 [ComposeNavigator]：两者生命周期不同。导航栈跨整本书/整屏，
 * 弹层栈是「当前屏上临时盖的一层」，离开这一屏就该清空。合并成一个对象会让
 * 「换屏时把弹层也带走」变成默认行为。两者在 [AppNavHost] 里组合。
 *
 * 状态本身是纯 Kotlin 的 [OverlayStack]（可单测），这里只做 Compose 镜像。
 */
class AppNavState(
    val navigator: ComposeNavigator,
    initialOverlays: OverlayStack = OverlayStack.EMPTY
) {

    var overlays: OverlayStack by mutableStateOf(initialOverlays)
        private set

    /** 当前显示的弹层（栈顶）；null = 没有。 */
    val top: Overlay? get() = overlays.top

    fun pushOverlay(overlay: Overlay) {
        overlays = overlays.push(overlay)
    }

    fun replaceOverlay(overlay: Overlay) {
        overlays = overlays.replace(overlay)
    }

    /** 关掉栈顶；栈空时返回 null（调用方据此决定是否往下传）。 */
    fun dismissTop(): Overlay? {
        val popped = overlays.pop() ?: return null
        overlays = popped.stack
        return popped.overlay
    }

    fun dismissOverlay(kind: com.linguareader.shared.ui.state.OverlayKind) {
        overlays = overlays.dismiss(kind)
    }

    fun dismissAllOverlays() {
        overlays = overlays.dismissAll()
    }

    /** 返回键：先关弹层，弹层空了再退导航栈。两步都在这里，调用点只管调它。 */
    fun onBack(): BackOutcome {
        when (val result = overlays.handleBack()) {
            is OverlayBackResult.Dismiss -> {
                overlays = result.stack
                return BackOutcome.DismissedOverlay(result.dismissed)
            }
            OverlayBackResult.PassThrough -> {
                val popped = navigator.goBack()
                return if (popped != null) BackOutcome.PoppedRoute(popped) else BackOutcome.Unhandled
            }
        }
    }

    /** 还有没有可处理的返回（弹层或上一层页面）。false 时返回键交给系统。 */
    val canGoBack: Boolean get() = !overlays.isEmpty || navigator.canGoBack
}

/** [AppNavState.onBack] 的结果：调试点与测试都靠它表达「刚才发生了哪一种返回」。 */
sealed interface BackOutcome {

    /** 关掉了一个弹层。 */
    data class DismissedOverlay(val overlay: Overlay) : BackOutcome

    /** 退回了上一页。 */
    data class PoppedRoute(val route: com.linguareader.shared.ui.nav.Route) : BackOutcome

    /** 什么都没了：返回键应当交给系统（Activity 退出）。 */
    data object Unhandled : BackOutcome
}

/** 组合内建一个 [AppNavState]（导航器与弹层栈一起记住）。 */
@Composable
fun rememberAppNavState(
    navigator: ComposeNavigator = rememberNavigator()
): AppNavState {
    val state = remember(navigator) { AppNavState(navigator) }
    return state
}
