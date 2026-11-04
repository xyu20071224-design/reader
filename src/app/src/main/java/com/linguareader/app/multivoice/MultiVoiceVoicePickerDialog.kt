package com.linguareader.app.multivoice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.linguareader.app.R
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.tts.VoiceLibrary
import com.linguareader.app.tts.VoicePicker

/**
 * 音色选择弹层：搜索 + 「推荐 / 语言·性别」分组 + 逐项试听。
 *
 * ## 为什么是搜索+分组，不是长下拉
 *
 * Kokoro 一台服务器就有 100+ 音色。下拉在手机上要滚很久、且不能搜索，选一个音色
 * 成为体力活。这里换成「可搜索的分组列表」，分组由 [VoicePicker.groups] 按
 * 目标角色的语言/性别算出来（推荐组优先）。
 *
 * ## 职责边界
 *
 * 只做「展示候选音色 + 回传用户的选择」，**不写任何存储**：
 * - [onPick]：选中某个音色（调用方负责锁定说话人 + 让播放服务重载）；
 * - [onAudition]：试听（调用方持有播放器与「正在试听谁」的状态）；
 * - [onRelease]：恢复自动分配（只有当前是锁定状态时才显示这个按钮）。
 *
 * ## 拆分前原状
 *
 * 原本在 `MultiVoiceSettings.kt`（1980 行）里，与主列表、角色弹层、MiMo 面板同文件。
 * 本次只搬移 + 可见性 private -> internal，函数体逐行未改
 * （`scripts/verify-pure-move.ps1` 机械比对确认）。
 */

@Composable
internal fun VoicePickerDialog(
    target: VoicePickerTarget,
    library: VoiceLibrary,
    currentVoice: String,
    locked: Boolean,
    playingVoice: String?,
    onAudition: (String) -> Unit,
    onPick: (String) -> Unit,
    onRelease: () -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalDesignTokens.current
    var query by remember { mutableStateOf("") }
    val auditionTemplate = stringResource(R.string.multivoice_audition, "%s")
    val auditionOf: (String) -> String = { id -> auditionTemplate.replace("%s", id) }
    val groups = remember(library, query, target) {
        VoicePicker.groups(library.voices, target.language, target.gender, query)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.colors.cardSurface,
        title = { Text(stringResource(R.string.multivoice_picker_title, target.title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.multivoice_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(t.spacing.s))
                if (groups.isEmpty()) {
                    Text(stringResource(R.string.multivoice_no_match), color = t.colors.inkSoft)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = VoicePickerMaxHeight)) {
                        groups.forEach { group ->
                            item(key = "header-" + group.title) {
                                Text(
                                    group.title,
                                    style = t.typography.label,
                                    color = t.colors.inkFaint,
                                    modifier = Modifier.padding(top = t.spacing.s, bottom = ListHeaderBottomGap)
                                )
                            }
                            items(group.options, key = { it.voice.id }) { option ->
                                val selected = option.voice.id == currentVoice
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (selected) t.colors.accentSoft else t.colors.cardSurface)
                                        .clickable { onPick(option.voice.id) }
                                        .padding(horizontal = ListRowHorizontalPadding, vertical = t.spacing.s),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        VoicePicker.label(option.voice),
                                        style = t.typography.caption,
                                        color = if (selected) t.colors.accentDeep else t.colors.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { onAudition(option.voice.id) },
                                        modifier = Modifier.semantics {
                                            contentDescription = auditionOf(option.voice.id)
                                        }
                                    ) {
                                        Icon(
                                            if (playingVoice == option.voice.id) {
                                                Icons.Default.Stop
                                            } else {
                                                Icons.Default.PlayArrow
                                            },
                                            contentDescription = null,
                                            tint = if (playingVoice == option.voice.id) t.colors.accent else t.colors.inkSoft,
                                            modifier = Modifier.size(RowIconSize)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            LinguaTextButton(text = stringResource(R.string.common_done), onClick = onDismiss)
        },
        dismissButton = {
            if (locked) {
                LinguaTextButton(text = stringResource(R.string.multivoice_release_action), onClick = onRelease)
            }
        }
    )
}