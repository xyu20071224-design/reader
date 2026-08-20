package com.linguareader.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.app.data.ReviewScheduler
import com.linguareader.app.data.SavedWord
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.math.roundToInt

/** Shared review deck used by the vocabulary screen and reader-side entries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewSheet(
    deck: List<SavedWord>,
    onReview: (String, Boolean, (Boolean) -> Unit) -> Unit,
    onSpeak: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var index by remember(deck) { mutableIntStateOf(0) }
    var revealed by remember(deck) { mutableStateOf(false) }
    var busy by remember(deck) { mutableStateOf(false) }
    var completedCount by remember(deck) { mutableIntStateOf(0) }
    var allDone by remember(deck) { mutableStateOf(false) }
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

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
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
                    "复习完成",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "已完成 ${completedCount} / ${deck.size} 个单词，均已安排下次复习。",
                    color = InkSoft,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                    shape = PillShape
                ) { Text("完成") }
                Spacer(Modifier.height(16.dp))
            } else {
                Text(
                    if (completedCount == 0) "第 ${index + 1} / ${deck.size} 张"
                    else "已完成 ${completedCount} / ${deck.size}",
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
                    Text("朗读")
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
                            if (word.aiSource.isBlank()) "本书语境：${word.aiMeaning}"
                            else "本书语境（${word.aiSource}）：${word.aiMeaning}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Accent,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TextButton(onClick = { finish(false) }, enabled = !busy) {
                            Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("再学一次")
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
                            Text("认识")
                        }
                    }
                } else {
                    Button(
                        onClick = { revealed = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                        shape = PillShape
                    ) { Text("显示释义") }
                }
            }
        }
    }
}

/**
 * Review settings (F-138/F-137): three built-in presets plus a custom editor
 * that picks a point on an illustrative forgetting curve; reminder channels
 * are independently toggleable.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 30.dp)
        ) {
            Text("复习设置", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "提醒方式与复习节奏彼此独立：先组合提醒载体，再选择复习频率。",
                color = InkSoft,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
            Text("提醒方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            ReminderToggleRow(
                title = "语境浮现",
                description = "已收藏词再次出现时加细点线；点击该词可在释义面板复习。",
                checked = reminders.contextHighlight,
                onCheckedChange = { onChangeReminders(reminders.copy(contextHighlight = it)) }
            )
            ReminderToggleRow(
                title = "停顿点提示",
                description = "翻章或返回书架时，显示一行“有 N 个词可快速复习”。",
                checked = reminders.pausePrompt,
                onCheckedChange = { onChangeReminders(reminders.copy(pausePrompt = it)) }
            )
            ReminderToggleRow(
                title = "工具栏角标",
                description = "阅读页顶部显示待复习数量，不主动打扰。",
                checked = reminders.toolbarBadge,
                onCheckedChange = { onChangeReminders(reminders.copy(toolbarBadge = it)) }
            )
            ReminderToggleRow(
                title = "定时轻提醒",
                description = "到期时发送本地通知（需系统通知权限）。",
                checked = reminders.notifications,
                onCheckedChange = ::toggleNotification
            )
            if (notificationDenied) {
                Text(
                    "未授予通知权限，定时轻提醒保持关闭。",
                    color = Danger,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            ReminderToggleRow(
                title = "仅手动",
                description = "关闭以上所有主动提醒，只从生词本进入复习。",
                checked = reminders.manualOnly,
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
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Ink.copy(alpha = .08f))
            Spacer(Modifier.height(8.dp))
            Text("复习节奏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "选择预设会恢复其经典提醒组合，之后仍可单独调整。",
                color = InkSoft,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
            ReviewMode.entries.forEach { presetMode ->
                val selected = preset == presetMode
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) AccentSoft else CardSurface
                    ),
                    shape = CardShape,
                    elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 1.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        .clickable { selectPreset(presetMode) }
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(selected = selected, onClick = { selectPreset(presetMode) })
                        Column(Modifier.padding(start = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    presetMode.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (presetMode == ReviewMode.DEFAULT) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "默认",
                                        color = Accent,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(presetMode.description, color = InkSoft, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "首次 ${presetMode.firstDelayMillis / 60_000} 分钟 · 间隔 ×${presetMode.intervalMultiplier} · " +
                                    "每日 ${presetMode.dailyPromptLimit} 次 · 单次 ${presetMode.sessionMaxWords} 词",
                                color = Ink.copy(alpha = .55f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            val customSelected = preset == null
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (customSelected) AccentSoft else CardSurface
                ),
                shape = CardShape,
                elevation = CardDefaults.cardElevation(defaultElevation = if (customSelected) 2.dp else 1.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .clickable { editingCustom = true }
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    RadioButton(selected = customSelected, onClick = { editingCustom = true })
                    Column(Modifier.padding(start = 6.dp)) {
                        Text(
                            "自定义",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "在记忆曲线上选择复习时还想记住多少，并调整首次复习与提醒方式。",
                            color = InkSoft,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (customSelected) "正在使用 · 点击编辑" else "点击开始调整",
                            color = Accent,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            if (editingCustom) {
                Spacer(Modifier.height(4.dp))
                CustomReviewEditor(initial = custom, onSave = onChangeCustom)
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Ink.copy(alpha = .08f))
            Spacer(Modifier.height(8.dp))
            Text(
                "阅读中不会弹出全屏复习；所有提示都可一键忽略。",
                color = InkSoft,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CustomReviewEditor(
    initial: ReviewPace,
    onSave: (ReviewPace) -> Unit
) {
    var draft by remember { mutableStateOf(initial) }
    var showMore by remember { mutableStateOf(false) }
    val retention = ReviewPace.retentionForMultiplier(draft.intervalMultiplier)

    Card(
        colors = CardDefaults.cardColors(containerColor = PaperDeep),
        shape = CardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("自定义节奏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "点越靠左复习越勤快（忘得少），越靠右越宽松。曲线是示意，不是对个人记忆的测量。",
                color = InkSoft,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "复习时约剩 ${ReviewPace.retentionPercent(retention)}% 记忆",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Accent
            )
            Spacer(Modifier.height(8.dp))
            ReviewCurvePicker(
                retention = retention,
                onRetentionChange = { r ->
                    draft = draft.copy(intervalMultiplier = ReviewPace.multiplierForRetention(r))
                }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "参考刻度：沉浸 ${retentionPercentOf(ReviewMode.IMMERSIVE)}% · 温和 ${retentionPercentOf(ReviewMode.GENTLE)}% · 勤学 ${retentionPercentOf(ReviewMode.DILIGENT)}%",
                color = InkFaint,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(12.dp))
            PacePreview(pace = draft)
            Spacer(Modifier.height(14.dp))
            Text("首次复习", color = InkSoft, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                firstDelayOptions.forEach { (millis, label) ->
                    OptionChip(
                        label = label,
                        selected = draft.firstDelayMillis == millis,
                        onClick = { draft = draft.copy(firstDelayMillis = millis) }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = { showMore = !showMore }) {
                Text(if (showMore) "收起更多选项" else "更多选项", color = Accent)
            }
            if (showMore) {
                Text("每日主动提示上限", color = InkSoft, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 4).forEach { limit ->
                        OptionChip(
                            label = "$limit 次",
                            selected = draft.dailyPromptLimit == limit,
                            onClick = { draft = draft.copy(dailyPromptLimit = limit) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("单次最多复习", color = InkSoft, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 10).forEach { maxWords ->
                        OptionChip(
                            label = "$maxWords 词",
                            selected = draft.sessionMaxWords == maxWords,
                            onClick = { draft = draft.copy(sessionMaxWords = maxWords) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { draft = ReviewPace.defaultCustom() }) {
                    Text("恢复默认", color = Accent)
                }
                Button(
                    onClick = { onSave(draft) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                    shape = PillShape
                ) { Text("保存并启用") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "节奏修改后需点“保存并启用”才会应用。",
                color = InkFaint,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ReminderToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                color = InkSoft,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics { contentDescription = "记忆曲线" }
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
        Text("按此节奏", color = InkSoft, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text("首次：${firstDelayLabel(pace.firstDelayMillis)}")
        Text("第 2 次：${formatApproxDuration(ReviewScheduler.intervalFor(1, pace))}")
        Text("第 3 次：${formatApproxDuration(ReviewScheduler.intervalFor(2, pace))}")
        Text("第 4 次：${formatApproxDuration(ReviewScheduler.intervalFor(3, pace))}")
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) AccentSoft else CardSurface
        ),
        shape = SmallShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = if (selected) AccentDeep else InkSoft,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.labelMedium
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

private val firstDelayOptions = listOf(
    5 * 60_000L to "5 分钟",
    30 * 60_000L to "30 分钟",
    2 * 3_600_000L to "2 小时",
    24 * 3_600_000L to "次日"
)

private fun firstDelayLabel(millis: Long): String =
    firstDelayOptions.firstOrNull { it.first == millis }?.second
        ?: formatApproxDuration(millis)

private fun formatApproxDuration(millis: Long): String {
    val minutes = millis / 60_000L
    return when {
        minutes < 60 -> "约 $minutes 分钟后"
        minutes < 24 * 60 -> "约 ${minutes / 60} 小时后"
        else -> "约 ${minutes / (24 * 60)} 天后"
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
                "有 $count 个词可快速复习",
                modifier = Modifier.weight(1f),
                color = Ink,
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onStart) { Text("开始", color = Accent) }
            TextButton(onClick = onDismiss) { Text("忽略") }
        }
    }
}
