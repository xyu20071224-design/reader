package com.linguareader.app

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.net.toUri
import androidx.compose.ui.unit.dp
import com.linguareader.app.data.Book
import com.linguareader.app.tts.AzureSpeechClient
import com.linguareader.app.tts.AzureVoice
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.CloudVoicePicker
import com.linguareader.app.tts.CloudVoiceStore
import com.linguareader.app.tts.OpenAiCompatTtsBackend
import com.linguareader.app.tts.PiperVoice
import com.linguareader.app.tts.PiperVoiceCatalog
import com.linguareader.app.tts.PiperVoiceImporter
import com.linguareader.app.tts.PiperVoiceStore
import com.linguareader.app.tts.SystemTtsVoices
import com.linguareader.app.tts.SystemVoiceInfo
import com.linguareader.app.tts.TtsEngineMode
import com.linguareader.app.tts.TtsPlaybackController
import com.linguareader.app.tts.VolcanoTtsBackend
import kotlinx.coroutines.launch
import java.io.File

/** Settings for the optional cloud TTS engines (F-151). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListeningSettingsSheet(onDismiss: () -> Unit, book: Book? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        ListeningSettingsBody(onDismiss = onDismiss, onSaved = onDismiss, book = book)
    }
}

/** 行内反馈的语义（决定颜色），与文案解耦——禁止再用文案前缀判断样式。 */
internal enum class StatusTone { SUCCESS, DANGER, NEUTRAL }

/** 听书设置的行内状态：文本 + 显式语义。 */
internal data class SettingsStatus(
    val text: String,
    val tone: StatusTone = StatusTone.NEUTRAL
) {
    companion object {
        fun success(text: String) = SettingsStatus(text, StatusTone.SUCCESS)
        fun danger(text: String) = SettingsStatus(text, StatusTone.DANGER)
        fun info(text: String) = SettingsStatus(text, StatusTone.NEUTRAL)
    }
}

/**
 * Reusable body of the listening settings (engine / voices / speed / test).
 * Shared by the reader's quick sheet and the AI drawer's "听书语音" tab.
 *
 * @param onDismiss optional dismiss callback; when null the cancel button is hidden.
 * @param onSaved invoked after a successful save (e.g. close the sheet).
 * @param books shelf books, so the multi-voice section can pick one (AI drawer).
 * @param book the book being read, if any; its characters are shown directly.
 */
@Composable
internal fun ListeningSettingsBody(
    onDismiss: (() -> Unit)?,
    onSaved: () -> Unit,
    books: List<Book> = emptyList(),
    book: Book? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(CloudTtsSettings.load(context)) }
    var voices by remember { mutableStateOf(CloudVoiceStore.load(context)) }
    var systemVoices by remember { mutableStateOf<List<SystemVoiceInfo>>(emptyList()) }
    var systemVoicesLoaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<SettingsStatus?>(null) }
    var piperVoices by remember { mutableStateOf(PiperVoiceStore.installed(context)) }
    var piperStatus by remember { mutableStateOf<SettingsStatus?>(null) }
    var piperImporting by remember { mutableStateOf(false) }
    val onnxPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 模型有 30–90 MB：复制与校验都在 IO 线程做，主线程只更新状态。
        piperImporting = true
        piperStatus = SettingsStatus.info("正在导入并校验模型…")
        scope.launch {
            PiperVoiceImporter.import(context, uri)
                .onSuccess { voice ->
                    piperVoices = PiperVoiceStore.installed(context)
                    settings = settings.copy(piperEnVoiceId = voice.id)
                    piperStatus = SettingsStatus.success("已导入 ${voice.id}，保存后即可使用")
                }
                .onFailure { piperStatus = SettingsStatus.danger(it.message ?: "导入失败") }
            piperImporting = false
        }
    }

    fun loadSystemVoices(refresh: Boolean = false) {
        if (systemVoicesLoaded && !refresh) return
        status = null
        SystemTtsVoices.load(context) { list ->
            systemVoices = list
            systemVoicesLoaded = true
            status = if (list.isEmpty()) {
                SettingsStatus.info("未找到可用的系统音色，将使用系统默认")
            } else {
                SettingsStatus.success("已获取 ${list.size} 个系统音色")
            }
        }
    }

    fun fetchAzureVoices() {
        if (settings.region.isBlank() || settings.apiKey.isBlank()) {
            status = SettingsStatus.danger("请先填写 Region 和 API Key")
            return
        }
        busy = true
        status = null
        scope.launch {
            AzureSpeechClient(settings.region, settings.apiKey).listVoices()
                .onSuccess { list ->
                    voices = list
                    CloudVoiceStore.save(context, list)
                    val multilingual = CloudVoicePicker.defaultMultilingual(list)
                    settings = settings.copy(
                        enVoice = settings.enVoice.ifBlank { CloudVoicePicker.defaultEnglish(list) },
                        zhVoice = settings.zhVoice.ifBlank { CloudVoicePicker.defaultChinese(list) },
                        multilingualVoice = settings.multilingualVoice
                            .ifBlank { multilingual.orEmpty() },
                        useMultilingual = if (multilingual != null) settings.useMultilingual else false
                    )
                    status = SettingsStatus.success("已获取 ${list.size} 个可用音色")
                }
                .onFailure { status = SettingsStatus.danger(it.message ?: "获取音色失败，请检查 Region 与 Key") }
            busy = false
        }
    }

    /** BUG-012: actually synthesize a probe clip — listing voices proves the
     *  key but not that speech synthesis itself works. */
    fun testAzure() {
        if (settings.region.isBlank() || settings.apiKey.isBlank()) {
            status = SettingsStatus.danger("请先填写 Region 和 API Key")
            return
        }
        val voice = when {
            settings.useMultilingual && settings.multilingualVoice.isNotBlank() ->
                settings.multilingualVoice
            settings.zhVoice.isNotBlank() -> settings.zhVoice
            else -> settings.enVoice
        }
        if (voice.isBlank()) {
            status = SettingsStatus.danger("请先获取可用音色并选择音色")
            return
        }
        busy = true
        status = null
        scope.launch {
            val probe = File(context.cacheDir, "tts_probe_azure.mp3")
            AzureSpeechClient(settings.region, settings.apiKey)
                .synthesize("测试。Test.", voice, probe)
                .onSuccess {
                    probe.delete()
                    status = SettingsStatus.success("连接成功，Azure 可正常合成")
                }
                .onFailure {
                    probe.delete()
                    status = SettingsStatus.danger(it.message ?: "连接失败")
                }
            busy = false
        }
    }

    fun testServer() {
        if (!settings.isConfigured) {
            status = SettingsStatus.danger("请先填写服务器地址")
            return
        }
        busy = true
        status = null
        scope.launch {
            val backend = OpenAiCompatTtsBackend(settings)
            val probe = File(context.cacheDir, "tts_probe.mp3")
            backend.synthesize("测试。Test.", backend.voiceFor("测试。Test."), probe)
                .onSuccess {
                    probe.delete()
                    status = SettingsStatus.success("连接成功，服务器可正常合成")
                }
                .onFailure { status = SettingsStatus.danger(it.message ?: "连接失败") }
            busy = false
        }
    }

    fun testVolcano() {
        if (!settings.isConfigured) {
            status = SettingsStatus.danger("请填写 API Key，或 App ID + Access Token")
            return
        }
        busy = true
        status = null
        scope.launch {
            val backend = VolcanoTtsBackend(settings)
            val zhProbe = File(context.cacheDir, "tts_probe_zh.mp3")
            val enProbe = File(context.cacheDir, "tts_probe_en.mp3")
            val zh = backend.synthesize(
                "你好，世界。",
                backend.voiceFor("你好，世界。"),
                zhProbe
            )
            val en = backend.synthesize(
                "Hello world.",
                backend.voiceFor("Hello world."),
                enProbe
            )
            zhProbe.delete()
            enProbe.delete()
            if (zh.isSuccess && en.isSuccess) {
                status = SettingsStatus.success("连接成功，中英文音色均可合成")
            } else {
                status = SettingsStatus.danger(
                    (zh.exceptionOrNull() ?: en.exceptionOrNull())?.message ?: "连接失败"
                )
            }
            busy = false
        }
    }

    fun save() {
        if (settings.mode != TtsEngineMode.SYSTEM && !settings.isConfigured) {
            status = SettingsStatus.danger("启用云 TTS 前请先完成对应配置")
            return
        }
        CloudTtsSettings.save(context, settings)
        TtsPlaybackController.onCloudSettingsChanged(context)
        status = SettingsStatus.success("已保存")
        onSaved()
    }

    LaunchedEffect(settings.mode) {
        status = null
        if (settings.mode == TtsEngineMode.SYSTEM) loadSystemVoices()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 30.dp)
    ) {
        Text("听书设置", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(14.dp))
            Text("朗读引擎", style = MaterialTheme.typography.labelLarge, color = InkSoft)
            Row(verticalAlignment = Alignment.CenterVertically) {
                EngineChoice(
                    label = "系统语音",
                    selected = settings.mode == TtsEngineMode.SYSTEM,
                    onSelect = { settings = settings.copy(mode = TtsEngineMode.SYSTEM) }
                )
                Spacer(Modifier.weight(1f))
                EngineChoice(
                    label = "本地 Piper",
                    selected = settings.mode == TtsEngineMode.PIPER,
                    onSelect = { settings = settings.copy(mode = TtsEngineMode.PIPER) }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                EngineChoice(
                    label = "Azure 云 TTS",
                    selected = settings.mode == TtsEngineMode.AZURE,
                    onSelect = { settings = settings.copy(mode = TtsEngineMode.AZURE) }
                )
                Spacer(Modifier.weight(1f))
                EngineChoice(
                    label = "火山引擎（豆包语音）",
                    selected = settings.mode == TtsEngineMode.VOLC,
                    onSelect = { settings = settings.copy(mode = TtsEngineMode.VOLC) }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                EngineChoice(
                    label = "自建服务器（OpenAI 兼容）",
                    selected = settings.mode == TtsEngineMode.OPENAI_COMPAT,
                    onSelect = { settings = settings.copy(mode = TtsEngineMode.OPENAI_COMPAT) }
                )
            }

            if (settings.mode == TtsEngineMode.PIPER) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "本地 Piper 语音：内置离线神经语音，无需联网、无需 API Key；" +
                        "自动按中英文切换。英文音色可选内置或导入的 Piper 模型。" +
                        "支持多角色听书（完全离线；最多驻留 4 个英文音色，超出按最近使用淘汰）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint
                )
                Spacer(Modifier.height(10.dp))
                PiperVoiceDropdown(
                    voices = piperVoices,
                    selectedId = settings.piperEnVoiceId,
                    onSelect = { settings = settings.copy(piperEnVoiceId = it) }
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Ink.copy(alpha = .1f))
                Spacer(Modifier.height(10.dp))
                Text("下载更多 Piper 音色", style = MaterialTheme.typography.labelLarge, color = InkSoft)
                Text(
                    "点「下载」跳到官方页面下载模型包，再点「导入」选择其中的 .onnx 文件即可离线使用。" +
                        "注意：「试听」与「下载」需要联网（HuggingFace / GitHub），受顶部「联网 AI 总开关」控制；" +
                        "导入完成后的朗读依然完全离线。",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { onnxPicker.launch(arrayOf("*/*")) },
                    enabled = !piperImporting
                ) {
                    Text(if (piperImporting) "正在导入…" else "导入模型（选择 .onnx 文件）")
                }
                piperStatus?.let {
                    Text(
                        it.text,
                        style = MaterialTheme.typography.labelMedium,
                        color = when (it.tone) {
                            StatusTone.SUCCESS -> Success
                            StatusTone.DANGER -> Danger
                            StatusTone.NEUTRAL -> InkFaint
                        }
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (!settings.networkAiEnabled) {
                    Text(
                        "联网 AI 总开关已关闭：试听与下载暂不可用（已导入的音色仍可离线使用）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Danger
                    )
                }
                PiperVoiceCatalog.downloadable.forEach { voice ->
                    PiperCatalogRow(
                        voice = voice,
                        networkEnabled = settings.networkAiEnabled,
                        onMessage = { piperStatus = SettingsStatus.danger(it) }
                    )
                }
            }

            if (settings.mode == TtsEngineMode.SYSTEM) {
                Spacer(Modifier.height(16.dp))
                Text("系统音色", style = MaterialTheme.typography.labelLarge, color = InkSoft)
                Spacer(Modifier.height(4.dp))
                Text(
                    "选择手机系统 TTS 引擎中的音色；不选则跟随系统默认。" +
                        "中文和英文可分别指定。",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { loadSystemVoices(refresh = true) }) {
                    Text(if (systemVoices.isEmpty()) "加载音色" else "刷新音色")
                }
                status?.let {
                    Text(
                        it.text,
                        color = when (it.tone) {
                            StatusTone.SUCCESS -> Success
                            StatusTone.DANGER -> Danger
                            StatusTone.NEUTRAL -> InkFaint
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (systemVoices.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    // Fall back to the full list when the engine's locale codes
                    // don't map to zh/en at all (some domestic engines), so the
                    // dropdowns still offer the voices instead of only
                    // "跟随系统默认".
                    val chineseVoices = systemVoices.filter { it.isChinese }
                    val englishVoices = systemVoices.filter { it.isEnglish }
                    val hasLanguageSplit = chineseVoices.isNotEmpty() || englishVoices.isNotEmpty()
                    SystemVoiceDropdown(
                        label = "中文音色",
                        voices = if (hasLanguageSplit) chineseVoices else systemVoices,
                        selected = settings.systemZhVoice,
                        onSelect = { settings = settings.copy(systemZhVoice = it) }
                    )
                    Spacer(Modifier.height(6.dp))
                    SystemVoiceDropdown(
                        label = "英文音色",
                        voices = if (hasLanguageSplit) englishVoices else systemVoices,
                        selected = settings.systemEnVoice,
                        onSelect = { settings = settings.copy(systemEnVoice = it) }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已隐藏需要联网下载的音色，确保离线可正常朗读。",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint
                    )
                }
            }

            if (settings.mode == TtsEngineMode.AZURE) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = settings.region,
                    onValueChange = { settings = settings.copy(region = it.trim()) },
                    label = { Text("Region") },
                    supportingText = { Text("中国北部 3：chinanorth3") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = { settings = settings.copy(apiKey = it.trim()) },
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = ::fetchAzureVoices, enabled = !busy) {
                        Text("获取可用音色")
                    }
                    TextButton(onClick = ::testAzure, enabled = !busy) {
                        Text("测试连接")
                    }
                }
                status?.let {
                    Text(
                        it.text,
                        color = when (it.tone) {
                            StatusTone.SUCCESS -> Success
                            StatusTone.DANGER -> Danger
                            StatusTone.NEUTRAL -> InkFaint
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                if (voices.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = Ink.copy(alpha = .1f))
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("中英混读音色", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "同一个声音朗读中英文，混排更连贯",
                                style = MaterialTheme.typography.labelSmall,
                                color = InkFaint
                            )
                        }
                        Switch(
                            checked = settings.useMultilingual,
                            onCheckedChange = {
                                settings = settings.copy(useMultilingual = it)
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (settings.useMultilingual) {
                        VoiceDropdown(
                            label = "多语言音色",
                            voices = voices,
                            selected = settings.multilingualVoice,
                            onSelect = { settings = settings.copy(multilingualVoice = it) }
                        )
                    } else {
                        VoiceDropdown(
                            label = "英文音色",
                            voices = voices.filter { it.supportsEnglish() },
                            selected = settings.enVoice,
                            onSelect = { settings = settings.copy(enVoice = it) }
                        )
                        Spacer(Modifier.height(6.dp))
                        VoiceDropdown(
                            label = "中文音色",
                            voices = voices.filter { it.supportsChinese() },
                            selected = settings.zhVoice,
                            onSelect = { settings = settings.copy(zhVoice = it) }
                        )
                    }
                }
            }

            if (settings.mode == TtsEngineMode.OPENAI_COMPAT) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = settings.serverUrl,
                    onValueChange = { settings = settings.copy(serverUrl = it.trim()) },
                    label = { Text("服务器地址") },
                    supportingText = { Text("如 https://your-server.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.serverModel,
                    onValueChange = { settings = settings.copy(serverModel = it.trim()) },
                    label = { Text("模型名") },
                    supportingText = { Text("Fish Speech S2 等服务器发布的模型名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.serverToken,
                    onValueChange = { settings = settings.copy(serverToken = it.trim()) },
                    label = { Text("API Token（可选）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.serverVoice,
                    onValueChange = { settings = settings.copy(serverVoice = it.trim()) },
                    label = { Text("音色/voice（可选，通用兜底）") },
                    supportingText = { Text("Fish Speech 通常为 default；IndexTTS 可填参考音频名，如 voice_03.wav") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.serverEnVoice,
                    onValueChange = { settings = settings.copy(serverEnVoice = it.trim()) },
                    label = { Text("英文音色（可选）") },
                    supportingText = { Text("英文句子用此音色；留空跟随上方通用音色") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.serverZhVoice,
                    onValueChange = { settings = settings.copy(serverZhVoice = it.trim()) },
                    label = { Text("中文音色（可选）") },
                    supportingText = { Text("中文句子用此音色；IndexTTS 一次只克隆一个说话人，中英分开填更自然") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.narratorVoice,
                    onValueChange = { settings = settings.copy(narratorVoice = it.trim()) },
                    label = { Text("旁白音色 Narrator（可选）") },
                    supportingText = { Text("多角色听书 M1：旁白句子用此音色；留空则跟随上方「音色/voice」") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.dialogueVoice,
                    onValueChange = { settings = settings.copy(dialogueVoice = it.trim()) },
                    label = { Text("对白音色 Dialogue（可选）") },
                    supportingText = { Text("引号内对白用此音色；两个都留空 = 单音色模式（原有行为）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = ::testServer, enabled = !busy) {
                    Text("测试连接")
                }
                status?.let {
                    Text(
                        it.text,
                        color = when (it.tone) {
                            StatusTone.SUCCESS -> Success
                            StatusTone.DANGER -> Danger
                            StatusTone.NEUTRAL -> InkFaint
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (settings.mode == TtsEngineMode.VOLC) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = settings.volcApiKey,
                    onValueChange = { settings = settings.copy(volcApiKey = it.trim()) },
                    label = { Text("API Key（新版控制台，推荐）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.volcAppId,
                    onValueChange = { settings = settings.copy(volcAppId = it.trim()) },
                    label = { Text("App ID（旧版控制台）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.volcToken,
                    onValueChange = { settings = settings.copy(volcToken = it.trim()) },
                    label = { Text("Access Token（旧版控制台）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                PresetField(
                    label = "Resource ID / 模型",
                    value = settings.volcResourceId,
                    onValueChange = { settings = settings.copy(volcResourceId = it.trim()) },
                    presets = volcResourcePresets,
                    supportingText = "2.0 音色配 seed-tts-2.0；1.0 音色配 seed-tts-1.0"
                )
                Spacer(Modifier.height(10.dp))
                PresetField(
                    label = "中文音色",
                    value = settings.volcZhVoice,
                    onValueChange = { settings = settings.copy(volcZhVoice = it.trim()) },
                    presets = volcZhVoicePresets(settings.volcResourceId)
                )
                Spacer(Modifier.height(10.dp))
                PresetField(
                    label = "英文音色",
                    value = settings.volcEnVoice,
                    onValueChange = { settings = settings.copy(volcEnVoice = it.trim()) },
                    presets = volcEnVoicePresets(settings.volcResourceId)
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = ::testVolcano, enabled = !busy) {
                    Text("测试连接")
                }
                status?.let {
                    Text(
                        it.text,
                        color = when (it.tone) {
                            StatusTone.SUCCESS -> Success
                            StatusTone.DANGER -> Danger
                            StatusTone.NEUTRAL -> InkFaint
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // Multi-voice M4: switch + narrator/character voices (§8).
            MultiVoiceSection(
                settings = settings,
                onSettingsChange = { settings = it },
                books = books,
                preselectedBook = book
            )

            if (settings.mode != TtsEngineMode.SYSTEM && settings.mode != TtsEngineMode.PIPER) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "启用后，朗读文本会发送到对应云端/自建服务，按实际服务计费；" +
                        "章节音频首次生成后缓存在本机。合成失败会自动回退系统语音。",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = ::save,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = OnAccent
                    ),
                    shape = PillShape
                ) { Text("保存") }
            }
        }
    }

@Composable
private fun PresetField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    presets: List<Pair<String, String>>,
    supportingText: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                supportingText = supportingText?.let { supporting ->
                    { Text(supporting) }
                },
                visualTransformation = VisualTransformation.None,
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Box {
                TextButton(onClick = { expanded = true }) { Text("预设") }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    presets.forEach { (preset, name) ->
                        DropdownMenuItem(
                            text = { Text("$name（$preset）") },
                            onClick = {
                                onValueChange(preset)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

private val volcResourcePresets = listOf(
    "seed-tts-2.0" to "豆包语音合成模型 2.0（推荐）",
    "seed-tts-1.0" to "豆包语音合成模型 1.0",
    "seed-tts-1.0-concurr" to "豆包语音合成模型 1.0（并发版）"
)

private fun volcZhVoicePresets(resourceId: String): List<Pair<String, String>> =
    if (resourceId.startsWith("seed-tts-2.0")) {
        listOf(
            "zh_female_shuangkuaisisi_uranus_bigtts" to "爽快思思 2.0",
            "zh_female_cancan_uranus_bigtts" to "灿灿 2.0",
            "zh_female_vv_uranus_bigtts" to "VV 2.0",
            "zh_female_xiaohe_uranus_bigtts" to "晓荷 2.0",
            "zh_male_m191_uranus_bigtts" to "云舟 2.0",
            "zh_male_taocheng_uranus_bigtts" to "小田 2.0",
            "zh_female_kefunvsheng_uranus_bigtts" to "暖阳女声 2.0"
        )
    } else {
        listOf(
            "BV001_streaming" to "通用女声",
            "BV002_streaming" to "通用男声",
            "BV700_streaming" to "灿灿",
            "BV701_streaming" to "青苍（有声书）"
        )
    }

private fun volcEnVoicePresets(resourceId: String): List<Pair<String, String>> =
    if (resourceId.startsWith("seed-tts-2.0")) {
        listOf(
            "en_female_dacey_uranus_bigtts" to "Dacey（英文女声）",
            "en_male_tim_uranus_bigtts" to "Tim（英文男声）"
        )
    } else {
        listOf("BV503_streaming" to "Ariana（英文女声）")
    }

@Composable
private fun EngineChoice(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        Modifier
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Accent else Ink
        )
    }
}

@Composable
private fun VoiceDropdown(
    label: String,
    voices: List<AzureVoice>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val available = voices.filter { it.status.equals("GA", ignoreCase = true) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = InkSoft)
        Box(Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    available.firstOrNull { it.shortName == selected }
                        ?.let { "${it.displayName}（${it.locale}）" }
                        ?: selected.ifBlank { "未选择" },
                    modifier = Modifier.weight(1f),
                    color = Ink
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = InkSoft)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                available.forEach { voice ->
                    DropdownMenuItem(
                        text = {
                            Text("${voice.displayName}（${voice.locale}）")
                        },
                        onClick = {
                            onSelect(voice.shortName)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemVoiceDropdown(
    label: String,
    voices: List<SystemVoiceInfo>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = InkSoft)
        Box(Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    voices.firstOrNull { it.name == selected }?.displayName()
                        ?: "跟随系统默认",
                    modifier = Modifier.weight(1f),
                    color = Ink
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = InkSoft)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("跟随系统默认") },
                    onClick = {
                        onSelect("")
                        expanded = false
                    }
                )
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice.displayName()) },
                        onClick = {
                            onSelect(voice.name)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PiperVoiceDropdown(
    voices: List<PiperVoice>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = voices.firstOrNull { it.id == selectedId }
    Column(Modifier.fillMaxWidth()) {
        Text("Piper 英文音色", style = MaterialTheme.typography.labelMedium, color = InkSoft)
        Box(Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    selected?.displayName ?: PiperVoiceCatalog.builtin.displayName,
                    modifier = Modifier.weight(1f),
                    color = Ink
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = InkSoft)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = {
                            Text(voice.displayName + if (voice.builtin) "（内置）" else "（已导入）")
                        },
                        onClick = {
                            onSelect(voice.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PiperCatalogRow(
    voice: PiperVoice,
    networkEnabled: Boolean,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(voice.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${voice.language} · ${voice.gender}声 · ${voice.sizeLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint
                )
            }
            PlaySampleButton(
                url = voice.sampleUrl,
                enabled = networkEnabled,
                onMessage = onMessage
            )
            TextButton(
                enabled = networkEnabled && voice.packageUrl.isNotBlank(),
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, voice.packageUrl.toUri()))
                    }.onFailure { onMessage("无法打开下载页面") }
                }
            ) { Text("下载") }
        }
    }
}

@Composable
private fun PlaySampleButton(
    url: String,
    enabled: Boolean,
    onMessage: (String) -> Unit
) {
    val playerState = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) {
        onDispose { playerState.value?.release() }
    }
    TextButton(
        enabled = enabled && url.isNotBlank(),
        onClick = {
            playerState.value?.release()
            val mp = MediaPlayer()
            playerState.value = mp
            val ok = runCatching {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                mp.setDataSource(url)
                mp.setOnPreparedListener { it.start() }
                mp.setOnCompletionListener {
                    it.release()
                    if (playerState.value == it) playerState.value = null
                }
                mp.setOnErrorListener { it, _, _ ->
                    it.release()
                    if (playerState.value == it) playerState.value = null
                    // 之前失败是完全静默的；HuggingFace 在部分网络不可达，必须给反馈。
                    onMessage("样例试听失败，请检查网络（HuggingFace 可能不可达）")
                    true
                }
                mp.prepareAsync()
            }.isSuccess
            if (!ok) {
                runCatching { mp.release() }
                playerState.value = null
                onMessage("无法播放样例音频")
            }
        }
    ) { Text("试听") }
}
