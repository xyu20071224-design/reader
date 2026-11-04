package com.linguareader.app.multivoice

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.linguareader.app.R
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.tts.SystemTtsEngines
import com.linguareader.app.tts.SystemTtsVoices
import com.linguareader.app.tts.SystemVoiceAnnotation
import com.linguareader.app.tts.SystemVoiceInfo
import com.linguareader.app.tts.SystemVoiceStore

/**
 * **系统 TTS 引擎专属**的两个界面 + 它们共用的性别胶囊。
 *
 * ## 职责边界（这一组为什么能独立成文件）
 *
 * 它们只在 `settings.mode == SYSTEM` 时出现，并且只服务「系统语音」这一条路径：
 * - [SystemEngineGuidance]：三态引导（已推荐 / 可切换 / 未安装）。**纯提示，
 *   绝不自动切换引擎** —— 跳设置页的动作也是用户点了按钮才发生。
 * - [SystemVoiceAnnotateDialog]：给系统音色打「性别 + 是否启用」标注。多角色分配器
 *   靠这份标注做匹配，没有它系统引擎就进不了多角色模式（必须 ≥2 个可用音色）。
 * - [GenderPill]：标注弹层与 MiMo 面板共用的三态胶囊。
 *
 * 与云引擎（自建 / MiMo）那条路径零耦合：不读 `CloudTtsSettings` 的云字段、
 * 不碰 `VoiceLibrary`、不经过任何网络。唯一的落盘是 [SystemVoiceStore] 的音色标注
 * 快照 —— 这是本文件与存储层唯一的接触点，且只在**弹层打开时**发生（见下）。
 *
 * ## 时序不变量（拆解时特意保持原样）
 *
 * [SystemVoiceAnnotateDialog] 打开时会**先读已落盘快照，没有才现场探测并保存**：
 * - 探测为空时**绝不覆盖**已有快照（引擎可能只是还在加载音色表）；
 * - 探测只在 `LaunchedEffect(engine)` 里跑一次，不重试。
 *
 * 这条时序是历史修复的结果，拆文件时逐行照搬，一个字没改。
 *
 * ## 拆分前原状
 *
 * 原本都在 `MultiVoiceSettings.kt`（1980 行）里。本次只搬移 + 可见性
 * private -> internal，函数体逐行未改（`scripts/verify-pure-move.ps1` 机械比对确认）。
 */

/**
 * SYSTEM 模式三态引导（M5c, PLAN-MULTI-VOICE §13.5）：
 * 态1 当前就是 Google TTS → 推荐提示；态2 已装未启用 → 提示 + 跳系统 TTS 设置
 * （`ACTION_TTS_SETTINGS`，绝不自动切换）；态3 未安装 → 安装引导文案。
 */
@Composable
internal fun SystemEngineGuidance(onOpenSettingsFailed: () -> Unit) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val state = remember {
        SystemTtsEngines.guideState(
            currentEngine = SystemTtsVoices.currentEngine(context),
            googleTtsInstalled = SystemTtsEngines.isGoogleTtsInstalled(context)
        )
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            when (state) {
                SystemTtsEngines.Guide.RECOMMENDED ->
                    stringResource(R.string.multi_voice_system_engine_recommended)
                SystemTtsEngines.Guide.SWITCH_AVAILABLE ->
                    stringResource(R.string.multi_voice_system_switch_hint)
                SystemTtsEngines.Guide.NOT_INSTALLED ->
                    stringResource(R.string.multi_voice_system_not_installed)
            },
            style = t.typography.label,
            color = t.colors.inkSoft
        )
        if (state == SystemTtsEngines.Guide.SWITCH_AVAILABLE) {
            // startActivity 需在非 Activity context 下加 NEW_TASK；失败静默降级为 Snackbar。
            LinguaTextButton(
                text = stringResource(R.string.multi_voice_system_open_settings),
                onClick = {
                    runCatching {
                        // Settings.ACTION_TTS_SETTINGS 未进公开 SDK，用其字面值。
                        context.startActivity(
                            Intent("com.android.settings.TTS_SETTINGS")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { onOpenSettingsFailed() }
                }
            )
        }
    }
}

/** 一行系统音色的标注草稿（PLAN-MULTI-VOICE §13.6）。 */
internal data class SystemVoiceDraft(
    val gender: String = "",
    val enabled: Boolean = true
)

/**
 * 系统音色标注弹层（M5b, §13.6）。
 *
 * 打开时优先用已落盘的快照；没有快照才现场探测一次并保存（探测为空 = 引擎还在
 * 加载音色表，展示加载/空态即可，不做复杂重试）。每行：音色名 + 性别三态 +
 * 启用开关 + 逐音色试听。保存把整份草稿按 voiceName 覆盖写回标注存储。
 */
@Composable
internal fun SystemVoiceAnnotateDialog(
    playingVoice: String?,
    onAudition: (SystemVoiceInfo) -> Unit,
    onSave: (List<SystemVoiceAnnotation>) -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val engine = remember { SystemTtsVoices.currentEngine(context) }
    var voices by remember { mutableStateOf<List<SystemVoiceInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(engine) {
        val cached = if (engine.isBlank()) emptyList() else {
            SystemVoiceStore.loadSnapshot(context, engine).voices
        }
        voices = if (cached.isNotEmpty()) {
            cached
        } else {
            runCatching { SystemTtsVoices.probe(context) }.getOrDefault(emptyList())
                .also { probed ->
                    // 探测成功才落盘；空结果绝不覆盖已有快照。
                    if (probed.isNotEmpty() && engine.isNotBlank()) {
                        SystemVoiceStore.saveSnapshot(
                            context,
                            SystemVoiceStore.Snapshot(engine, probed)
                        )
                    }
                }
        }
        loading = false
    }

    val drafts = remember(engine) {
        mutableStateMapOf<String, SystemVoiceDraft>().apply {
            if (engine.isNotBlank()) {
                SystemVoiceStore.loadAnnotations(context, engine).forEach { annotation ->
                    put(annotation.voiceName, SystemVoiceDraft(annotation.gender, annotation.enabled))
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.colors.cardSurface,
        title = { Text(stringResource(R.string.multi_voice_annotate_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    engine.isBlank() -> Text(
                        stringResource(R.string.multi_voice_annotate_no_engine),
                        style = t.typography.caption,
                        color = t.colors.inkSoft
                    )

                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = t.colors.accent, modifier = Modifier.size(MediumIconSize))
                        Spacer(Modifier.width(FieldGap))
                        Text(
                            stringResource(R.string.multi_voice_annotate_loading),
                            style = t.typography.caption,
                            color = t.colors.inkSoft
                        )
                    }

                    voices.isEmpty() -> Text(
                        stringResource(R.string.multi_voice_annotate_empty),
                        style = t.typography.caption,
                        color = t.colors.inkSoft
                    )

                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = AnnotateListMaxHeight)) {
                        items(voices, key = { it.name }) { voice ->
                            val draft = drafts[voice.name] ?: SystemVoiceDraft()
                            // semantics{} 不是 composable 作用域，标签先取出来。
                            val auditionLabel =
                                stringResource(R.string.multivoice_audition, voice.name)
                            Column(Modifier.fillMaxWidth().padding(vertical = AnnotateRowVerticalPadding)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        voice.displayName(),
                                        style = t.typography.caption,
                                        color = t.colors.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { onAudition(voice) },
                                        modifier = Modifier.semantics {
                                            contentDescription = auditionLabel
                                        }
                                    ) {
                                        Icon(
                                            if (playingVoice == voice.name) {
                                                Icons.Default.Stop
                                            } else {
                                                Icons.Default.PlayArrow
                                            },
                                            contentDescription = null,
                                            tint = if (playingVoice == voice.name) t.colors.accent else t.colors.inkSoft,
                                            modifier = Modifier.size(RowIconSize)
                                        )
                                    }
                                    Switch(
                                        checked = draft.enabled,
                                        onCheckedChange = {
                                            drafts[voice.name] = draft.copy(enabled = it)
                                        }
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                                    GenderPill(
                                        label = stringResource(R.string.multivoice_gender_male),
                                        selected = draft.gender == "male",
                                        onSelect = { drafts[voice.name] = draft.copy(gender = "male") }
                                    )
                                    GenderPill(
                                        label = stringResource(R.string.multivoice_gender_female),
                                        selected = draft.gender == "female",
                                        onSelect = { drafts[voice.name] = draft.copy(gender = "female") }
                                    )
                                    GenderPill(
                                        label = stringResource(R.string.multi_voice_gender_unknown),
                                        selected = draft.gender.isEmpty(),
                                        onSelect = { drafts[voice.name] = draft.copy(gender = "") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            LinguaTextButton(
                text = stringResource(R.string.multi_voice_annotate_save),
                enabled = !loading && !engine.isBlank() && voices.isNotEmpty(),
                onClick = {
                    onSave(
                        voices.mapNotNull { voice ->
                            drafts[voice.name]?.let {
                                SystemVoiceAnnotation(voice.name, it.gender, it.enabled)
                            }
                        }
                    )
                }
            )
        },
        dismissButton = {
            LinguaTextButton(text = stringResource(R.string.common_cancel), onClick = onDismiss)
        }
    )
}

/** 性别三态小胶囊：选中反色，未选中淡底。 */
@Composable
internal fun GenderPill(label: String, selected: Boolean, onSelect: () -> Unit) {
    val t = LocalDesignTokens.current
    Text(
        label,
        style = t.typography.label,
        color = if (selected) t.colors.cardSurface else t.colors.inkSoft,
        modifier = Modifier
            .clip(t.pillShape)
            .background(if (selected) t.colors.accent else t.colors.ink.copy(alpha = PillWashAlpha))
            .clickable(onClick = onSelect)
            .padding(horizontal = PillHorizontalPadding, vertical = t.spacing.xs)
    )
}