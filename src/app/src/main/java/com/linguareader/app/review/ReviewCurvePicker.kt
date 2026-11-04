package com.linguareader.app.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.linguareader.app.R
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.skin.LocalDesignTokens
import kotlin.math.pow

/**
 * 自定义节奏编辑器里的**遗忘曲线**：横轴是「复习后经过的时间比例」，
 * 纵轴是「记忆留存率」，在曲线上点一下 / 拖一下即可选一个留存率档位。
 *
 * ## 职责
 *
 * 只做两件事：按 [ReviewCurve] 的公式把曲线画出来，把点击/拖动位置翻译成一个
 * 可选的留存率（[onRetentionChange]）。**它不存状态**：当前选中值由调用方
 * `CustomReviewEditor` 的 `draft` 持有并通过 [retention] 传进来。
 *
 * ## 为什么可以独立
 *
 * 输入一个 Double、输出一个 Double 回调，外加一个 Canvas 的尺寸约束。
 * 不依赖卡组、不依赖设置、不碰 Activity，所以能单独抽出来并单独渲染。
 *
 * ## ⚠️ 这组几何是**语义**，不是样式
 *
 * [ReviewCurve.DECAY_SPAN] / [ReviewCurve.CURVE_EXPONENT]、坐标轴内缩
 * [CurveAxisInsetStart] 等、笔画宽度、手柄半径 —— 这些比例共同决定「曲线长什么样、
 * 哪个位置对应哪个留存率」。**为了套 token 而改动任何一档，都会让这张图说假话。**
 * 因此它们全部保留原值，只是从裸 dp 改成了命名常量。
 *
 * ## 这一块是全批**唯一**非纯搬移的地方
 *
 * 配色从旧调色板（`Ink` / `Accent` / `Paper`）改成了 [LocalDesignTokens]：
 * 拆到独立文件后反向 import 旧调色板会让「设计系统统一」破功。
 * 日间皮肤下两者逐值相等（`SkinBuiltIns.light` 就是从 `ThemeColors` 逐字抄的），
 * 夜间/AMOLED 下则会正确地跟着皮肤走 —— 这正是改动的目的。
 */

// ── 曲线几何（原值，一个数都没改）──────────────────────────────

/** 曲线画布高度。 */
private val CurveCanvasHeight = 180.dp

/** 绘图区左内缩（给纵轴端点留白）。 */
private val CurveAxisInsetStart = 8.dp

/** 绘图区上内缩。 */
private val CurveAxisInsetTop = 10.dp

/** 绘图区右内缩。 */
private val CurveAxisInsetEnd = 8.dp

/** 绘图区下内缩（为底部刻度留白）。 */
private val CurveAxisInsetBottom = 16.dp

/** 三条参考留存率网格线的粗细。 */
private val CurveGridStroke = 1.dp

/** 曲线本体笔画宽度。 */
private val CurveLineStroke = 2.dp

/** 内置三档节奏在曲线上的参考点半径。 */
private val CurvePresetDotRadius = 3.dp

/** 可拖动圆点外圈半径。 */
private val CurveHandleOuterRadius = 9.dp

/** 可拖动圆点内圈（纸色填充）半径。 */
private val CurveHandleInnerRadius = 4.dp

/** 网格线透明度。 */
private const val CurveGridAlpha = .10f

/** 参考点透明度。 */
private const val CurveMarkerAlpha = .45f

@Composable
internal fun ReviewCurvePicker(
    retention: Double,
    onRetentionChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalDesignTokens.current
    val currentOnChange by rememberUpdatedState(onRetentionChange)
    // Canvas 的绘制作用域不是 @Composable，配色与几何要先在这里取出来。
    //
    // 配色走设计 token（而不是旧调色板的 Ink/Accent/Paper）：拆到独立文件后，
    // 反向 import 旧调色板会让「设计系统统一」破功——旧调色板正在被逐步淘汰。
    // 已验证日间皮肤下 t.colors.* 与旧调色板逐值相等（SkinBuiltIns.light 是从
    // ThemeColors 逐字抄来的），故视觉等价；夜间/AMOLED 下则会正确地跟着皮肤走，
    // 这正是这次改动的目的。
    val gridColor = t.colors.ink.copy(alpha = CurveGridAlpha)
    val curveColor = t.colors.accent
    val markerColor = t.colors.ink.copy(alpha = CurveMarkerAlpha)
    val handleFill = t.colors.paper
    val curveDescription = stringResource(R.string.review_curve_desc)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(CurveCanvasHeight)
            .semantics { contentDescription = curveDescription }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val x = (offset.x / size.width).coerceIn(0f, 1f)
                    currentOnChange(ReviewCurve.selectableRetentionForFraction(x.toDouble()))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val x = (change.position.x / size.width).coerceIn(0f, 1f)
                    currentOnChange(ReviewCurve.selectableRetentionForFraction(x.toDouble()))
                }
            }
    ) {
        val left = CurveAxisInsetStart.toPx()
        val top = CurveAxisInsetTop.toPx()
        val right = CurveAxisInsetEnd.toPx()
        val bottom = CurveAxisInsetBottom.toPx()
        val chartWidth = size.width - left - right
        val chartHeight = size.height - top - bottom

        fun yFor(r: Double): Float = (top + chartHeight * (1.0 - r)).toFloat()
        fun xFor(r: Double): Float = (left + chartWidth * ReviewCurve.fractionForRetention(r)).toFloat()

        listOf(1.0, 0.85, 0.60).forEach { r ->
            drawLine(
                color = gridColor,
                start = Offset(left, yFor(r)),
                end = Offset(size.width - right, yFor(r)),
                strokeWidth = CurveGridStroke.toPx()
            )
        }

        val path = Path()
        var step = 0
        while (step <= 100) {
            val x = step / 100f
            val r = ReviewCurve.retentionForFraction(x.toDouble())
            val px = left + chartWidth * x
            val py = yFor(r)
            if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
            step += 1
        }
        drawPath(path, color = curveColor, style = Stroke(width = CurveLineStroke.toPx()))

        ReviewMode.entries.forEach { mode ->
            val r = ReviewPace.retentionForMultiplier(mode.intervalMultiplier)
            drawCircle(
                color = markerColor,
                radius = CurvePresetDotRadius.toPx(),
                center = Offset(xFor(r), yFor(r))
            )
        }

        val handleRetention = retention.coerceIn(ReviewPace.MIN_RETENTION, ReviewPace.MAX_RETENTION)
        val handle = Offset(xFor(handleRetention), yFor(handleRetention))
        drawCircle(color = curveColor, radius = CurveHandleOuterRadius.toPx(), center = handle)
        drawCircle(color = handleFill, radius = CurveHandleInnerRadius.toPx(), center = handle)
    }
}

private object ReviewCurve {
    const val DECAY_SPAN = 0.55
    const val CURVE_EXPONENT = 1.02

    fun retentionForFraction(x: Double): Double {
        val clamped = x.coerceIn(0.0, 1.0)
        return 1.0 - DECAY_SPAN * clamped.pow(CURVE_EXPONENT)
    }

    fun selectableRetentionForFraction(x: Double): Double =
        retentionForFraction(x).coerceIn(ReviewPace.MIN_RETENTION, ReviewPace.MAX_RETENTION)

    fun fractionForRetention(retention: Double): Double {
        val r = retention.coerceIn(ReviewPace.MIN_RETENTION, ReviewPace.MAX_RETENTION)
        return (((1.0 - r) / DECAY_SPAN).coerceIn(0.0, 1.0)).pow(1.0 / CURVE_EXPONENT)
    }
}
