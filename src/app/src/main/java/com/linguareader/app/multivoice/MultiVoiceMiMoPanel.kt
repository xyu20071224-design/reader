package com.linguareader.app.multivoice

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
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
import com.linguareader.app.SettingsStatus
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.tts.MiMoVoiceStore
import com.linguareader.app.tts.TtsLanguage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * MiMo 专属音色面板：预置音色之外的「设计 / 复刻」音色从这里造。
 *
 * ## 它做什么
 *
 * - **设计**：用一段自然语言描述音色（`mimo-v2.5-tts-voicedesign`）；
 * - **复刻**：导入 mp3/wav 样本克隆真实声音（`mimo-v2.5-tts-voiceclone`）；
 * - 建好后作为**普通音色库条目**出现在角色分配列表里，可试听、可删除、可换绑。
 *
 * ## 为什么面板与它的两个对话框必须同文件（Lead 的问题，答在这里）
 *
 * 那 2 个 `AlertDialog`（「设计音色」/「复刻音色」）**不是独立 Composable**，
 * 而是本面板内的 `if (designing) { AlertDialog(...) }` 分支，直接读写面板的局部状态：
 * `designName` / `designPrompt` / `designLanguage` / `designGender` /
 * `cloneName` / `cloneLanguage` / `cloneGender` / `cloningImporting`。
 *
 * 也就是说它们**持有状态**（不是纯展示）。按「持有状态就只抽面板不抽对话框」的规则，
 * 这里只能是**panel + 2 dialogs 一起搬** —— 拆开就要把 8 个状态上提，那是改行为。
 *
 * ## 与父级零耦合（已机械核对）
 *
 * 本文件**不读** `MultiVoiceSection` 的任何局部状态（`settings` / `bookId` / `library` /
 * `voiceMap` / `picker` … 一个都没有）。所有输入都走形参：`context` / `scope` /
 * `onChanged` / `notify` / `playingVoice` / `onAudition`。
 *
 * ⚠️ 唯一的副作用面是 [MiMoVoiceStore] 的读写与一次 SAF 打开（`OpenDocument`）——
 * 这两件事的**触发点（用户点击）与顺序完全保持原样**，拆解只搬位置。
 *
 * ## 拆分前原状
 *
 * 原本在 `MultiVoiceSettings.kt`（1980 行）末尾。本次只搬移 + 可见性
 * private -> internal，函数体逐行未改（`scripts/verify-pure-move.ps1` 机械比对确认）。
 */
/**
 * MiMo 专属音色面板（多角色服务）：预置音色之外的音色都从这里造——
 * 用一段自然语言描述「设计」专属音色（mimo-v2.5-tts-voicedesign），或导入
 * mp3/wav 样本「复刻」真实声音（mimo-v2.5-tts-voiceclone）。建好后作为普通
 * 音色库条目出现在下方角色分配列表里，可试听、可删除、可随时换绑。
 */
@Composable
internal fun MiMoCustomVoicesPanel(
    context: Context,
    scope: CoroutineScope,
    onChanged: () -> Unit,
    notify: (SettingsStatus) -> Unit,
    playingVoice: String?,
    onAudition: (MiMoVoiceStore.CustomVoice) -> Unit
) {
    val t = LocalDesignTokens.current
    var customVoices by remember { mutableStateOf(MiMoVoiceStore.installed(context)) }
    var designing by remember { mutableStateOf(false) }
    var cloning by remember { mutableStateOf(false) }
    var cloningImporting by remember { mutableStateOf(false) }
    var designName by remember { mutableStateOf("") }
    var designPrompt by remember { mutableStateOf("") }
    var designLanguage by remember { mutableStateOf(TtsLanguage.CHINESE) }
    var designGender by remember { mutableStateOf("female") }
    var cloneName by remember { mutableStateOf("") }
    var cloneLanguage by remember { mutableStateOf(TtsLanguage.CHINESE) }
    var cloneGender by remember { mutableStateOf("female") }

    fun refresh() {
        customVoices = MiMoVoiceStore.installed(context)
    }

    val clonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null || !cloning) return@rememberLauncherForActivityResult
        var temp: File? = null
        cloningImporting = true
        scope.launch {
            val result = runCatching {
                val mime = context.contentResolver.getType(uri)
                val ext = when {
                    mime.equals("audio/mpeg", true) || mime.equals("audio/mp3", true) -> "mp3"
                    mime.equals("audio/wav", true) || mime.equals("audio/x-wav", true) -> "wav"
                    else -> uri.lastPathSegment
                        ?.substringAfterLast('.')
                        ?.lowercase()
                        ?.takeIf { it == "mp3" || it == "wav" }
                        ?: "mp3"
                }
                temp = File(context.cacheDir, "mimo_clone_sample.$ext")
                temp!!.delete()
                val input = context.contentResolver.openInputStream(uri)
                    ?: error(context.getString(R.string.multivoice_mimo_clone_read_failed))
                input.use { reader ->
                    temp!!.outputStream().use { writer -> reader.copyTo(writer) }
                }
                MiMoVoiceStore.addClone(
                    context = context,
                    name = cloneName,
                    source = temp!!,
                    language = cloneLanguage,
                    gender = cloneGender
                ).getOrThrow()
            }
            temp?.delete()
            result.onSuccess { voice ->
                refresh()
                cloningImporting = false
                cloning = false
                notify(
                    SettingsStatus.success(
                        context.getString(R.string.multivoice_mimo_clone_imported, voice.name)
                    )
                )
                onChanged()
            }.onFailure {
                cloningImporting = false
                notify(
                    SettingsStatus.danger(
                        it.message ?: context.getString(R.string.multivoice_mimo_clone_failed)
                    )
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(MiMoPanelGap))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.multivoice_mimo_panel_title),
                style = t.typography.label,
                color = t.colors.inkSoft,
                modifier = Modifier.weight(1f)
            )
            LinguaTextButton(
                text = stringResource(R.string.multivoice_mimo_design_button),
                onClick = { designing = true }
            )
            LinguaTextButton(
                text = stringResource(R.string.multivoice_mimo_clone_button),
                onClick = { cloning = true }
            )
        }
        Text(
            stringResource(R.string.multivoice_mimo_panel_hint),
            style = t.typography.label,
            color = t.colors.inkFaint
        )
        if (customVoices.isEmpty()) {
            Text(
                stringResource(R.string.multivoice_mimo_empty),
                style = t.typography.label,
                color = t.colors.inkFaint
            )
        } else {
            customVoices.forEach { voice ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = RowVerticalGap),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            voice.name,
                            style = t.typography.caption,
                            color = t.colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            (if (voice.isDesign) {
                                stringResource(R.string.multivoice_mimo_kind_design)
                            } else {
                                stringResource(R.string.multivoice_mimo_kind_clone)
                            }) +
                                " · " +
                                (if (voice.language == TtsLanguage.CHINESE) {
                                    stringResource(R.string.multivoice_mimo_lang_zh)
                                } else {
                                    stringResource(R.string.multivoice_mimo_lang_en)
                                }) +
                                (if (voice.gender.isNotBlank()) {
                                    " · " + if (voice.gender == "male") {
                                        stringResource(R.string.multivoice_gender_male)
                                    } else {
                                        stringResource(R.string.multivoice_gender_female)
                                    }
                                } else {
                                    ""
                                }),
                            style = t.typography.label,
                            color = t.colors.inkFaint
                        )
                    }
                    val auditionLabel = if (playingVoice == voice.id) {
                        stringResource(R.string.multivoice_audition_stop)
                    } else {
                        stringResource(R.string.multivoice_audition, voice.name)
                    }
                    IconButton(
                        onClick = { onAudition(voice) },
                        modifier = Modifier.semantics {
                            contentDescription = auditionLabel
                        }
                    ) {
                        Icon(
                            if (playingVoice == voice.id) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = if (playingVoice == voice.id) t.colors.accent else t.colors.inkSoft,
                            modifier = Modifier.size(RowIconSize)
                        )
                    }
                    val deleteLabel = stringResource(R.string.multivoice_mimo_delete, voice.name)
                    IconButton(
                        onClick = {
                            MiMoVoiceStore.remove(context, voice.id)
                            refresh()
                            notify(
                                SettingsStatus.success(
                                    context.getString(R.string.multivoice_mimo_deleted, voice.name)
                                )
                            )
                            onChanged()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = deleteLabel
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

    if (designing) {
        AlertDialog(
            onDismissRequest = { designing = false; designName = ""; designPrompt = "" },
            containerColor = t.colors.cardSurface,
            title = { Text(stringResource(R.string.multivoice_mimo_design_title)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = designName,
                        onValueChange = { designName = it },
                        label = { Text(stringResource(R.string.multivoice_mimo_design_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(FieldGap))
                    Text(
                        stringResource(R.string.multivoice_mimo_design_language_label),
                        style = t.typography.label,
                        color = t.colors.inkSoft
                    )
                    Spacer(Modifier.height(t.spacing.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                        GenderPill(
                            label = stringResource(R.string.multivoice_mimo_lang_zh),
                            selected = designLanguage == TtsLanguage.CHINESE,
                            onSelect = { designLanguage = TtsLanguage.CHINESE }
                        )
                        GenderPill(
                            label = stringResource(R.string.multivoice_mimo_lang_en),
                            selected = designLanguage != TtsLanguage.CHINESE,
                            onSelect = { designLanguage = TtsLanguage.ENGLISH }
                        )
                    }
                    Spacer(Modifier.height(FieldGap))
                    Text(
                        stringResource(R.string.multivoice_gender_label),
                        style = t.typography.label,
                        color = t.colors.inkSoft
                    )
                    Spacer(Modifier.height(t.spacing.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                        GenderPill(
                            label = stringResource(R.string.multivoice_gender_female),
                            selected = designGender == "female",
                            onSelect = { designGender = "female" }
                        )
                        GenderPill(
                            label = stringResource(R.string.multivoice_gender_male),
                            selected = designGender == "male",
                            onSelect = { designGender = "male" }
                        )
                        GenderPill(
                            label = stringResource(R.string.multivoice_gender_unknown),
                            selected = designGender.isBlank(),
                            onSelect = { designGender = "" }
                        )
                    }
                    Spacer(Modifier.height(FieldGap))
                    OutlinedTextField(
                        value = designPrompt,
                        onValueChange = { designPrompt = it },
                        label = { Text(stringResource(R.string.multivoice_mimo_design_prompt_label)) },
                        supportingText = { Text(stringResource(R.string.multivoice_mimo_design_prompt_hint)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                LinguaTextButton(
                    text = stringResource(R.string.multivoice_mimo_design_create),
                    onClick = {
                        MiMoVoiceStore.addDesign(
                            context = context,
                            name = designName,
                            prompt = designPrompt,
                            language = designLanguage,
                            gender = designGender
                        ).onSuccess { voice ->
                            refresh()
                            designing = false
                            designName = ""
                            designPrompt = ""
                            notify(
                                SettingsStatus.success(
                                    context.getString(R.string.multivoice_mimo_design_created, voice.name)
                                )
                            )
                            onChanged()
                        }.onFailure {
                            notify(
                                SettingsStatus.danger(
                                    it.message ?: context.getString(R.string.multivoice_mimo_design_failed)
                                )
                            )
                        }
                    }
                )
            },
            dismissButton = {
                LinguaTextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { designing = false; designName = ""; designPrompt = "" }
                )
            }
        )
    }

    if (cloning) {
        AlertDialog(
            onDismissRequest = { if (!cloningImporting) cloning = false },
            containerColor = t.colors.cardSurface,
            title = { Text(stringResource(R.string.multivoice_mimo_clone_title)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = cloneName,
                        onValueChange = { cloneName = it },
                        label = { Text(stringResource(R.string.multivoice_mimo_clone_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(FieldGap))
                    Text(
                        stringResource(R.string.multivoice_mimo_design_language_label),
                        style = t.typography.label,
                        color = t.colors.inkSoft
                    )
                    Spacer(Modifier.height(t.spacing.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                        GenderPill(
                            label = stringResource(R.string.multivoice_mimo_lang_zh),
                            selected = cloneLanguage == TtsLanguage.CHINESE,
                            onSelect = { cloneLanguage = TtsLanguage.CHINESE }
                        )
                        GenderPill(
                            label = stringResource(R.string.multivoice_mimo_lang_en),
                            selected = cloneLanguage != TtsLanguage.CHINESE,
                            onSelect = { cloneLanguage = TtsLanguage.ENGLISH }
                        )
                    }
                    Spacer(Modifier.height(FieldGap))
                    Text(
                        stringResource(R.string.multivoice_gender_label),
                        style = t.typography.label,
                        color = t.colors.inkSoft
                    )
                    Spacer(Modifier.height(t.spacing.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                        GenderPill(
                            label = stringResource(R.string.multivoice_gender_female),
                            selected = cloneGender == "female",
                            onSelect = { cloneGender = "female" }
                        )
                        GenderPill(
                            label = stringResource(R.string.multivoice_gender_male),
                            selected = cloneGender == "male",
                            onSelect = { cloneGender = "male" }
                        )
                        GenderPill(
                            label = stringResource(R.string.multivoice_gender_unknown),
                            selected = cloneGender.isBlank(),
                            onSelect = { cloneGender = "" }
                        )
                    }
                    Spacer(Modifier.height(FieldGap))
                    // 复合「图标没有、但文案随状态切换」——LinguaTextButton 接受字符串，
                    // 正好覆盖；enabled 与文案都随导入状态变，交互语义零改动。
                    LinguaTextButton(
                        text = stringResource(
                            if (cloningImporting) {
                                R.string.multivoice_mimo_clone_importing
                            } else {
                                R.string.multivoice_mimo_clone_sample_button
                            }
                        ),
                        enabled = !cloningImporting,
                        onClick = {
                            clonePicker.launch(
                                arrayOf(
                                    "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/*"
                                )
                            )
                        }
                    )
                    Text(
                        stringResource(R.string.multivoice_mimo_clone_hint),
                        style = t.typography.label,
                        color = t.colors.inkFaint
                    )
                }
            },
            confirmButton = {
                LinguaTextButton(
                    text = stringResource(R.string.common_done),
                    enabled = !cloningImporting,
                    onClick = { cloning = false }
                )
            },
            dismissButton = {
                LinguaTextButton(
                    text = stringResource(R.string.common_cancel),
                    enabled = !cloningImporting,
                    onClick = { cloning = false }
                )
            }
        )
    }
}