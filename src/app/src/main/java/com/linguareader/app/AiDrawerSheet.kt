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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.ai.BookGlossary
import com.linguareader.app.ai.DeepSeekTranslator
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
    val context = LocalContext.current
    var deepSeekEnabled by remember(settings) { mutableStateOf(settings.enabled) }
    var apiKey by remember(settings) { mutableStateOf(settings.apiKey) }
    var baseUrl by remember(settings) { mutableStateOf(settings.baseUrl) }
    var model by remember(settings) { mutableStateOf(settings.model) }
    var azureEnabled by remember(settings) { mutableStateOf(settings.azureTranslationEnabled) }
    var azureKey by remember(settings) { mutableStateOf(settings.azureKey) }
    var azureRegion by remember(settings) { mutableStateOf(settings.azureRegion) }
    var azureEndpoint by remember(settings) { mutableStateOf(settings.azureEndpoint) }
    // 「测试连接」的瞬态：用当前表单里的 Key/baseUrl/model 真的发一次请求，
    // 让用户能在保存前就发现坏 Key/坏端点，而不是被「已就绪」误导。
    val scope = rememberCoroutineScope()
    var testingConnection by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.aidrawer_deepseek_title),
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
                label = { Text(stringResource(R.string.aidrawer_base_url_label)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.aidrawer_model_label)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.aidrawer_deepseek_on_hint),
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        if (apiKey.trim().isEmpty()) {
                            testOk = false
                            testStatus = context.getString(R.string.aidrawer_deepseek_test_need_key)
                            return@TextButton
                        }
                        val draft = settings.copy(
                            enabled = deepSeekEnabled,
                            apiKey = apiKey.trim(),
                            baseUrl = baseUrl.trim(),
                            model = model.trim()
                        )
                        testingConnection = true
                        testStatus = null
                        scope.launch {
                            val outcome = runCatching { DeepSeekTranslator(draft).verifyConnection() }
                            val ok = context.getString(R.string.aidrawer_deepseek_test_ok)
                            testingConnection = false
                            outcome.fold(
                                onSuccess = { testOk = true; testStatus = ok },
                                onFailure = {
                                    testOk = false
                                    testStatus = context.getString(
                                        R.string.aidrawer_deepseek_test_fail,
                                        it.message ?: ""
                                    )
                                }
                            )
                        }
                    },
                    enabled = enabled && !testingConnection
                ) { Text(stringResource(R.string.aidrawer_deepseek_test)) }
                val statusText = when {
                    testingConnection -> stringResource(R.string.aidrawer_deepseek_testing)
                    testStatus != null -> testStatus.orEmpty()
                    else -> null
                }
                statusText?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            testingConnection -> InkSoft
                            testOk -> Success
                            else -> Danger
                        }
                    )
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.aidrawer_deepseek_off_hint),
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.aidrawer_azure_title),
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
                label = { Text(stringResource(R.string.aidrawer_azure_region_label)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = azureEndpoint,
                onValueChange = { azureEndpoint = it },
                label = { Text(stringResource(R.string.aidrawer_base_url_label)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.aidrawer_azure_on_hint),
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft
            )
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.aidrawer_azure_off_hint),
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
                        !enabled -> savedPowerOffText
                        updated.remoteReady -> savedReadyText
                        deepSeekEnabled -> savedLocalText
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
