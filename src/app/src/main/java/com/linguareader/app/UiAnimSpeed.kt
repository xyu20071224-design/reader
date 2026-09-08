package com.linguareader.app

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.MotionDurationScale

/**
 * 全局「交互动画速度」档位。
 *
 * ## 为什么不是换 Indication
 * 项目用的 Material3 1.3.2 里，`Button` / `IconButton` / 可点击 `Surface` 都直接调用
 * `rippleOrFallbackImplementation()`，**不读 `LocalIndication`**（2026-09-08 反汇编
 * `SurfaceKt$Surface$2` / `IconButtonKt` 确认）；而 `ripple()` 与 `RippleNode` 也没有时长参数。
 * 所以「provide 一个自定义 Indication」只能改到 foundation 的 `Modifier.clickable`，
 * 对真正的按钮无效。
 *
 * ## 实际生效的机制
 * Compose 的动画时长由协程上下文里的 [MotionDurationScale] 缩放：`animation-core` 每次
 * `animateTo` 都读 `coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f`（反汇编
 * `SuspendAnimationKt` 确认），而 modifier 节点的协程上下文来自
 * `AndroidComposeView.coroutineContext = recomposer.effectCoroutineContext`
 * （反汇编 `Wrapper_androidKt` 确认）。因此把自定义 [MotionDurationScale] 挂进窗口 `Recomposer`
 * 的协程上下文（见 `MainActivity.onCreate`），就能同时改到 M3 涟漪、弹层、开关等
 * 所有 Compose 动画——这正是「交互动画速度」该有的语义。
 *
 * @property scaleFactor 相对**系统动画时长**的倍率；0 = 立即完成（等价于「移除动画」）。
 */
enum class UiAnimSpeed(
    val scaleFactor: Float
) {
    /** 舒缓：动画时长翻倍，反馈更柔和。 */
    SLOW(2f),

    /** 标准：跟随系统（默认）。 */
    NORMAL(1f),

    /** 跟手：动画时长减半，反馈一闪即过。 */
    FAST(0.5f),

    /** 关闭：动画立即完成，按压后界面直接到位。 */
    OFF(0f)
}

private const val UI_ANIM_SPEED_PREFS = "reader_preferences"
private const val UI_ANIM_SPEED_KEY = "ui_anim_speed"

/** 读取已保存的交互动画速度；未设置时默认 [UiAnimSpeed.NORMAL]。 */
internal fun storedUiAnimSpeed(context: Context): UiAnimSpeed {
    val name = context.getSharedPreferences(UI_ANIM_SPEED_PREFS, Context.MODE_PRIVATE)
        .getString(UI_ANIM_SPEED_KEY, UiAnimSpeed.NORMAL.name)
        ?: UiAnimSpeed.NORMAL.name
    return runCatching { UiAnimSpeed.valueOf(name) }.getOrDefault(UiAnimSpeed.NORMAL)
}

/** 保存交互动画速度（异步落盘）。 */
internal fun saveUiAnimSpeed(context: Context, speed: UiAnimSpeed) {
    context.getSharedPreferences(UI_ANIM_SPEED_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(UI_ANIM_SPEED_KEY, speed.name)
        .apply()
}

/**
 * 读系统「动画时长缩放」（开发者选项 / 无障碍「移除动画」），读不到时按 1 处理。
 *
 * 不能省掉这一步：系统把动画关掉（0）是无障碍承诺，任何档位都不该把它强行打开。
 */
internal fun systemAnimDurationScale(context: Context): Float = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    )
}.getOrDefault(1f)

/**
 * 实际生效的动画缩放 = 系统缩放 × 档位倍率，且永不为负。
 *
 * 纯函数，单测覆盖（见 `UiAnimSpeedTest`）。
 */
internal fun effectiveAnimDurationScale(speed: UiAnimSpeed, systemScale: Float): Float =
    (systemScale * speed.scaleFactor).coerceAtLeast(0f)

/**
 * 进程级动画时长缩放。`WindowRecomposer` 会把它装进协程上下文，改 [scaleFactor] 对
 * **之后启动**的动画立即生效（已在跑的动画保持原时长，不会中途跳变）。
 */
internal class UiAnimDurationScale : MotionDurationScale {
    @Volatile
    override var scaleFactor: Float = 1f
}

/**
 * 全局单例。必须在任何 ComposeView 建树之前挂进窗口 `Recomposer`
 * （`MainActivity.onCreate` 里 `setContent` 之前），否则 Compose 会用它自带的
 * 只跟随系统设置的缩放实现。
 */
internal val appUiAnimDurationScale = UiAnimDurationScale()

/** 按档位 + 当前系统缩放刷新全局动画速度。 */
internal fun applyUiAnimSpeed(context: Context, speed: UiAnimSpeed) {
    appUiAnimDurationScale.scaleFactor =
        effectiveAnimDurationScale(speed, systemAnimDurationScale(context))
}
