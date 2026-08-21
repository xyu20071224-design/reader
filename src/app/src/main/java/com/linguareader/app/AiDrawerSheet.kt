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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.ai.BookGlossary
import com.linguareader.app.ai.GlossaryEntry
import com.linguareader.app.data.Book
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.TtsPlaybackController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "AI 中心"底部抽屉：一个入口集中管理所有 AI 能力。
 *
 *  - 头部是联网 AI 总开关：关闭后翻译（DeepSeek/Azure）与云端朗读
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
                    Text("AI 中心", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "联网 AI 总开关：关闭后翻译与云端朗读全部离线。",
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
                DrawerTab("翻译", tab == 0) { tab = 0 }
                DrawerTab("听书语音", tab == 1) { tab = 1 }
                DrawerTab("术语表", tab == 2) { tab = 2 }
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
 * Translation settings (DeepSeek contextual AI + Azure sentence translator).
 * Mirrors the former Bookshelf AI dialog; [enabled] is false while the master
 * power switch is off, disabling every networked field.
 */
@Composable
private fun AiTranslationSettingsBody(
    settings: AiSettings,
    onSave: (AiSettings) -> Unit,
    enabled: Boolean
) {
    var deepSeekEnabled by remember(settings) { mutableStateOf(settings.enabled) }
    var apiKey by remember(settings) { mutableStateOf(settings.apiKey) }
    var baseUrl by remember(settings) { mutableStateOf(settings.baseUrl) }
    var model by remember(settings) { mutableStateOf(settings.model) }
    var azureEnabled by remember(settings) { mutableStateOf(settings.azureTranslationEnabled) }
    var azureKey by remember(settings) { mutableStateOf(settings.azureKey) }
    var azureRegion by remember(settings) { mutableStateOf(settings.azureRegion) }
    var azureEndpoint by remember(settings) { mutableStateOf(settings.azureEndpoint) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "DeepSeek 语境翻译",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = deepSeekEnabled,
                onCheckedChange = { deepSeekEnabled = it },
                enabled = enabled
            )
        }
        if (deepSeekEnabled) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("DeepSeek API Key") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("接口地址") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "启用后，书籍章节文本与点击的词句会发送到 DeepSeek 以生成/更新本书语境档案；" +
                    "未填 API Key 时自动使用不联网的本地轻量语境。",
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "关闭时查词保持纯本地词典，不发送任何文本。",
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Azure 整句翻译",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = azureEnabled,
                onCheckedChange = { azureEnabled = it },
                enabled = enabled
            )
        }
        if (azureEnabled) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = azureKey,
                onValueChange = { azureKey = it },
                label = { Text("Azure Translator Key") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = azureRegion,
                onValueChange = { azureRegion = it },
                label = { Text("区域（如 eastasia，可留空）") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = azureEndpoint,
                onValueChange = { azureEndpoint = it },
                label = { Text("接口地址") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "整句翻译会把当前句发送到 Azure AI Translator；本书术语表条目会用动态词典标记" +
                    "（<mstrans:dictionary>）随请求生效，保证专名译法一致。",
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft
            )
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                "关闭时查词面板不显示整句翻译。",
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
            Button(
                onClick = {
                    val updated = settings.copy(
                        enabled = deepSeekEnabled,
                        apiKey = apiKey.trim(),
                        baseUrl = baseUrl.trim(),
                        model = model.trim(),
                        azureTranslationEnabled = azureEnabled,
                        azureKey = azureKey.trim(),
                        azureRegion = azureRegion.trim(),
                        azureEndpoint = azureEndpoint.trim()
                    )
                    onSave(updated)
                    // 说清「保存后会发生什么」，而不是只说“已保存”。
                    savedNotice = when {
                        !enabled -> "已保存（联网 AI 总开关关闭，暂不生效）"
                        updated.remoteReady -> "已保存：DeepSeek 已就绪，打开书即可生成语境档案"
                        deepSeekEnabled -> "已保存：未填 API Key，将使用本地轻量语境"
                        else -> "已保存：AI 语境已关闭，查词保持纯本地"
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = OnAccent
                ),
                shape = PillShape
            ) { Text("保存") }
        }
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
