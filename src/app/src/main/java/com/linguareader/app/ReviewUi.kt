package com.linguareader.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.components.SettingRow
import com.linguareader.app.components.SheetDivider
import com.linguareader.app.components.StatusChip
import com.linguareader.app.components.StatusChipTone
import com.linguareader.app.skin.LocalDesignTokens
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linguareader.app.R
import com.linguareader.app.res.resolve
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.app.data.ReviewScheduler
import com.linguareader.app.data.SavedWord
import kotlinx.coroutines.delay
import kotlin.math.pow

/** Shared review deck used by the vocabulary screen and reader-side entries. */
@OptIn(ExperimentalMaterial3Api::class)
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        modifier = Modifier.testTag(UiTags.REVIEW_SHEET)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 26.dp).padding(bottom = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (allDone) {
                Spacer(Modifier.height(28.dp))
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Success
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.review_done_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.review_done_body, completedCount, deck.size),
                    color = InkSoft,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                    shape = PillShape
                ) { Text(stringResource(R.string.common_done)) }
                Spacer(Modifier.height(16.dp))
            } else {
                Text(
                    if (completedCount == 0) {
                        stringResource(R.string.review_card_position, index + 1, deck.size)
                    } else {
                        stringResource(R.string.review_card_progress, completedCount, deck.size)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSoft
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    word.headword,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (word.phonetic.isNotBlank()) {
                    Text("/${word.phonetic}/", color = InkSoft)
                }
                TextButton(onClick = { onSpeak(word.headword) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.common_speak))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    word.sentence,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Serif
                )
                Spacer(Modifier.height(20.dp))
                if (revealed) {
                    Text(
                        word.meaning,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (word.aiMeaning.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (word.aiSource.isBlank()) {
                                stringResource(R.string.common_book_context, word.aiMeaning)
                            } else {
                                stringResource(
                                    R.string.common_book_context_source,
                                    word.aiSource,
                                    word.aiMeaning
                                )
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = Accent,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (word.aiExplanation.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.review_context_explanation, word.aiExplanation),
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSoft,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TextButton(onClick = { finish(false) }, enabled = !busy) {
                            Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.review_learn_again))
                        }
                        Button(
                            onClick = { finish(true) },
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Success,
                                contentColor = OnAccent
                            ),
                            shape = PillShape
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.review_known))
                        }
                    }
                } else {
                    Button(
                        onClick = { revealed = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                        shape = PillShape
                    ) { Text(stringResource(R.string.review_reveal)) }
                }
            }
        }
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
private fun PaceOptionCard(
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
private fun CustomReviewEditor(
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


@Composable
private fun ReviewCurvePicker(
    retention: Double,
    onRetentionChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnChange by rememberUpdatedState(onRetentionChange)
    // Canvas 的绘制作用域不是 @Composable，配色要先在这里取出来。
    val gridColor = Ink.copy(alpha = .10f)
    val curveColor = Accent
    val markerColor = Ink.copy(alpha = .45f)
    val handleFill = Paper
    val curveDescription = stringResource(R.string.review_curve_desc)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
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
        val left = 8.dp.toPx()
        val top = 10.dp.toPx()
        val right = 8.dp.toPx()
        val bottom = 16.dp.toPx()
        val chartWidth = size.width - left - right
        val chartHeight = size.height - top - bottom

        fun yFor(r: Double): Float = (top + chartHeight * (1.0 - r)).toFloat()
        fun xFor(r: Double): Float = (left + chartWidth * ReviewCurve.fractionForRetention(r)).toFloat()

        listOf(1.0, 0.85, 0.60).forEach { r ->
            drawLine(
                color = gridColor,
                start = Offset(left, yFor(r)),
                end = Offset(size.width - right, yFor(r)),
                strokeWidth = 1.dp.toPx()
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
        drawPath(path, color = curveColor, style = Stroke(width = 2.dp.toPx()))

        ReviewMode.entries.forEach { mode ->
            val r = ReviewPace.retentionForMultiplier(mode.intervalMultiplier)
            drawCircle(
                color = markerColor,
                radius = 3.dp.toPx(),
                center = Offset(xFor(r), yFor(r))
            )
        }

        val handleRetention = retention.coerceIn(ReviewPace.MIN_RETENTION, ReviewPace.MAX_RETENTION)
        val handle = Offset(xFor(handleRetention), yFor(handleRetention))
        drawCircle(color = curveColor, radius = 9.dp.toPx(), center = handle)
        drawCircle(color = handleFill, radius = 4.dp.toPx(), center = handle)
    }
}

@Composable
private fun PacePreview(pace: ReviewPace) {
    Column {
        Text(stringResource(R.string.review_pace_preview_title), color = InkSoft, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.review_pace_preview_first, firstDelayLabel(pace.firstDelayMillis)))
        Text(stringResource(R.string.review_pace_preview_nth, 2, formatApproxDuration(ReviewScheduler.intervalFor(1, pace))))
        Text(stringResource(R.string.review_pace_preview_nth, 3, formatApproxDuration(ReviewScheduler.intervalFor(2, pace))))
        Text(stringResource(R.string.review_pace_preview_nth, 4, formatApproxDuration(ReviewScheduler.intervalFor(3, pace))))
    }
}

// ── 组件专有几何常量 ──────────────────────────────────────────────
//
// 这些值**不进间距梯**，理由与 `ShelfAppearanceSheet` 同一口径：它们不是「间距」，
// 而是这一屏自己的形状参数（胶囊内边距、曲线画布高度、坐标轴内缩）。
// 间距梯只有 4/8/16/24/32/48：7、10、12、180 硬套都会改变像素，
// 而 P3 的验收前提是「迁移前后视觉等价」。聚成命名常量，可读性不比裸 dp 差。

/** 选项胶囊的水平内边距。 */
private val ChipHorizontalPadding = 12.dp

/** 选项胶囊的垂直内边距（比水平小一档：文字本身已有行高）。 */
private val ChipVerticalPadding = 7.dp

/** 选项胶囊的描边粗细。 */
private val ChipBorder = 1.dp

/** Q2-c06：每日提醒上限的可调范围（越界由按钮禁用拦住）。 */
private const val MIN_DAILY_PROMPT_LIMIT = 1
private const val MAX_DAILY_PROMPT_LIMIT = 10

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
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
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

private fun retentionPercentOf(mode: ReviewMode): Int =
    ReviewPace.retentionPercent(ReviewPace.retentionForMultiplier(mode.intervalMultiplier))

@Composable
private fun firstDelayOptions(): List<Pair<Long, String>> = listOf(
    5 * 60_000L to stringResource(R.string.review_delay_5m),
    30 * 60_000L to stringResource(R.string.review_delay_30m),
    2 * 3_600_000L to stringResource(R.string.review_delay_2h),
    24 * 3_600_000L to stringResource(R.string.review_delay_next_day)
)

@Composable
private fun firstDelayLabel(millis: Long): String =
    firstDelayOptions().firstOrNull { it.first == millis }?.second
        ?: formatApproxDuration(millis)

@Composable
private fun formatApproxDuration(millis: Long): String {
    val minutes = millis / 60_000L
    return when {
        minutes < 60 -> stringResource(R.string.review_in_minutes_approx, minutes)
        minutes < 24 * 60 -> stringResource(R.string.review_in_hours_approx, minutes / 60)
        else -> stringResource(R.string.review_in_days_approx, minutes / (24 * 60))
    }
}

/** Non-modal pause prompt shown at chapter boundaries / before leaving the reader. */
@Composable
internal fun ReviewPromptBanner(
    count: Int,
    dwellMillis: Long,
    onStart: () -> Unit,
    onDismiss: () -> Unit
) {
    LaunchedEffect(dwellMillis) {
        if (dwellMillis > 0) {
            delay(dwellMillis)
            onDismiss()
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PaperDeep.copy(alpha = .97f),
        shadowElevation = 3.dp
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                pluralStringResource(R.plurals.review_banner_due, count, count),
                modifier = Modifier.weight(1f),
                color = Ink,
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onStart) { Text(stringResource(R.string.review_banner_start), color = Accent) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_banner_dismiss)) }
        }
    }
}
