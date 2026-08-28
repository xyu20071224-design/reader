package com.linguareader.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linguareader.app.ai.AiProviderProfile
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.ai.BookGlossary
import com.linguareader.app.ai.GlossaryEntry
import com.linguareader.app.data.Book
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.TtsPlaybackController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * "AI 中心"底部抽屉：一个入口集中管理所有 AI 能力。
 *
 *  - 头部是联网 AI 总开关：关闭后翻译（模型服务）与云端朗读
 *    （Azure/火山/自建）全部回到离线模式，本地词典、本地 Piper 不受影响。
 *  - 三个 Tab：翻译设置 / 听书语音（复用 [ListeningSettingsBody]）/ 术语表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiDrawerSheet(
    books: List<Book>,
    aiSettings: AiSettings,
    onAiSettingsChange: (AiSettings) -> Unit,
    onLoadGlossary: suspend (String) -> BookGlossary,
    onAddGlossary: suspend (String, String, String) -> BookGlossary,
    onUpdateGlossary: suspend (String, GlossaryEntry) -> BookGlossary,
    onRemoveGlossary: suspend (String, String) -> BookGlossary,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }

    fun setPower(on: Boolean) {
        onAiSettingsChange(aiSettings.copy(powerEnabled = on))
        val tts = CloudTtsSettings.load(context).copy(networkAiEnabled = on)
        CloudTtsSettings.save(context, tts)
        TtsPlaybackController.onCloudSettingsChanged(context)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 30.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.aidrawer_title), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.aidrawer_power_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSoft
                    )
                }
                Switch(
                    checked = aiSettings.powerEnabled,
                    onCheckedChange = ::setPower
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DrawerTab(stringResource(R.string.aidrawer_tab_translation), tab == 0) { tab = 0 }
                DrawerTab(stringResource(R.string.aidrawer_tab_tts), tab == 1) { tab = 1 }
                DrawerTab(stringResource(R.string.shelf_glossary), tab == 2) { tab = 2 }
            }
            HorizontalDivider(color = Ink.copy(alpha = .1f))
            Spacer(Modifier.height(12.dp))
            // Each tab scrolls itself so nested scrollers never fight.
            Box(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                when (tab) {
                    0 -> AiTranslationSettingsBody(
                        settings = aiSettings,
                        onSave = onAiSettingsChange,
                        enabled = aiSettings.powerEnabled
                    )
                    1 -> ListeningSettingsBody(onDismiss = null, onSaved = {}, books = books)
                    else -> GlossaryTabBody(
                        books = books,
                        onLoad = onLoadGlossary,
                        onAdd = onAddGlossary,
                        onUpdate = onUpdateGlossary,
                        onRemove = onRemoveGlossary
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerTab(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            color = if (selected) Accent else InkSoft,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * 翻译设置（模型服务商）。服务商列表/编辑卡都是草稿态，
 * 统一走底部「保存」落盘并镜像生效服务商回旧字段；[enabled] 为 false 时
 * （总开关关闭）禁用所有联网字段。
 */
@Composable
private fun AiTranslationSettingsBody(
    settings: AiSettings,
    onSave: (AiSettings) -> Unit,
    enabled: Boolean
) {
    var remoteEnabled by remember(settings) { mutableStateOf(settings.enabled) }
    var providers by remember(settings) { mutableStateOf(settings.providers) }
    var activeId by remember(settings) { mutableStateOf(settings.activeProviderId) }
    var editorDraft by remember { mutableStateOf<AiProviderProfile?>(null) }
    var editorIsNew by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.aidrawer_remote_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = remoteEnabled,
                onCheckedChange = { remoteEnabled = it },
                enabled = enabled
            )
        }
        if (remoteEnabled) {
            Spacer(Modifier.height(8.dp))
            ProviderSettingsBody(
                providers = providers,
                activeId = activeId,
                masterEnabled = enabled,
                onSelectActive = { activeId = it },
                onEdit = {
                    editorDraft = it
                    editorIsNew = false
                },
                onAdd = {
                    editorDraft = AiProviderProfile(id = "")
                    editorIsNew = true
                }
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.aidrawer_remote_on_hint),
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.aidrawer_remote_off_hint),
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft
            )
        }
        Spacer(Modifier.height(20.dp))
        // 保存后必须有明确反馈：此前点「保存」界面毫无变化，用户会以为没响应。
        // 抽屉是独立窗口（全局 Snackbar 会被它遮住），所以确认信息放在行内。
        var savedNotice by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(savedNotice) {
            if (savedNotice != null) {
                delay(3000)
                savedNotice = null
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            savedNotice?.let {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Success,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = Success
                    )
                }
            } ?: Spacer(Modifier.weight(1f))
            // onClick 不是 @Composable 作用域，四种「保存后会发生什么」文案先取出来。
            val savedPowerOffText = stringResource(R.string.aidrawer_saved_power_off)
            val savedReadyText = stringResource(R.string.aidrawer_saved_ready)
            val savedLocalText = stringResource(R.string.aidrawer_saved_local)
            val savedOffText = stringResource(R.string.aidrawer_saved_off)
            Button(
                onClick = {
                    val updated = settings.copy(
                        enabled = remoteEnabled,
                        providers = providers,
                        activeProviderId = activeId
                    ).withActiveMirrored()
                    onSave(updated)
                    // 说清「保存后会发生什么」，而不是只说“已保存”。
                    savedNotice = when {
                        !enabled -> savedPowerOffText
                        updated.remoteReady -> savedReadyText
                        remoteEnabled -> savedLocalText
                        else -> savedOffText
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = OnAccent
                ),
                shape = PillShape
            ) { Text(stringResource(R.string.common_save)) }
        }
    }
    editorDraft?.let { draft ->
        ProviderEditorDialog(
            initial = draft,
            isNew = editorIsNew,
            masterEnabled = enabled,
            onSave = { saved ->
                if (saved.id.isBlank()) {
                    val withId = saved.copy(id = UUID.randomUUID().toString())
                    providers = providers + withId
                    if (providers.none { it.id == activeId }) activeId = withId.id
                } else {
                    providers = providers.map { if (it.id == saved.id) saved else it }
                }
                editorDraft = null
            },
            onDelete = if (editorIsNew) {
                null
            } else {
                { target ->
                    providers = providers.filterNot { it.id == target.id }
                    if (activeId == target.id) activeId = providers.firstOrNull()?.id.orEmpty()
                    editorDraft = null
                }
            },
            onDismiss = { editorDraft = null }
        )
    }
}

/** Per-book glossary tab: delegates to the shared editor (also used by the shelf dialog). */
@Composable
private fun GlossaryTabBody(
    books: List<Book>,
    onLoad: suspend (String) -> BookGlossary,
    onAdd: suspend (String, String, String) -> BookGlossary,
    onUpdate: suspend (String, GlossaryEntry) -> BookGlossary,
    onRemove: suspend (String, String) -> BookGlossary
) {
    GlossaryEditorBody(
        books = books,
        lockedBookId = null,
        onLoad = onLoad,
        onAdd = onAdd,
        onUpdate = onUpdate,
        onRemove = onRemove
    )
}
