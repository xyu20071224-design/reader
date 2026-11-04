package com.linguareader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.PrimaryButtonFullWidth
import com.linguareader.app.data.Greeting
import com.linguareader.app.data.GreetingPeriod
import com.linguareader.app.data.UpdateNote
import com.linguareader.app.skin.LocalDesignTokens

/** Launch greeting scene card, or one-time update note after an app update (F-144). */
@Composable
internal fun LaunchPromptDialog(
    prompt: LaunchPromptUi,
    onDismiss: () -> Unit
) {
    when (prompt) {
        is LaunchPromptUi.GreetingPrompt -> GreetingSceneDialog(prompt.greeting, onDismiss)
        is LaunchPromptUi.UpdatePrompt -> UpdateNoteDialog(prompt.note, onDismiss)
    }
}

/**
 * 问候场景卡（F-144）。
 *
 * P3 补迁：样式改由 [LocalDesignTokens] 提供。**是 `Dialog`，画不进渲染台的位图**
 * （走独立 Window），所以这一屏只有编译验证与代码审阅，**没有像素基线**——
 * 真机验收时请单独看一眼深色主题下的观感。
 *
 * 保留的排版特例：标题 26sp / 正文 15sp·行高 26sp。它们是这张「场景卡」的版式
 * 比例（标题需要大于 `display` 的 28sp 太多会溢出卡片宽度），间距梯里没有字号档位，
 * 故沿用原值并在这里写明理由。
 */
@Composable
private fun GreetingSceneDialog(greeting: Greeting, onDismiss: () -> Unit) {
    val t = LocalDesignTokens.current
    val accent = greeting.period.accent()
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = t.largeShape,
            color = t.colors.cardSurface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = t.spacing.l, vertical = t.spacing.s),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(accent.copy(alpha = GreetingSceneWashAlpha), Color.Transparent)
                            )
                        )
                        .padding(vertical = t.spacing.l),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(GreetingBadgeSize)
                                .background(accent.copy(alpha = GreetingBadgeAlpha), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(greeting.period.iconRes()),
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(GreetingIconSize)
                            )
                        }
                        Spacer(Modifier.height(t.spacing.m))
                        Text(
                            text = stringResource(greeting.titleRes),
                            // 版式特例：26sp 场景标题（见函数注释）。
                            fontFamily = FontFamily.Serif,
                            fontSize = GreetingTitleSize,
                            fontWeight = FontWeight.Medium,
                            color = t.colors.ink
                        )
                        Spacer(Modifier.height(GreetingTinyGap))
                        Text(
                            text = stringResource(greeting.period.hoursLabelRes),
                            style = t.typography.label,
                            color = t.colors.inkSoft
                        )
                    }
                }
                Text(
                    text = stringResource(greeting.messageRes),
                    // 版式特例：15sp / 26sp 行高（见函数注释）。
                    fontFamily = FontFamily.Serif,
                    fontSize = GreetingBodySize,
                    lineHeight = GreetingBodyLineHeight,
                    textAlign = TextAlign.Center,
                    color = t.colors.ink,
                    modifier = Modifier.padding(top = t.spacing.xs, bottom = t.spacing.l)
                )
                PrimaryButtonFullWidth(
                    text = stringResource(R.string.launch_greeting_start),
                    onClick = onDismiss
                )
                Spacer(Modifier.height(t.spacing.s))
            }
        }
    }
}

@Composable
private fun UpdateNoteDialog(note: UpdateNote, onDismiss: () -> Unit) {
    val t = LocalDesignTokens.current
    // joinToString 的 lambda 不是组合作用域，用 context 取文案而不是 stringResource。
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            LinguaTextButton(
                text = stringResource(R.string.common_got_it),
                onClick = onDismiss
            )
        },
        title = {
            Text(
                text = stringResource(R.string.launch_update_note_title, note.versionName),
                style = t.typography.title,
                color = t.colors.ink
            )
        },
        text = {
            Text(
                text = note.items.joinToString("\n") { "· ${'$'}{context.getString(it)}" },
                style = t.typography.body.copy(lineHeight = t.typography.display.lineHeight),
                color = t.colors.ink,
                modifier = Modifier.padding(top = t.spacing.xs)
            )
        },
        containerColor = t.colors.cardSurface,
        shape = t.cardShape
    )
}

/** Per-period accent used by the greeting scene card. */
private fun GreetingPeriod.accent(): Color = when (this) {
    GreetingPeriod.DAWN -> Color(0xFFC97A45)   // 朝阳
    GreetingPeriod.NOON -> Color(0xFFA87E22)   // 烈日
    GreetingPeriod.DUSK -> Color(0xFF9A5D42)   // 晚霞
    GreetingPeriod.NIGHT -> Color(0xFF43506C)  // 星河
}

/** Per-period scene icon. */
private fun GreetingPeriod.iconRes(): Int = when (this) {
    GreetingPeriod.DAWN -> R.drawable.ic_greeting_dawn
    GreetingPeriod.NOON -> R.drawable.ic_greeting_noon
    GreetingPeriod.DUSK -> R.drawable.ic_greeting_dusk
    GreetingPeriod.NIGHT -> R.drawable.ic_greeting_night
}

// ── 场景卡专有尺寸与透明度 ────────────────────────────────────────
//
// 这几个是「这张问候卡自己的版式比例」，不是全局间距：
// - GreetingTitleSize 26sp 比排版梯最大档 display(28sp) 略小，但 greeting 标题是单行
//   短词（「清晨」「正午」），用 28sp 会在窄卡里换行；
// - GreetingBodySize 15sp / 行高 26sp 是「引言」的版式（比 body 的 15/22 行距更松）；
// - 两个 alpha 是渐变/底盘浓度，属于配色而非间距。
// 硬套 token 会改变这张卡的比例，故按原值保留并命名。
private val GreetingTitleSize = 26.sp
private val GreetingBodySize = 15.sp
private val GreetingBodyLineHeight = 26.sp
private val GreetingTinyGap = 2.dp
private val GreetingBadgeSize = 56.dp
private val GreetingIconSize = 30.dp
private const val GreetingSceneWashAlpha = 0.16f
private const val GreetingBadgeAlpha = 0.18f
