package com.linguareader.app.multivoice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.linguareader.app.tts.VoiceCharacter

/**
 * 角色管理：**添加角色**与**编辑角色**两个弹层（多角色音色的「角色表」侧）。
 *
 * ## 职责边界
 *
 * 这一对弹层只负责**收集/校验用户输入并回调**，不碰存储：写术语表（glossary）、
 * 刷角色列表、重跑音色分配全是调用方 [MultiVoiceSection] 的事。所以它们能脱离
 * Android 存储层单独渲染（见 `MultiVoiceRenderTest` 的思路）。
 *
 * - [AddCharacterDialog]：名字必填、性别可选（空 = 交给自动分配推断）。
 *   重名/空名校验在调用方，失败时**不关弹层**、反馈走行内状态。
 * - [EditCharacterDialog]：性别 / 年龄组 / 重要性三组胶囊 + 风格文本 + 别名增删。
 *
 * ## 拆分前的原状（别把这里当"新写的"）
 *
 * 两个弹层连同 [GenderChip] 原本都在 `MultiVoiceSettings.kt`（1980 行）里，与
 * 主列表、音色面板、MiMo 面板挤在一个文件。本次只做**搬移 + 可见性从 private 变
 * internal**，函数体逐行未改（`scripts/verify-pure-move.ps1` 机械比对确认）。
 *
 * **文案与确认逻辑零改动** —— 迁移/拆解过程中这一条最容易破，改动若涉及它们，
 * 必须单独提交并写明原因。
 *
 * ## 为什么 [VoicePickerTarget] 在这
 *
 * 它是「正在挑音色的对象」这一个纯数据类，被角色管理侧（选中某个角色去挑音色）
 * 和音色选择弹层共用。放在这里是因为角色行是它的主要生产者；音色选择弹层从
 * 本文件 import 它。
 */

/** 正在挑音色的对象：一个角色，或某个语言的旁白。 */
internal data class VoicePickerTarget(
    val speaker: String,
    val title: String,
    val language: String,
    val gender: String?,
    val narratorLanguage: String?
)

/**
 * 手动添加角色的弹层：名字必填，性别可选（空 = 未指定，交给自动分配推断）。
 * 确认时先做重名/空名校验，失败不关弹层，反馈走全局 Snackbar。
 */
@Composable
internal fun AddCharacterDialog(
    onConfirm: (name: String, gender: String) -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalDesignTokens.current
    var name by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.colors.cardSurface,
        title = { Text(stringResource(R.string.multivoice_add_character_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.multivoice_character_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(FieldGap))
                Text(
                    stringResource(R.string.multivoice_gender_label),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
                Spacer(Modifier.height(t.spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                    GenderChip(
                        label = stringResource(R.string.multivoice_gender_male),
                        selected = gender == "male",
                        onClick = { gender = if (gender == "male") "" else "male" }
                    )
                    GenderChip(
                        label = stringResource(R.string.multivoice_gender_female),
                        selected = gender == "female",
                        onClick = { gender = if (gender == "female") "" else "female" }
                    )
                    GenderChip(
                        label = stringResource(R.string.multivoice_gender_unknown),
                        selected = gender.isEmpty(),
                        onClick = { gender = "" }
                    )
                }
            }
        },
        confirmButton = {
            LinguaTextButton(
                text = stringResource(R.string.multivoice_add_character),
                onClick = { onConfirm(name, gender) }
            )
        },
        dismissButton = {
            LinguaTextButton(text = stringResource(R.string.common_cancel), onClick = onDismiss)
        }
    )
}

@Composable
internal fun GenderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

/**
 * 别名编辑弹层：列出已有别名（可删），输入新别名即时添加。
 * 增删立即写库并 Snackbar 反馈；重名/空名不关弹层。
 */
@Composable
internal fun EditCharacterDialog(
    character: VoiceCharacter,
    onSave: (gender: String, ageGroup: String, style: List<String>, importance: String) -> Unit,
    onAddAlias: (alias: String) -> Boolean,
    onRemoveAlias: (alias: String) -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalDesignTokens.current
    var draft by remember { mutableStateOf("") }
    // 属性状态：随目标角色切换重置。GenderChip 是通用三态胶囊（选中/未选中）。
    var gender by remember(character.name) { mutableStateOf(character.gender) }
    var ageGroup by remember(character.name) { mutableStateOf(character.ageGroup) }
    var importance by remember(character.name) { mutableStateOf(character.importance) }
    var styleText by remember(character.name) { mutableStateOf(character.style.joinToString(", ")) }
    val deleteTemplate = stringResource(R.string.multivoice_alias_delete, "%s")
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.colors.cardSurface,
        title = { Text(stringResource(R.string.multivoice_edit_character_title, character.name)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.multivoice_gender_label),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
                Spacer(Modifier.height(t.spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                    GenderChip(
                        label = stringResource(R.string.multivoice_gender_male),
                        selected = gender == "male",
                        onClick = { gender = if (gender == "male") "" else "male" }
                    )
                    GenderChip(
                        label = stringResource(R.string.multivoice_gender_female),
                        selected = gender == "female",
                        onClick = { gender = if (gender == "female") "" else "female" }
                    )
                    GenderChip(
                        label = stringResource(R.string.multivoice_gender_unknown),
                        selected = gender.isBlank(),
                        onClick = { gender = "" }
                    )
                }
                Spacer(Modifier.height(FieldGap))
                Text(
                    stringResource(R.string.multivoice_age_group_label),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
                Spacer(Modifier.height(t.spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                    GenderChip(
                        label = stringResource(R.string.multivoice_age_child),
                        selected = ageGroup == "child",
                        onClick = { ageGroup = if (ageGroup == "child") "" else "child" }
                    )
                    GenderChip(
                        label = stringResource(R.string.multivoice_age_young),
                        selected = ageGroup == "young",
                        onClick = { ageGroup = if (ageGroup == "young") "" else "young" }
                    )
                    GenderChip(
                        label = stringResource(R.string.multivoice_age_adult),
                        selected = ageGroup == "adult",
                        onClick = { ageGroup = if (ageGroup == "adult") "" else "adult" }
                    )
                    GenderChip(
                        label = stringResource(R.string.multivoice_age_elderly),
                        selected = ageGroup == "elderly",
                        onClick = { ageGroup = if (ageGroup == "elderly") "" else "elderly" }
                    )
                    GenderChip(
                        label = stringResource(R.string.multivoice_age_unknown),
                        selected = ageGroup.isBlank(),
                        onClick = { ageGroup = "" }
                    )
                }
                Spacer(Modifier.height(FieldGap))
                Text(
                    stringResource(R.string.multivoice_importance_label),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
                Spacer(Modifier.height(t.spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                    GenderChip(
                        label = stringResource(R.string.multivoice_role_major),
                        selected = importance == "major",
                        onClick = { importance = if (importance == "major") "" else "major" }
                    )
                    GenderChip(
                        label = stringResource(R.string.multivoice_role_medium),
                        selected = importance == "medium",
                        onClick = { importance = if (importance == "medium") "" else "medium" }
                    )
                    GenderChip(
                        label = stringResource(R.string.multivoice_role_minor),
                        selected = importance == "minor" || importance.isBlank(),
                        onClick = { importance = "minor" }
                    )
                }
                Spacer(Modifier.height(FieldGap))
                OutlinedTextField(
                    value = styleText,
                    onValueChange = { styleText = it },
                    label = { Text(stringResource(R.string.multivoice_style_label)) },
                    supportingText = { Text(stringResource(R.string.multivoice_style_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(SectionGap))
                HorizontalDivider(color = t.colors.outline)
                Spacer(Modifier.height(FieldGap))
                Text(
                    stringResource(R.string.multivoice_alias_subtitle),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
                Spacer(Modifier.height(t.spacing.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(stringResource(R.string.multivoice_alias_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    LinguaTextButton(
                        text = stringResource(R.string.multivoice_alias_add),
                        onClick = {
                            if (onAddAlias(draft)) draft = ""
                        }
                    )
                }
                Spacer(Modifier.height(LabelGap))
                if (character.aliases.isEmpty()) {
                    Text(
                        stringResource(R.string.multivoice_alias_empty),
                        style = t.typography.label,
                        color = t.colors.inkFaint
                    )
                } else {
                    character.aliases.forEach { alias ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                alias,
                                style = t.typography.caption,
                                color = t.colors.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onRemoveAlias(alias) },
                                modifier = Modifier.semantics {
                                    contentDescription = deleteTemplate.replace("%s", alias)
                                }
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = null,
                                    tint = t.colors.inkFaint,
                                    modifier = Modifier.size(SmallIconSize)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            LinguaTextButton(
                text = stringResource(R.string.multivoice_edit_save),
                onClick = {
                    val style = styleText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    onSave(gender, ageGroup, style, importance)
                }
            )
        },
        dismissButton = {
            LinguaTextButton(text = stringResource(R.string.common_cancel), onClick = onDismiss)
        }
    )
}