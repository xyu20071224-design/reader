package com.linguareader.app

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.linguareader.app.tts.AzureSpeechClient
import com.linguareader.app.tts.AzureVoice
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.CloudVoicePicker
import com.linguareader.app.tts.CloudVoiceStore
import com.linguareader.app.tts.OpenAiCompatTtsBackend
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
internal fun ListeningSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(CloudTtsSettings.load(context)) }
    var voices by remember { mutableStateOf(CloudVoiceStore.load(context)) }
    var systemVoices by remember { mutableStateOf<List<SystemVoiceInfo>>(emptyList()) }
    var systemVoicesLoaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun loadSystemVoices(refresh: Boolean = false) {
        if (systemVoicesLoaded && !refresh) return
        status = null
        SystemTtsVoices.load(context) { list ->
            systemVoices = list
            systemVoicesLoaded = true
            status = if (list.isEmpty()) {
                "未找到可用的系统音色，将使用系统默认"
            } else {
                "已获取 ${list.size} 个系统音色"
            }
        }
    }

    fun fetchAzureVoices() {
        if (settings.region.isBlank() || settings.apiKey.isBlank()) {
            status = "请先填写 Region 和 API Key"
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
                    status = "已获取 ${list.size} 个可用音色"
                }
                .onFailure { status = it.message ?: "获取音色失败，请检查 Region 与 Key" }
            busy = false
        }
    }

    fun testServer() {
        if (!settings.isConfigured) {
            status = "请先填写服务器地址"
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
                    status = "连接成功，服务器可正常合成"
                }
                .onFailure { status = it.message ?: "连接失败" }
            busy = false
        }
    }

    fun testVolcano() {
        if (!settings.isConfigured) {
            status = "请填写 API Key，或 App ID + Access Token"
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
                status = "连接成功，中英文音色均可合成"
            } else {
                status = (zh.exceptionOrNull() ?: en.exceptionOrNull())
                    ?.message ?: "连接失败"
            }
            busy = false
        }
    }

    fun save() {
        if (settings.mode != TtsEngineMode.SYSTEM && !settings.isConfigured) {
            status = "启用云 TTS 前请先完成对应配置"
            return
        }
        CloudTtsSettings.save(context, settings)
        TtsPlaybackController.onCloudSettingsChanged(context)
        onDismiss()
    }

    LaunchedEffect(settings.mode) {
        status = null
        if (settings.mode == TtsEngineMode.SYSTEM) loadSystemVoices()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
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
                        "自动按中英文切换内置音色。",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint
                )
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
                        it,
                        color = if (it.startsWith("已获取")) Success else InkFaint,
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
                        "需要联网下载的音色会标记（网络）。",
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
                    TextButton(onClick = ::fetchAzureVoices, enabled = !busy) {
                        Text("测试连接")
                    }
                }
                status?.let {
                    Text(
                        it,
                        color = if (it.startsWith("已获取") || it.startsWith("连接成功")) Success else Danger,
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
                    label = { Text("音色/voice（可选）") },
                    supportingText = { Text("Fish Speech 通常为 default；也可填克隆音色名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = ::testServer, enabled = !busy) {
                    Text("测试连接")
                }
                status?.let {
                    Text(
                        it,
                        color = if (it.startsWith("连接成功")) Success else Danger,
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
                        it,
                        color = if (it.startsWith("连接成功")) Success else Danger,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

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
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = ::save,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = Color.White
                    ),
                    shape = PillShape
                ) { Text("保存") }
            }
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
                Text("▾", color = InkSoft)
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
                Text("▾", color = InkSoft)
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
