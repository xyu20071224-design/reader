package com.linguareader.app.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.linguareader.app.R
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.components.SettingRow
import com.linguareader.app.components.SheetDivider
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.app.res.resolve
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 复习设置的**内容体**，与 sheet 外壳解耦。
 *
 * ## 职责
 *
 * 画「提醒方式 + 复习节奏」两段设置，把用户操作通过回调交回外壳。
 * **它不申请权限、不写盘、不做预设与提醒的联动**——那些全在
 * [com.linguareader.app.ReviewSettingsSheet]。
 *
 * ## 为什么可以独立
 *
 * 输入是三个值对象（preset / custom / reminders）+ 两个 UI 态 + 五个回调，
 * 没有任何 Android 平台依赖（对比外壳：那边要 `POST_NOTIFICATIONS` 与
 * `rememberLauncherForActivityResult`）。所以它可被渲染台单独画出
 * （见 `ReviewSettingsRenderTest`）。
 *
 * ⚠️ **单独使用时不自带宽度/背景约束**：真实场景继承 `LinguaSheet` 的纸色与留白。
 *
 * ## 🔴 联动规则不在这里
 *
 * 「选某个预设会自动套上它的一套提醒组合」是产品规则（F-137/F-138），
 * 由外壳在 `selectPreset` 里实现。本文件只管画与转交点击，
 * **不要**在这里补默认值逻辑，否则规则会有两个真相。
 */
/**
 * 复习设置的**内容体**，与 sheet 外壳解耦。
 *
 * 理由同 `UpdateSheetContent`：`ModalBottomSheet` 在渲染台里画不进位图，
 * 抽出来才能做迁移前后的视觉对比。代价也一样——**不再自带宽度/背景约束**，
 * 单独使用时调用方必须补齐（渲染台见 `ReviewSettingsRenderTest`）。
 *
 * 通知权限那套 Activity 副作用留在外壳（[ReviewSettingsSheet]），这里只收状态与回调，
 * 所以它是纯展示、可渲染的。
 */
@Composable
internal fun ReviewSettingsContent(
    preset: ReviewMode?,
    custom: ReviewPace,
    reminders: ReviewReminders,
    editingCustom: Boolean,
    notificationDenied: Boolean,
    onSelectPreset: (ReviewMode) -> Unit,
    onToggleNotification: (Boolean) -> Unit,
    onChangeReminders: (ReviewReminders) -> Unit,
    onChangeCustom: (ReviewPace) -> Unit,
    onStartCustomEdit: () -> Unit
) {
    val t = LocalDesignTokens.current
    // 原实现的滚动容器就在这里（`verticalScroll`），保持不变：内容常超出屏幕高度。
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.review_settings_title),
            style = t.typography.title,
            color = t.colors.ink
        )
        Spacer(Modifier.height(t.spacing.xs))
        Text(
            text = stringResource(R.string.review_settings_hint),
            style = t.typography.caption,
            color = t.colors.inkSoft
        )
        Spacer(Modifier.height(t.spacing.m))

        // 分区标题：原实现是 titleMedium/SemiBold（16sp/600）。
        // 排版梯的 label 也是 12sp/600——比原来小；但「提醒方式 / 复习节奏」是**分区名**
        // 而不是正文，SectionHeader 正是这个语义位，且逐屏用同一套才不会各屏不一。
        SectionHeader(text = stringResource(R.string.review_reminder_section))
        // 原「标题+说明+Switch」手写行 → SettingRow（行高、副标题色、开关配色统一）。
        SettingRow(
            title = stringResource(R.string.review_reminder_context_title),
            subtitle = stringResource(R.string.review_reminder_context_desc),
            checked = reminders.contextHighlight,
            onCheckedChange = { onChangeReminders(reminders.copy(contextHighlight = it)) }
        )
        SettingRow(
            title = stringResource(R.string.review_reminder_pause_title),
            subtitle = stringResource(R.string.review_reminder_pause_desc),
            checked = reminders.pausePrompt,
            onCheckedChange = { onChangeReminders(reminders.copy(pausePrompt = it)) }
        )
        SettingRow(
            title = stringResource(R.string.review_reminder_badge_title),
            subtitle = stringResource(R.string.review_reminder_badge_desc),
            checked = reminders.toolbarBadge,
            onCheckedChange = { onChangeReminders(reminders.copy(toolbarBadge = it)) }
        )
        SettingRow(
            title = stringResource(R.string.review_reminder_notification_title),
            subtitle = stringResource(R.string.review_reminder_notification_desc),
            checked = reminders.notifications,
            onCheckedChange = onToggleNotification
        )
        if (notificationDenied) {
            Text(
                text = stringResource(R.string.review_notification_denied),
                style = t.typography.label,
                color = t.colors.danger
            )
        }
        SettingRow(
            title = stringResource(R.string.review_reminder_manual_title),
            subtitle = stringResource(R.string.review_reminder_manual_desc),
            checked = reminders.manualOnly,
            // 原实现只处理「打开」：关掉「仅手动」不改任何渠道（各渠道仍是全 off），
            // 这一行为原样保留。
            onCheckedChange = { enabled ->
                if (enabled) {
                    onChangeReminders(
                        ReviewReminders(
                            contextHighlight = false,
                            pausePrompt = false,
                            toolbarBadge = false,
                            notifications = false
                        )
                    )
                }
            }
        )

        Spacer(Modifier.height(t.spacing.s))
        SheetDivider()
        Spacer(Modifier.height(t.spacing.s))

        SectionHeader(text = stringResource(R.string.review_pace_section))
        Text(
            text = stringResource(R.string.review_preset_hint),
            style = t.typography.caption,
            color = t.colors.inkSoft
        )
        Spacer(Modifier.height(t.spacing.m))

        ReviewMode.entries.forEach { presetMode ->
            PaceOptionCard(
                title = stringResource(presetMode.labelRes.resolve()),
                badge = if (presetMode == ReviewMode.DEFAULT) {
                    stringResource(R.string.review_default_badge)
                } else {
                    null
                },
                description = stringResource(presetMode.descriptionRes.resolve()),
                summary = stringResource(
                    R.string.review_pace_summary,
                    presetMode.firstDelayMillis / 60_000,
                    presetMode.intervalMultiplier,
                    presetMode.dailyPromptLimit
                ),
                selected = preset == presetMode,
                onSelect = { onSelectPreset(presetMode) }
            )
        }

        val customSelected = preset == null
        PaceOptionCard(
            title = stringResource(R.string.review_pace_custom),
            badge = null,
            description = stringResource(R.string.review_custom_desc),
            summary = if (customSelected) {
                stringResource(R.string.review_custom_in_use)
            } else {
                stringResource(R.string.review_custom_start)
            },
            summaryIsAction = true,
            selected = customSelected,
            onSelect = { onStartCustomEdit() }
        )

        if (editingCustom) {
            Spacer(Modifier.height(t.spacing.xs))
            CustomReviewEditor(initial = custom, onSave = onChangeCustom)
        }

        Spacer(Modifier.height(t.spacing.s))
        SheetDivider()
        Spacer(Modifier.height(t.spacing.s))
        Text(
            text = stringResource(R.string.review_no_fullscreen_note),
            style = t.typography.caption,
            color = t.colors.inkSoft
        )
    }
}

