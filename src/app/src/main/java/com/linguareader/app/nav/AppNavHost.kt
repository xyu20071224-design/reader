package com.linguareader.app.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

/**
 * 应用导航壳：屏幕 + 弹层 + 返回键，三件事在这里合成一条链。
 *
 * 这是 P3 各屏迁移时的**唯一**接入点：
 * ```
 * CompositionLocalProvider(LocalNavigator provides nav.navigator) {
 *     AppNavHost(screens = ..., overlays = ..., nav = ...) { ... }
 * }
 * ```
 * 屏幕内部不再自己写 `BackHandler`、不再自己维护 `showXxx` 布尔。
 *
 * 返回键优先级（与 [AppNavState.onBack] 一致）：弹层栈顶 → 导航栈上一页 → 系统。
 * 三者都没有时**不注册** BackHandler，让系统拿到返回键——否则返回键会在这个壳里失灵。
 */
@Composable
fun AppNavHost(
    screens: ScreenRegistry,
    overlays: OverlayRegistry,
    nav: AppNavState,
    modifier: Modifier = Modifier
) {
    val handled = nav.canGoBack
    BackHandler(enabled = handled) { nav.onBack() }

    CompositionLocalProvider(LocalNavigator provides nav.navigator) {
        Box(modifier.fillMaxSize()) {
            NavHostLite(registry = screens, host = nav.navigator)
            OverlayHost(
                stack = nav.overlays,
                registry = overlays,
                onPush = { nav.pushOverlay(it) },
                onReplace = { nav.replaceOverlay(it) },
                onDismissTop = { nav.dismissTop() }
            )
        }
    }
}
