package com.linguareader.app.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.linguareader.app.R
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.components.StatusChip
import com.linguareader.app.components.StatusChipTone
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 自定义复习节奏编辑器：遗忘曲线上选留存率 + 首次复习延迟 + 每日提醒上限。
 *
 * ## 职责
 *
 * 把用户在一组控件上的选择攒成一个 **draft**（`ReviewPace`），点「保存并启用」时
 * 通过 [onSave] 交回调用方。**它只改 draft，不写盘、不通知任何人**——
 * 持久化与「把选择同步到提醒渠道」是外层的事。
 *
 * ## 为什么可以独立
 *
 * - 输入是一份 [ReviewPace] 初值 + 一个回调；不依赖卡组、不碰设置存储、不碰 Activity；
 * - 它已经把重活都委托出去了：曲线用 [ReviewCurvePicker]，预测文字用 [PacePreview]，
 *   两者都在本包内且各自可独立渲染；
 * - 唯一的本地状态是 `draft` 与 `showMore` 两个 `remember`，没有 `rememberSaveable`
 *   （草稿不需要跨进程存活，原实现就是这样）。
 *
 * 因此它能被渲染台单独画出来，也方便将来放到别处（例如独立的「节奏」页面）。
 *
 * ## 🔴 不变量
 *
 * 留存率 ↔ 间隔倍率的换算（`ReviewPace.multiplierForRetention` /
 * `retentionForMultiplier`）是**复习算法的对外口径**，本文件只调用、不修改。
 * 三个内置节奏在曲线上的参考点位置同理。
 *
 * ## 每日提醒上限
 *
 * [MIN_DAILY_PROMPT_LIMIT] / [MAX_DAILY_PROMPT_LIMIT] 是 Q2-c06 定下的可调范围
 * （越界由按钮禁用拦住），随编辑器一起搬出。
 */

/** Q2-c06：每日提醒上限的可调范围（越界由按钮禁用拦住）。 */
private const val MIN_DAILY_PROMPT_LIMIT = 1
private const val MAX_DAILY_PROMPT_LIMIT = 10

// ── 选项胶囊的几何 ────────────────────────────────────────────────
//
// 与 ReviewUi 时期的判据一致：间距梯只有 4/8/16/24/32/48，胶囊的 12/7/1
// 硬套都会改变像素，而 P3 的验收前提是视觉等价。

/** 选项胶囊的水平内边距。 */
private val ChipHorizontalPadding = 12.dp

/** 选项胶囊的垂直内边距（比水平小一档：文字本身已有行高）。 */
private val ChipVerticalPadding = 7.dp

/** 选项胶囊的描边粗细。 */
private val ChipBorder = 1.dp

/**
 * 一排「节奏选项」卡片：单选圆点 + 标题（+ 可选徽标）+ 说明 + 摘要行。
 *
 * 抽出来是因为内置三档与「自定义」那一张的排布**完全一致**——原实现是两段
 * 复制粘贴的 `Card { Row { RadioButton; Column { ... } } }`。抽成一处后，
 * 选中态的配色与圆角只有一个定义，加第四档也不会漏改。
 *
 * 圆点保持 Material 的 [RadioButton]：它的可访问性语义（单选组）比自绘圆点可靠，
 * 且配色由 MaterialTheme 的 primary 派生，这里只补一个 token 色，
 * 不重画整个控件。
 */
@Composable
internal fun PaceOptionCard(
    title: String,
    badge: String?,
    description: String,
    summary: String,
    selected: Boolean,
    onSelect: () -> Unit,
    summaryIsAction: Boolean = false
) {
    val t = LocalDesignTokens.current
    // 这里**没有**用 [LinguaCard]：它的 containerColor 固定取 cardSurface，不接受颜色/选中态。
    // 而「选中那张卡片整块染 accentSoft」是这一组选项的**主可读性线索**
    // （只靠左侧小圆点，扫视时几乎看不出来哪个是当前节奏）。
    // 所以保留 Card + 由 token 驱动的两套配色，形状/描边/配色仍全部来自设计系统。
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) t.colors.accentSoft else t.colors.cardSurface
        ),
        shape = t.cardShape,
        // 纸感风格不用阴影分层（TokenElevation.useShadow = false）：选中用色阶表达，
        // 未选中用一圈描边把卡片与纸底分开。
        elevation = CardDefaults.cardElevation(defaultElevation = t.elevation.none),
        border = if (selected) null else BorderStroke(t.hairline, t.colors.outline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = t.spacing.s)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(t.spacing.cardPadding),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = t.colors.accent,
                    unselectedColor = t.colors.inkSoft
                )
            )
            Column(
                modifier = Modifier.weight(1f).padding(start = t.spacing.xs)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = title, style = t.typography.body, color = t.colors.ink)
                    if (badge != null) {
                        Spacer(Modifier.width(t.spacing.s))
                        StatusChip(text = badge, tone = StatusChipTone.ACCENT)
                    }
                }
                Spacer(Modifier.height(t.spacing.xs))
                Text(
                    text = description,
                    style = t.typography.caption,
                    color = t.colors.inkSoft
                )
                Spacer(Modifier.height(t.spacing.xs))
                Text(
                    text = summary,
                    style = t.typography.label,
                    color = if (summaryIsAction) t.colors.accent else t.colors.inkSoft
                )
            }
        }
    }
}

@Composable
internal fun CustomReviewEditor(
    initial: ReviewPace,
    onSave: (ReviewPace) -> Unit
) {
    val t = LocalDesignTokens.current
    var draft by remember { mutableStateOf(initial) }
    var showMore by remember { mutableStateOf(false) }
    val retention = ReviewPace.retentionForMultiplier(draft.intervalMultiplier)

    // 编辑器是一块「内嵌面板」：原实现用 PaperDeep 铺底把自身上下沉一级，
    // 这里改用 LinguaCard（描边分层）并显式指定 paperDeep 底，保持同样的层级关系。
    Card(
        colors = CardDefaults.cardColors(containerColor = t.colors.paperDeep),
        shape = t.cardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = t.elevation.none),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(t.spacing.m)) {
            Text(
                text = stringResource(R.string.review_custom_editor_title),
                style = t.typography.title,
                color = t.colors.ink
            )
            Spacer(Modifier.height(t.spacing.xs))
            Text(
                text = stringResource(R.string.review_custom_editor_hint),
                style = t.typography.caption,
                color = t.colors.inkSoft
            )
            Spacer(Modifier.height(t.spacing.s))
            Text(
                text = stringResource(
                    R.string.review_retention_label,
                    ReviewPace.retentionPercent(retention)
                ),
                style = t.typography.title,
                color = t.colors.accent
            )
            Spacer(Modifier.height(t.spacing.s))
            ReviewCurvePicker(
                retention = retention,
                onRetentionChange = { r ->
                    draft = draft.copy(intervalMultiplier = ReviewPace.multiplierForRetention(r))
                }
            )
            Spacer(Modifier.height(t.spacing.xs))
            Text(
                text = stringResource(
                    R.string.review_retention_scale,
                    retentionPercentOf(ReviewMode.IMMERSIVE),
                    retentionPercentOf(ReviewMode.GENTLE),
                    retentionPercentOf(ReviewMode.DILIGENT)
                ),
                style = t.typography.label,
                color = t.colors.inkFaint
            )
            Spacer(Modifier.height(t.spacing.s))
            PacePreview(pace = draft)
            Spacer(Modifier.height(t.spacing.m))
            Text(
                text = stringResource(R.string.review_first_delay_label),
                style = t.typography.label,
                color = t.colors.inkSoft
            )
            Spacer(Modifier.height(t.spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                firstDelayOptions().forEach { (millis, label) ->
                    OptionChip(
                        label = label,
                        selected = draft.firstDelayMillis == millis,
                        onClick = { draft = draft.copy(firstDelayMillis = millis) }
                    )
                }
            }
            Spacer(Modifier.height(t.spacing.m))
            LinguaTextButton(
                text = if (showMore) {
                    stringResource(R.string.review_fewer_options)
                } else {
                    stringResource(R.string.review_more_options)
                },
                onClick = { showMore = !showMore }
            )
            if (showMore) {
                Text(
                    text = stringResource(R.string.review_daily_limit_label),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
                Spacer(Modifier.height(t.spacing.xs))
                // Q2-c06：每日提醒上限改为可自定义（原先只有 1/2/4 三档）。
                // 原「单次最多复习」档位已随 Q1-t05 的修复失去作用（牌组改为给全部到期词），
                // 按裁决删除，不再保留无效控件。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            if (draft.dailyPromptLimit > MIN_DAILY_PROMPT_LIMIT) {
                                draft = draft.copy(dailyPromptLimit = draft.dailyPromptLimit - 1)
                            }
                        },
                        enabled = draft.dailyPromptLimit > MIN_DAILY_PROMPT_LIMIT
                    ) {
                        Icon(
                            Icons.Filled.Remove,
                            contentDescription = stringResource(R.string.review_daily_limit_decrease),
                            tint = t.colors.ink
                        )
                    }
                    Text(
                        text = pluralStringResource(
                            R.plurals.review_count_times,
                            draft.dailyPromptLimit,
                            draft.dailyPromptLimit
                        ),
                        style = t.typography.body,
                        color = t.colors.ink
                    )
                    IconButton(
                        onClick = {
                            if (draft.dailyPromptLimit < MAX_DAILY_PROMPT_LIMIT) {
                                draft = draft.copy(dailyPromptLimit = draft.dailyPromptLimit + 1)
                            }
                        },
                        enabled = draft.dailyPromptLimit < MAX_DAILY_PROMPT_LIMIT
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.review_daily_limit_increase),
                            tint = t.colors.ink
                        )
                    }
                }
            }
            Spacer(Modifier.height(t.spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                LinguaTextButton(
                    text = stringResource(R.string.review_reset_default),
                    onClick = { draft = ReviewPace.defaultCustom() }
                )
                PrimaryButton(
                    text = stringResource(R.string.review_save_enable),
                    onClick = { onSave(draft) }
                )
            }
            Spacer(Modifier.height(t.spacing.xs))
            Text(
                text = stringResource(R.string.review_save_enable_hint),
                style = t.typography.label,
                color = t.colors.inkFaint
            )
        }
    }
}

/**
 * 可选中的胶囊（「首次复习」的 5 分钟 / 30 分钟 / 2 小时 / 次日）。
 *
 * **刻意没有改用 [StatusChip]**：那个是「状态徽章」——不可点、只陈述事实，语义上是标签；
 * 这个的**全部意义在于可选中**，点击会改节奏参数。把可选控件画成状态徽章，
 * 用户看不出它能点。两者共享的只有 pill 形状与配色 token，那部分本来就来自设计系统，
 * 不需要为此合并类型。
 *
 * 因此保留为本屏的私有组件（组件层新增 `SelectableChip` 需要 Lead 裁决，本屏不越界）。
 * 若后续别处也出现同款，再抽到 components/ 去。
 */
@Composable
internal fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Box(
        modifier = Modifier
            .clip(t.pillShape)
            .background(if (selected) t.colors.accentSoft else Color.Transparent)
            .border(
                width = ChipBorder,
                color = if (selected) t.colors.accent else t.colors.outline,
                shape = t.pillShape
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = ChipHorizontalPadding, vertical = ChipVerticalPadding)
    ) {
        Text(
            text = label,
            style = t.typography.label,
            color = if (selected) t.colors.accentDeep else t.colors.inkSoft
        )
    }
}

/**
 * 取某个内置节奏在遗忘曲线上的留存率（「沉浸 / 温和 / 勤学」那行刻度用）。
 *
 * 与 [ReviewCurvePicker] 画参考点时用的是同一套换算，保证「刻度文字」与
 * 「曲线上的点」指的是同一件事。纯函数，随编辑器一起搬出。
 */
internal fun retentionPercentOf(mode: ReviewMode): Int =
    ReviewPace.retentionPercent(ReviewPace.retentionForMultiplier(mode.intervalMultiplier))
