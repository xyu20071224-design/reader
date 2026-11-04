package com.linguareader.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.linguareader.app.components.LinguaSheet
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.linguareader.app.review.ReviewCardContent
import com.linguareader.app.review.ReviewSettingsContent
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

// ── 组件专有几何常量 ──────────────────────────────────────────────
//
// 这些值**不进间距梯**，理由与 `ShelfAppearanceSheet` 同一口径：它们不是「间距」，
// 而是这一屏自己的形状参数（图标尺寸、胶囊内边距、曲线画布与其坐标轴内缩）。
// 间距梯只有 4/8/16/24/32/48；图标 18/20 与曲线 180/8/10/16/1/2/3/9/4 硬套都会
// 改变像素，而 P3 的验收前提是「迁移前后视觉等价」。聚成命名常量，可读性不比裸 dp 差。
