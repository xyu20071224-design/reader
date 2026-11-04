package com.linguareader.app.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.linguareader.app.R
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewScheduler
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 自定义节奏编辑器里那块「按此节奏：首次 30 分钟 / 第 2 次 …」的预测预览。
 *
 * ## 职责
 *
 * 把一份 [ReviewPace] 翻译成四行人类可读的时间预测。**它只做翻译，不做计算**：
 * 第 n 次的间隔由 [ReviewScheduler.intervalFor] 给出（那是复习算法，本文件一行不动），
 * 这里只负责把毫秒格式化成「约 N 分钟 / 小时 / 天」。
 *
 * ## 为什么可以独立
 *
 * 纯函数式：输入一份 pace、输出文字，没有状态、没有回调、不碰 Activity。
 * 唯一的上游是 [ReviewScheduler] 与字符串资源，两者都不依赖 ReviewUi.kt。
 * 抽出来之后，未来任何「展示某节奏会怎样」的地方（设置页、帮助页）都能直接复用。
 *
 * ⚠️ 四行预测值必须与 [ReviewScheduler] 的真实调度一致 —— 这是产品对用户的承诺，
 * 所以 [formatApproxDuration] 的取整口径（向下取整到分/时/天）保持原样。
 */

@Composable
internal fun PacePreview(pace: ReviewPace) {
    val t = LocalDesignTokens.current
    Column {
        Text(
            text = stringResource(R.string.review_pace_preview_title),
            style = t.typography.label,
            color = t.colors.inkSoft
        )
        Spacer(Modifier.height(t.spacing.xs))
        // 这几行是「按此节奏」的预测值，数字本身来自 ReviewScheduler，一个字没改。
        Text(stringResource(R.string.review_pace_preview_first, firstDelayLabel(pace.firstDelayMillis)), style = t.typography.caption, color = t.colors.ink)
        Text(stringResource(R.string.review_pace_preview_nth, 2, formatApproxDuration(ReviewScheduler.intervalFor(1, pace))), style = t.typography.caption, color = t.colors.ink)
        Text(stringResource(R.string.review_pace_preview_nth, 3, formatApproxDuration(ReviewScheduler.intervalFor(2, pace))), style = t.typography.caption, color = t.colors.ink)
        Text(stringResource(R.string.review_pace_preview_nth, 4, formatApproxDuration(ReviewScheduler.intervalFor(3, pace))), style = t.typography.caption, color = t.colors.ink)
    }
}

@Composable
internal fun firstDelayOptions(): List<Pair<Long, String>> = listOf(
    5 * 60_000L to stringResource(R.string.review_delay_5m),
    30 * 60_000L to stringResource(R.string.review_delay_30m),
    2 * 3_600_000L to stringResource(R.string.review_delay_2h),
    24 * 3_600_000L to stringResource(R.string.review_delay_next_day)
)

@Composable
internal fun firstDelayLabel(millis: Long): String =
    firstDelayOptions().firstOrNull { it.first == millis }?.second
        ?: formatApproxDuration(millis)

@Composable
internal fun formatApproxDuration(millis: Long): String {
    val minutes = millis / 60_000L
    return when {
        minutes < 60 -> stringResource(R.string.review_in_minutes_approx, minutes)
        minutes < 24 * 60 -> stringResource(R.string.review_in_hours_approx, minutes / 60)
        else -> stringResource(R.string.review_in_days_approx, minutes / (24 * 60))
    }
}
