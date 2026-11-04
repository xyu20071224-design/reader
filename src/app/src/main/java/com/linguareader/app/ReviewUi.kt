package com.linguareader.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.components.SettingRow
import com.linguareader.app.components.SheetDivider
import com.linguareader.app.skin.LocalDesignTokens
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linguareader.app.R
import com.linguareader.app.res.resolve
import com.linguareader.app.review.CustomReviewEditor
import com.linguareader.app.review.PaceOptionCard
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.app.data.SavedWord

/** Shared review deck used by the vocabulary screen and reader-side entries. */
@Composable
internal fun ReviewSheet(
    deck: List<SavedWord>,
    onReview: (String, Boolean, (Boolean) -> Unit) -> Unit,
    onSpeak: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // BUG-032：记忆键必须是**卡组身份**，不能是卡组本身。
    //
    // 复习一个词会改它的 reviewLevel/nextReviewAt，上游 savedWords 随之更新，
    // 阅读页那边的 deck 又是 `savedWords.filter { it.id in ids }` 现算出来的 ——
    // 于是每评一张卡都会得到一个「不相等」的新列表。拿它当 rememberSaveable 的键，
    // index/completedCount/allDone 就会当场归零：卡片翻不过去、待复习数恒定不变。
    // 用有序 id 串当键：同一次复习会话里它不变（成员没变，只是字段变了），
    // 换一副卡组时它才变。
    val deckKey = remember(deck) { deck.joinToString("|") { it.id } }
    var index by rememberSaveable(deckKey) { mutableIntStateOf(0) }
    var revealed by rememberSaveable(deckKey) { mutableStateOf(false) }
    var busy by remember(deckKey) { mutableStateOf(false) }
    var completedCount by rememberSaveable(deckKey) { mutableIntStateOf(0) }
    var allDone by rememberSaveable(deckKey) { mutableStateOf(false) }
    val word = deck[index.coerceAtMost(deck.lastIndex)]

    fun finish(remembered: Boolean) {
        if (busy || allDone) return
        val graded = word
        busy = true
        onReview(graded.id, remembered) { ok ->
            busy = false
            if (!ok) return@onReview
            completedCount += 1
            if (index >= deck.lastIndex) {
                allDone = true
            } else {
                index += 1
                revealed = false
            }
        }
    }

    LinguaSheet(
        onDismiss = onDismiss,
        contentArrangement = Arrangement.Top
    ) {
        ReviewCardContent(
            word = word,
            cardIndex = index,
            deckSize = deck.size,
            completedCount = completedCount,
            allDone = allDone,
            revealed = revealed,
            busy = busy,
            onDismissRequest = onDismiss,
            onReveal = { revealed = true },
            onSpeak = { onSpeak(word.headword) },
            onFinish = { finish(it) }
        )
    }
}

/**
 * 复习卡片的**内容体**，与 sheet 外壳解耦。
 *
 * 理由同其余已迁弹层：`ModalBottomSheet` 在渲染台里画不进位图，抽出内容体才能做
 * 迁移前后对比。代价也一样——**不再自带宽度/背景约束**，单独使用时调用方必须补齐。
 *
 * 状态流转（index / completedCount / allDone / busy）与 `finish()` 的评分回调
 * 全部留在外壳 [ReviewSheet]：那是复习卡组的核心逻辑，有 700+ 条单测守着；
 * 这里只收快照 + 回调，评分语义一个字都不改。
 */
@Composable
internal fun ReviewCardContent(
    word: SavedWord,
    cardIndex: Int,
    deckSize: Int,
    completedCount: Int,
    allDone: Boolean,
    revealed: Boolean,
    busy: Boolean,
    onDismissRequest: () -> Unit,
    onReveal: () -> Unit,
    onSpeak: () -> Unit,
    onFinish: (Boolean) -> Unit
) {
    val t = LocalDesignTokens.current
    // REVIEW_SHEET 原本挂在 ModalBottomSheet 上（LinguaSheet 不接受 modifier）；
    // 内容体是它的子树，语义查找照样命中，故改挂在这里，不丢可达性。
    // 不再自带水平/底部留白：[LinguaSheet] 的 content 槽已经给了 horizontal = spacing.l
    // 与 bottom = spacing.xl，这里再加一层会变成双倍内边距（实测会左右各多 24dp）。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.REVIEW_SHEET),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (allDone) {
            Spacer(Modifier.height(t.spacing.l))
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(DoneIconSize),
                tint = t.colors.success
            )
            Spacer(Modifier.height(t.spacing.s))
            Text(
                text = stringResource(R.string.review_done_title),
                style = t.typography.display,
                color = t.colors.ink,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(t.spacing.xs))
            Text(
                text = stringResource(R.string.review_done_body, completedCount, deckSize),
                style = t.typography.body,
                color = t.colors.inkSoft,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(t.spacing.m))
            PrimaryButton(
                text = stringResource(R.string.common_done),
                onClick = onDismissRequest
            )
            Spacer(Modifier.height(t.spacing.m))
        } else {
            Text(
                text = if (completedCount == 0) {
                    stringResource(R.string.review_card_position, cardIndex + 1, deckSize)
                } else {
                    stringResource(R.string.review_card_progress, completedCount, deckSize)
                },
                style = t.typography.label,
                color = t.colors.inkSoft
            )
            Spacer(Modifier.height(t.spacing.m))
            Text(
                text = word.headword,
                style = t.typography.display,
                color = t.colors.ink,
                textAlign = TextAlign.Center
            )
            if (word.phonetic.isNotBlank()) {
                Text(
                    text = "/${word.phonetic}/",
                    style = t.typography.caption,
                    color = t.colors.inkSoft
                )
            }
            SpeakButton(text = stringResource(R.string.common_speak), onClick = onSpeak)
            Spacer(Modifier.height(t.spacing.s))
            Text(
                text = word.sentence,
                // 例句保持衬线：它是书里的原文，与释义在观感上必须分开（与生词本同口径）。
                style = t.typography.body.copy(fontFamily = FontFamily.Serif),
                color = t.colors.ink
            )
            Spacer(Modifier.height(t.spacing.m))
            if (revealed) {
                Text(
                    text = word.meaning,
                    style = t.typography.body,
                    color = t.colors.ink,
                    modifier = Modifier.fillMaxWidth()
                )
                if (word.aiMeaning.isNotBlank()) {
                    Spacer(Modifier.height(t.spacing.s))
                    Text(
                        text = if (word.aiSource.isBlank()) {
                            stringResource(R.string.common_book_context, word.aiMeaning)
                        } else {
                            stringResource(
                                R.string.common_book_context_source,
                                word.aiSource,
                                word.aiMeaning
                            )
                        },
                        style = t.typography.body,
                        color = t.colors.accent,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (word.aiExplanation.isNotBlank()) {
                    Spacer(Modifier.height(t.spacing.s))
                    Text(
                        text = stringResource(R.string.review_context_explanation, word.aiExplanation),
                        style = t.typography.caption,
                        color = t.colors.inkSoft,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(t.spacing.m))
                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                    LinguaTextButton(
                        text = stringResource(R.string.review_learn_again),
                        onClick = { onFinish(false) },
                        enabled = !busy
                    )
                    // 「知道了」是这张卡上的主动作，但语义色是 success 而非 accent。
                    // PrimaryButton 固定取 accent，硬套会把按钮从绿变棕，
                    // 与「评对了」这件事的反馈脱钩，故保留一颗 token 驱动的按钮。
                    Button(
                        onClick = { onFinish(true) },
                        enabled = !busy,
                        modifier = Modifier.defaultMinSize(minHeight = t.controlHeight),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = t.colors.success,
                            contentColor = t.colors.successOn,
                            disabledContainerColor = t.colors.paperDeep,
                            disabledContentColor = t.colors.inkFaint
                        ),
                        shape = t.pillShape,
                        contentPadding = PaddingValues(
                            horizontal = t.spacing.l,
                            vertical = t.spacing.s
                        )
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonIconSize)
                        )
                        Spacer(Modifier.width(t.spacing.xs))
                        Text(stringResource(R.string.review_known), style = t.typography.body)
                    }
                }
            } else {
                PrimaryButton(
                    text = stringResource(R.string.review_reveal),
                    onClick = onReveal
                )
            }
        }
    }
}

/**
 * 「朗读这个单词」的行内文字按钮（图标 + 文字）。
 *
 * 单独抽出来是因为 `components/Buttons.kt` 目前只有**纯文字**的 [LinguaTextButton]，
 * 没有图标版；这一处需要喇叭图标，而 IconButton 又会丢掉「Speak」这层文字提示。
 * 配色走 token，等组件层补出 icon 版再换（与生词本的处理一致，组件缺口已反馈）。
 */
@Composable
private fun SpeakButton(text: String, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = t.colors.accent),
        contentPadding = PaddingValues(horizontal = t.spacing.m, vertical = t.spacing.xs)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(SpeakIconSize)
        )
        Spacer(Modifier.width(t.spacing.xs))
        Text(text, style = t.typography.body)
    }
}

/**
 * 复习设置弹层（F-138 / F-137）：三个内置节奏 + 一个自定义编辑器
 * （在示意性遗忘曲线上选一个点）；提醒渠道彼此独立可关。
 *
 * P3 迁移：外壳换成 [LinguaSheet]，提醒行换成 [SettingRow]，分区标题换成 [SectionHeader]，
 * 按钮换成 [LinguaTextButton]，样式一律取 [LocalDesignTokens]。
 * **通知权限流程、预设与提醒的联动规则、持久化语义零改动**——那些逻辑留在本外壳里。
 */
@Composable
internal fun ReviewSettingsSheet(
    preset: ReviewMode?,
    custom: ReviewPace,
    reminders: ReviewReminders,
    onChangePreset: (ReviewMode) -> Unit,
    onChangeCustom: (ReviewPace) -> Unit,
    onChangeReminders: (ReviewReminders) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pendingMode by remember { mutableStateOf<ReviewMode?>(null) }
    var editingCustom by remember { mutableStateOf(preset == null) }
    var pendingNotificationToggle by remember { mutableStateOf(false) }
    var notificationDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingMode?.let { selected ->
            onChangePreset(selected)
            onChangeReminders(
                if (granted) selected.defaultReminders()
                else selected.defaultReminders().copy(notifications = false)
            )
        }
        pendingMode = null
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingNotificationToggle) {
            if (granted) {
                onChangeReminders(reminders.copy(notifications = true))
            } else {
                notificationDenied = true
            }
            pendingNotificationToggle = false
        }
    }

    fun toggleNotification(enabled: Boolean) {
        notificationDenied = false
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationToggle = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onChangeReminders(reminders.copy(notifications = enabled))
        }
    }

    fun selectPreset(selected: ReviewMode) {
        val wantsNotifications = selected.defaultReminders().notifications
        if (wantsNotifications && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingMode = selected
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onChangePreset(selected)
            onChangeReminders(selected.defaultReminders())
        }
    }

    LinguaSheet(
        onDismiss = onDismiss,
        // 内容体自带显式 Spacer 排布（迁移前就是这样，密集的设置行之间不另加块间距），
        // 关掉容器的默认 spacedBy 以免双倍叠加。
        contentArrangement = Arrangement.Top
    ) {
        ReviewSettingsContent(
            preset = preset,
            custom = custom,
            reminders = reminders,
            editingCustom = editingCustom,
            notificationDenied = notificationDenied,
            onSelectPreset = { selectPreset(it) },
            onToggleNotification = { toggleNotification(it) },
            onChangeReminders = onChangeReminders,
            onChangeCustom = onChangeCustom,
            onStartCustomEdit = { editingCustom = true }
        )
    }
}

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





// ── 组件专有几何常量 ──────────────────────────────────────────────
//
// 这些值**不进间距梯**，理由与 `ShelfAppearanceSheet` 同一口径：它们不是「间距」，
// 而是这一屏自己的形状参数（图标尺寸、胶囊内边距、曲线画布与其坐标轴内缩）。
// 间距梯只有 4/8/16/24/32/48；图标 18/20 与曲线 180/8/10/16/1/2/3/9/4 硬套都会
// 改变像素，而 P3 的验收前提是「迁移前后视觉等价」。聚成命名常量，可读性不比裸 dp 差。

/** 结算态那颗大对勾的直径（视觉锚点，比正文图标大一圈）。 */
private val DoneIconSize = 48.dp

/** 卡片内「朗读」与评分按钮里的行内图标。 */
private val SpeakIconSize = 18.dp

/** 「知道了」按钮里的对勾图标。 */
private val ButtonIconSize = 18.dp
