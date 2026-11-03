package com.linguareader.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 分区标题：屏幕上一个大区块的名字（「最近阅读」「存储」「同步」）。
 *
 * 与 [CardSectionLabel] 的区别：这个在卡片**外面**，负责划分屏幕；那个在卡片**里面**，
 * 负责划分内容。现有代码里两者都写成 `typography.labelLarge + InkSoft`，看起来一样、
 * 层级却不同，迁移时容易用错。
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = t.spacing.s,
                end = t.spacing.s,
                top = t.spacing.l,
                bottom = t.spacing.s
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = t.typography.label,
            color = t.colors.inkSoft,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) trailing()
    }
}

/**
 * 状态标签（成功 / 失败 / 中性 / 警示）。
 *
 * 用低饱和的语义色底 + 同色系深字，而不是 Material 那种实心高饱和 chip ——
 * 纸感界面上一颗荧光绿胶囊会非常跳。
 *
 * 硬约束：样式只来自 [LocalDesignTokens]。
 */
@Composable
fun StatusChip(
    text: String,
    tone: StatusChipTone = StatusChipTone.NEUTRAL,
    modifier: Modifier = Modifier
) {
    val t = LocalDesignTokens.current
    val (foreground, background) = when (tone) {
        StatusChipTone.SUCCESS -> t.colors.success to t.colors.success.copy(alpha = t.chipWashAlpha)
        StatusChipTone.DANGER -> t.colors.danger to t.colors.danger.copy(alpha = t.chipWashAlpha)
        StatusChipTone.WARNING -> t.colors.warning to t.colors.warning.copy(alpha = t.chipWashAlpha)
        StatusChipTone.ACCENT -> t.colors.accent to t.colors.accentSoft
        StatusChipTone.NEUTRAL -> t.colors.inkSoft to t.colors.paperDeep
    }
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(t.radii.pill))
            .padding(horizontal = t.spacing.s, vertical = t.spacing.xs)
    ) {
        Text(text = text, style = t.typography.label, color = foreground)
    }
}

/**
 * [StatusChip] 的语义色调。
 *
 * 刻意不复用 Snackbar 的 `StatusTone`：那边只有三档，这里多了 WARNING/ACCENT，
 * 且两者的演化方向不同（提示条 vs 状态徽章），不该为了省一个枚举把它们绑死。
 */
enum class StatusChipTone { NEUTRAL, SUCCESS, DANGER, WARNING, ACCENT }

/**
 * 空状态：图标 + 标题 + 说明（+ 可选动作按钮）。
 *
 * 替换现有 3 处各写一遍的空书架/空生词本/空搜索结果。空状态是**引导**不是墓碑，
 * 所以标题必须说「能做什么」，而不是「这里没有东西」。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String? = null,
    action: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val t = LocalDesignTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = t.spacing.xl, vertical = t.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(t.spacing.m)
    ) {
        Box(
            modifier = Modifier
                .size(t.emptyStateIconSize)
                .background(t.colors.paperDeep, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = t.colors.inkFaint,
                modifier = Modifier.size(t.iconSize)
            )
        }
        Text(
            text = title,
            style = t.typography.title,
            color = t.colors.ink,
            textAlign = TextAlign.Center
        )
        if (body != null) {
            Text(
                text = body,
                style = t.typography.caption,
                color = t.colors.inkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = t.proseMaxWidth)
            )
        }
        if (action != null) {
            Spacer(Modifier.height(t.spacing.xs))
            action()
        }
    }
}

/** 空状态里的「箭头去别处」这类次级引导行。 */
@Composable
fun EmptyStateHint(text: String, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .background(t.colors.accentSoft, RoundedCornerShape(t.radii.pill))
            .padding(horizontal = t.spacing.m, vertical = t.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.spacing.xs)
    ) {
        Text(text = text, style = t.typography.caption, color = t.colors.accentDeep)
        Icon(
            imageVector = Icons.Filled.ArrowForward,
            contentDescription = null,
            tint = t.colors.accentDeep,
            modifier = Modifier.size(t.spacing.m)
        )
    }
}
