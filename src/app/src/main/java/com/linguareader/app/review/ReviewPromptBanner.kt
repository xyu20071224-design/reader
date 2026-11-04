package com.linguareader.app.review

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.linguareader.app.R
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.skin.LocalDesignTokens
import kotlinx.coroutines.delay

/**
 * 本章横幅自己的几何常量。
 *
 * 判据与 ReviewUi 时期一致：间距梯只有 4/8/16/24/32/48，横幅的 18dp 左右内边距
 * 与 3dp 抬起阴影都没有对应档位，硬套会改变像素，而 P3 的验收前提是视觉等价。
 * 这三个值随横幅一起搬出，不留在 ReviewUi.kt（那里已经没有任何引用）。
 */
private const val BannerAlpha = .97f
private val BannerShadow = 3.dp
private val BannerHorizontalPadding = 18.dp

/**
 * 章末 / 离开阅读页时出现的**非模态**提醒条：「有 N 个词可快速复习」。
 *
 * ## 职责
 *
 * 只负责画一条横幅，并在 [dwellMillis] 之后自行消失。它不决定「什么时候该出现」——
 * 那个判断（到期词数量、是否开启 pausePrompt、用户是否在接管窗口内）在调用方
 * （`ReaderScreen`）。所以它没有状态、不需要任何上游数据源，可以独立存在。
 *
 * ## 为什么不是 sheet
 *
 * 它是**非模态**的：不遮正文、不抢焦点，用户可以直接忽略继续读。因此保留 `Surface`
 * 而不是换成 [com.linguareader.app.components.LinguaSheet]——后者是模态弹层。
 */
/** Non-modal pause prompt shown at chapter boundaries / before leaving the reader. */
@Composable
internal fun ReviewPromptBanner(
    count: Int,
    dwellMillis: Long,
    onStart: () -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalDesignTokens.current
    LaunchedEffect(dwellMillis) {
        if (dwellMillis > 0) {
            delay(dwellMillis)
            onDismiss()
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        // 半透明纸底 + 抬起阴影：这是一条**浮在正文之上**的非模态提示，
        // 需要与身后滚动的内容拉开一层，所以保留阴影而不是纸感的描边分层。
        color = t.colors.paperDeep.copy(alpha = BannerAlpha),
        shadowElevation = BannerShadow
    ) {
        Row(
            modifier = Modifier.padding(horizontal = BannerHorizontalPadding, vertical = t.spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = pluralStringResource(R.plurals.review_banner_due, count, count),
                modifier = Modifier.weight(1f),
                style = t.typography.body,
                color = t.colors.ink
            )
            LinguaTextButton(text = stringResource(R.string.review_banner_start), onClick = onStart)
            LinguaTextButton(text = stringResource(R.string.review_banner_dismiss), onClick = onDismiss)
        }
    }
}
