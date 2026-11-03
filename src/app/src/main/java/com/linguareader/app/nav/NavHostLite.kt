package com.linguareader.app.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.linguareader.app.InkSoft
import com.linguareader.app.Paper
import com.linguareader.app.R
import com.linguareader.shared.ui.nav.Route

/**
 * 屏幕键：Route → 屏幕的一一对应。
 *
 * 用枚举而不是 `KClass`：加一个 Route 时 [RouteKey.key] 的 `when` 会编译失败，
 * 强制作者表态「这个新页面由哪个屏幕画」。这是这套导航模型唯一想要的强制力。
 */
enum class RouteKey {
    BOOKSHELF,
    VOCABULARY,
    SYNC_DETAIL,
    READER
}

/** logcat tag：导航层的诊断都打在这一路，方便 `adb logcat -s LinguaNav`。 */
const val TAG = "LinguaNav"

/** 路由对应的屏幕键。`when` 必须穷举：加 Route 时这里会红，是设计的一部分。 */
val Route.key: RouteKey
    get() = when (this) {
        Route.Bookshelf -> RouteKey.BOOKSHELF
        Route.Vocabulary -> RouteKey.VOCABULARY
        Route.SyncDetail -> RouteKey.SYNC_DETAIL
        is Route.Reader -> RouteKey.READER
    }

/**
 * 屏幕注册表：路由键 → 该屏的 Composable。
 *
 * 迁移期（P2）大多数屏幕还没搬过来，所以这里允许缺项——
 * [missing] 会明确指出缺哪个，[NavHostLite] 对缺项渲染一块诊断文案而不是崩。
 * P3 逐屏填充，填满后 `missing()` 为空即可考虑去掉兜底。
 */
class ScreenRegistry internal constructor(
    private val screens: Map<RouteKey, @Composable (Route) -> Unit>
) {

    operator fun get(key: RouteKey): (@Composable (Route) -> Unit)? = screens[key]

    /** 还没注册的屏幕键。 */
    fun missing(): List<RouteKey> = RouteKey.entries.filter { it !in screens }

    /** 是否所有屏幕都注册了。 */
    fun isComplete(): Boolean = missing().isEmpty()

    /** 已注册的屏幕键。 */
    fun registered(): Set<RouteKey> = screens.keys

    companion object {
        /** 一个屏幕都没注册（用于测试与迁移期兜底）。 */
        val EMPTY = ScreenRegistry(emptyMap())
    }
}

/**
 * [ScreenRegistry] 的构建器。
 *
 * 为什么不是 `of(key to { ... })`：`to` 里那个 lambda 拿不到期望类型，
 * 会被推断成普通 `Function1` 而不是 `@Composable (Route) -> Unit`（实测编译不过）。
 * DSL 把 lambda 直接放进参数位置，类型由编译器给出。
 */
class ScreenRegistryBuilder internal constructor() {

    private val screens = LinkedHashMap<RouteKey, @Composable (Route) -> Unit>()

    /** 注册一屏。重复注册同一键时后者生效（便于测试覆盖）。 */
    fun screen(key: RouteKey, content: @Composable (Route) -> Unit) {
        screens[key] = content
    }

    internal fun build(): ScreenRegistry = ScreenRegistry(screens.toMap())
}

fun screenRegistry(block: ScreenRegistryBuilder.() -> Unit): ScreenRegistry =
    ScreenRegistryBuilder().apply(block).build()

/**
 * NavHost-lite：按当前路由渲染唯一一个屏幕。**不引入** `navigation-compose`。
 *
 * 与官方 NavHost 的差别（也是刻意的差别）：
 * - 没有转场动画、没有回退栈的自动保存/恢复——这两件事本项目现在也不需要，
 *   P2 先要的是「一个地方决定当前是哪屏」；
 * - 路由是 [Route] 密封类型而不是字符串，参数由编译器检查；
 * - 状态由 [ComposeNavigator] 持有（可单测），而不是内部的 NavController。
 *
 * 返回键**不在这里**处理：统一由 [AppBackDispatcher] / [OverlayHost] 决定，
 * 避免出现「导航页和弹层各抢一次返回键」。
 */
@Composable
fun NavHostLite(
    registry: ScreenRegistry,
    modifier: Modifier = Modifier,
    host: ComposeNavigator = LocalNavigator.current
) {
    val route = host.current
    val content = registry[route.key]
    Box(modifier.fillMaxSize()) {
        if (content != null) content(route) else MissingScreen(route)
    }
}

/**
 * 未注册屏幕的诊断占位（不是崩溃）：P2 阶段迁移中的正常态。
 *
 * 除了画占位，还会把缺失的路由写进 logcat（tag=[TAG]）——真机上如果没有这行日志，
 * 「点了一下没反应」会变成无从下手的问题：界面只看到一块占位，不知道是哪个路由没注册。
 */
@Composable
fun MissingScreen(route: Route, modifier: Modifier = Modifier) {
    LaunchedEffect(route) {
        Log.w(TAG, "未注册的屏幕：" + route.javaClass.simpleName + " (" + route.key.name + ") → " + route)
    }
    Surface(modifier = modifier.fillMaxSize(), color = Paper) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.nav_placeholder_title),
                style = MaterialTheme.typography.titleMedium,
                color = InkSoft
            )
            Text(
                stringResource(
                    R.string.nav_placeholder_screen,
                    route.javaClass.simpleName,
                    route.key.name
                ),
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                stringResource(R.string.nav_placeholder_hint),
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
