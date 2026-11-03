package com.linguareader.app.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.linguareader.shared.ui.state.OverlayBackResult
import com.linguareader.shared.ui.state.OverlayStack

/**
 * 返回键的唯一分发点。
 *
 * 现状：全项目只有 3 处 `BackHandler`（`ReaderScreen.kt:459`、`BookshelfScreen.kt:235`
 * 及阅读页里的那一处），书架的 6 个 sheet + 7 个对话框没有返回处理——用户按返回，
 * 弹层纹丝不动、直接退出应用。原因是每个弹层自己是独立布尔，谁也不知道「上面还有没有更浅的一层」。
 *
 * 改成**一条链**之后，优先级由数据结构决定，不由调用点决定：
 *
 * 1. 弹层栈非空 → 关栈顶（[OverlayStack.handleBack]）；
 * 2. 导航栈还有上一页 → 弹回上一页；
 * 3. 都没有 → 不拦截，交回系统（Activity 退出）。
 *
 * 第 3 条很关键：这里**不**返回「已处理」。`BackHandler` 的 enabled 只在 1、2
 * 成立时为 true，否则完全不注册——让系统拿到返回键。这是空栈行为唯一正确的写法。
 */
@Composable
fun AppBackDispatcher(
    overlays: OverlayStack,
    navigator: ComposeNavigator,
    /** 有更高优先级的拦截者（例如阅读页自己的 `ReaderOverlays`）时置 false。 */
    enabled: Boolean = true,
    onOverlayBack: (OverlayBackResult.Dismiss) -> Unit = {}
) {
    val currentOverlays by rememberUpdatedState(overlays)
    val currentNavigator by rememberUpdatedState(navigator)
    val currentOnOverlayBack by rememberUpdatedState(onOverlayBack)

    val handled = enabled && (!currentOverlays.isEmpty || currentNavigator.canGoBack)
    BackHandler(enabled = handled) {
        val overlayBack = currentOverlays.handleBack()
        if (overlayBack is OverlayBackResult.Dismiss) {
            currentOnOverlayBack(overlayBack)
        } else {
            // 没有弹层可关：退导航栈。canGoBack 为 false 时外层根本不会注册这个
            // BackHandler，所以这里必然能弹出一页。
            currentNavigator.goBack()
        }
    }
}
