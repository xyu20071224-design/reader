package com.linguareader.app.multivoice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.linguareader.app.R
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.tts.MultiVoiceStatus
import com.linguareader.app.tts.MultiVoiceStatusKind
import com.linguareader.app.tts.VoiceCharacter

/**
 * 角色列表的**行**与它的两个**文案映射**（一侧展示、一侧是纯函数）。
 *
 * ## 职责边界
 *
 * - [VoiceRow]：一个可分配音色的说话人（旁白的一种语言，或一个角色）。展示名字、
 *   当前音色、锁定徽标、副标题，右侧是「更换 / 试听 / 恢复自动 / 编辑 / 删除」五个动作。
 *   **它是纯展示 + 回调**：不读任何存储，也不决定「能点哪个」—— 传进来的
 *   `locked` / `onEdit` / `onDelete` 为 null 时不渲染对应控件，判断在调用方。
 * - [characterSubtitle]：角色的「重要性 · 性别 · 风格」副标题（纯函数）。
 * - [statusText]：`MultiVoiceStatus` 数据 -> 文案（纯映射，见下）。
 *
 * ## [statusText] 为什么是纯映射
 *
 * 多角色状态（无音色库 / 无角色表 / 规则模式 / 无映射 / 共享音色 / 就绪）由
 * `MultiVoiceSupport.status()` 算成**数据**，本文件只把数据翻成文案。
 * 这样「哪种状态该显示哪句」可以脱离 Compose 单独验证，也让逻辑层不必产出中文。
 *
 * ## 拆分前原状
 *
 * 原本都在 `MultiVoiceSettings.kt`（1980 行）里。本次只搬移 + 可见性
 * private -> internal，函数体逐行未改（`scripts/verify-pure-move.ps1` 机械比对确认）。
 */
@Composable
internal fun characterSubtitle(character: VoiceCharacter): String {
    val parts = mutableListOf<String>()
    parts += when (character.importance.lowercase()) {
        "major" -> stringResource(R.string.multivoice_role_major)
        "medium" -> stringResource(R.string.multivoice_role_medium)
        else -> stringResource(R.string.multivoice_role_minor)
    }
    when (character.gender.lowercase()) {
        "male" -> parts += stringResource(R.string.multivoice_gender_male)
        "female" -> parts += stringResource(R.string.multivoice_gender_female)
    }
    if (character.style.isNotEmpty()) parts += character.style.take(2).joinToString("/")
    return parts.joinToString(" · ")
}

/** 状态提示文案：把 [MultiVoiceStatus] 映射到资源字符串。 */
@Composable
internal fun statusText(status: MultiVoiceStatus): String = when (status.kind) {
    MultiVoiceStatusKind.NO_LIBRARY -> stringResource(R.string.multivoice_status_no_library)
    MultiVoiceStatusKind.NO_ROSTER -> stringResource(R.string.multivoice_status_no_roster)
    MultiVoiceStatusKind.RULE_MODE -> stringResource(R.string.multivoice_status_rule_mode)
    MultiVoiceStatusKind.NO_MAP -> stringResource(R.string.multivoice_status_no_map)
    MultiVoiceStatusKind.SHARED_VOICES -> pluralStringResource(
        R.plurals.multivoice_status_shared,
        status.characters,
        status.characters,
        status.shared
    )

    MultiVoiceStatusKind.READY -> pluralStringResource(
        R.plurals.multivoice_status_ready,
        status.characters,
        status.characters
    )
}

/** 一行可分配的说话人：名字、当前音色、试听/停止、恢复自动。 */
@Composable
internal fun VoiceRow(
    title: String,
    currentVoice: String,
    locked: Boolean,
    playing: Boolean,
    onOpenPicker: () -> Unit,
    onAudition: () -> Unit,
    onRelease: () -> Unit,
    subtitle: String? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val t = LocalDesignTokens.current
    // semantics{} 不是 composable 作用域，标签先取出来。
    val auditionLabel = stringResource(R.string.multivoice_audition, title)
    val stopAuditionLabel = stringResource(R.string.multivoice_audition_stop)
    val releaseLabel = stringResource(R.string.multivoice_release, title)
    val editLabel = stringResource(R.string.multivoice_edit_character, title)
    val deleteLabel = stringResource(R.string.multivoice_delete_character, title)
    Row(
        Modifier.fillMaxWidth().padding(vertical = RowVerticalGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = t.typography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (locked) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = stringResource(R.string.multivoice_locked),
                        tint = t.colors.accent,
                        modifier = Modifier.padding(start = t.spacing.xs).size(LockIconSize)
                    )
                }
            }
            Text(
                currentVoice.ifBlank { stringResource(R.string.multivoice_auto) },
                style = t.typography.label,
                color = if (currentVoice.isBlank()) t.colors.inkFaint else t.colors.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(it, style = t.typography.label, color = t.colors.inkFaint)
            }
        }
        LinguaTextButton(text = stringResource(R.string.multivoice_change), onClick = onOpenPicker)
        IconButton(
            onClick = onAudition,
            enabled = currentVoice.isNotBlank(),
            modifier = Modifier.semantics {
                contentDescription = if (playing) stopAuditionLabel else auditionLabel
            }
        ) {
            Icon(
                if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (playing) t.colors.accent else t.colors.inkSoft,
                modifier = Modifier.size(MediumIconSize)
            )
        }
        if (locked) {
            IconButton(
                onClick = onRelease,
                modifier = Modifier.semantics { contentDescription = releaseLabel }
            ) {
                Icon(
                    Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = t.colors.inkFaint,
                    modifier = Modifier.size(RowIconSize)
                )
            }
        }
        if (onEdit != null) {
            IconButton(
                onClick = onEdit,
                modifier = Modifier.semantics { contentDescription = editLabel }
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    tint = t.colors.inkFaint,
                    modifier = Modifier.size(SmallIconSize)
                )
            }
        }
        if (onDelete != null) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = deleteLabel }
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = t.colors.inkFaint,
                    modifier = Modifier.size(SmallIconSize)
                )
            }
        }
    }
}