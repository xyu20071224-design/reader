package com.linguareader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.linguareader.shared.app.PreferencesStore
import com.linguareader.shared.data.ReviewMode
import com.linguareader.shared.data.ReviewPace
import java.util.Locale

/** Keeps the active selection separate from the saved custom parameters. */
internal class DesktopReviewSettings(private val preferences: PreferencesStore) {
    var preset: ReviewMode? by mutableStateOf(
        when (val name = preferences.getString(ReviewMode.PREFERENCE_KEY)) {
            ReviewPace.CUSTOM_NAME -> null
            else -> runCatching { ReviewMode.valueOf(name.orEmpty()) }.getOrDefault(ReviewMode.DEFAULT)
        }
    )
        private set

    val custom: ReviewPace
        get() = ReviewPace.fromJson(preferences.getString(ReviewPace.STORAGE_KEY))
            ?: ReviewPace.defaultCustom()

    fun selectPreset(mode: ReviewMode) {
        preferences.putString(ReviewMode.PREFERENCE_KEY, mode.name)
        preset = mode
    }

    fun selectCustom() {
        preferences.putString(ReviewMode.PREFERENCE_KEY, ReviewPace.CUSTOM_NAME)
        preset = null
    }

    fun saveCustom(pace: ReviewPace) {
        // Persist the parameters before activating them, including on a first save.
        preferences.putString(ReviewPace.STORAGE_KEY, pace.toJson())
        selectCustom()
    }
}

@Composable
internal fun ReviewSettingsSection(preferences: PreferencesStore) {
    val settings = remember(preferences) { DesktopReviewSettings(preferences) }
    var draft by remember(settings) { mutableStateOf(settings.custom) }
    var saveResult by remember(settings) { mutableStateOf<Result<Unit>?>(null) }

    fun persist(update: () -> Unit) {
        saveResult = runCatching(update)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("复习节奏", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            for (mode in ReviewMode.entries) {
                ReviewPaceOption(
                    title = mode.label,
                    description = "首次 ${mode.firstDelayMillis / 60_000} 分钟 · 倍率 ×${mode.intervalMultiplier}",
                    selected = settings.preset == mode,
                    onSelect = { persist { settings.selectPreset(mode) } }
                )
            }
            ReviewPaceOption(
                title = "自定义",
                description = "使用已保存的自定义复习参数",
                selected = settings.preset == null,
                onSelect = { persist { settings.selectCustom() } }
            )
        }

        if (settings.preset == null) {
            CustomReviewPaceEditor(
                draft = draft,
                onChange = {
                    draft = it
                    saveResult = null
                },
                onSave = { persist { settings.saveCustom(draft) } }
            )
        }

        saveResult?.let { result ->
            Text(
                text = result.fold(
                    onSuccess = { "复习设置已保存" },
                    onFailure = { "保存复习设置失败：${it.message.orEmpty()}" }
                ),
                color = if (result.isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ReviewPaceOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CustomReviewPaceEditor(
    draft: ReviewPace,
    onChange: (ReviewPace) -> Unit,
    onSave: () -> Unit
) {
    val retention = ReviewPace.retentionForMultiplier(draft.intervalMultiplier)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "目标保留率 ${ReviewPace.retentionPercent(retention)}% · 间隔倍率 ×" +
                String.format(Locale.ROOT, "%.2f", draft.intervalMultiplier),
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = retention.toFloat(),
            onValueChange = {
                onChange(draft.copy(intervalMultiplier = ReviewPace.multiplierForRetention(it.toDouble())))
            },
            valueRange = ReviewPace.MIN_RETENTION.toFloat()..ReviewPace.MAX_RETENTION.toFloat(),
            modifier = Modifier.semantics { contentDescription = "目标保留率" }
        )

        Text("首次复习延迟（当前 ${draft.firstDelayMillis / 60_000} 分钟）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((delay, label) in listOf(
                5 * 60_000L to "5 分钟",
                30 * 60_000L to "30 分钟",
                2 * 3_600_000L to "2 小时",
                24 * 3_600_000L to "次日"
            )) {
                FilterChip(
                    selected = draft.firstDelayMillis == delay,
                    onClick = { onChange(draft.copy(firstDelayMillis = delay)) },
                    label = { Text(label) }
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("每日提醒上限 ${draft.dailyPromptLimit} 次")
            OutlinedButton(
                onClick = { onChange(draft.copy(dailyPromptLimit = draft.dailyPromptLimit - 1)) },
                enabled = draft.dailyPromptLimit > 1,
                modifier = Modifier.semantics { contentDescription = "减少每日提醒上限" }
            ) { Text("−") }
            OutlinedButton(
                onClick = { onChange(draft.copy(dailyPromptLimit = draft.dailyPromptLimit + 1)) },
                enabled = draft.dailyPromptLimit < 10,
                modifier = Modifier.semantics { contentDescription = "增加每日提醒上限" }
            ) { Text("+") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onChange(ReviewPace.defaultCustom()) }) { Text("恢复默认") }
            Button(onClick = onSave) { Text("保存并启用") }
        }
    }
}
