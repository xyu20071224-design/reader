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
import androidx.compose.ui.res.stringResource
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
        piperStatus = SettingsStatus.info(context.getString(R.string.tts_piper_import_checking))
        scope.launch {
            PiperVoiceImporter.import(context, uri)
                .onSuccess { voice ->
                    piperVoices = PiperVoiceStore.installed(context)
                    settings = settings.copy(piperEnVoiceId = voice.id)
                    piperStatus = SettingsStatus.success(
                        context.getString(R.string.tts_piper_imported, voice.id)
                    )
                }
                .onFailure {
                    piperStatus = SettingsStatus.danger(
                        it.message ?: context.getString(R.string.tts_piper_import_failed)
                    )
                }
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
                SettingsStatus.info(context.getString(R.string.tts_system_voices_empty))
            } else {
                SettingsStatus.success(
                    context.resources.getQuantityString(
                        R.plurals.tts_system_voices_loaded, list.size, list.size
                    )
                )
            }
        }
    }

    fun fetchAzureVoices() {
        if (settings.region.isBlank() || settings.apiKey.isBlank()) {
            status = SettingsStatus.danger(context.getString(R.string.tts_fill_region_key))
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
                    status = SettingsStatus.success(
                        context.resources.getQuantityString(
                            R.plurals.tts_voices_fetched, list.size, list.size
                        )
                    )
                }
                .onFailure {
                    status = SettingsStatus.danger(
                        it.message ?: context.getString(R.string.tts_fetch_voices_failed)
                    )
                }
            busy = false
        }
    }

    /** BUG-012: actually synthesize a probe clip — listing voices proves the
     *  key but not that speech synthesis itself works. */
    fun testAzure() {
        if (settings.region.isBlank() || settings.apiKey.isBlank()) {
            status = SettingsStatus.danger(context.getString(R.string.tts_fill_region_key))
            return
        }
        val voice = when {
            settings.useMultilingual && settings.multilingualVoice.isNotBlank() ->
                settings.multilingualVoice
            settings.zhVoice.isNotBlank() -> settings.zhVoice
            else -> settings.enVoice
        }
        if (voice.isBlank()) {
            status = SettingsStatus.danger(context.getString(R.string.tts_select_voice_first))
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
                    status = SettingsStatus.success(context.getString(R.string.tts_azure_test_ok))
                }
                .onFailure {
                    probe.delete()
                    status = SettingsStatus.danger(
                        it.message ?: context.getString(R.string.tts_test_failed)
                    )
                }
            busy = false
        }
    }

    fun testServer() {
        if (!settings.isConfigured) {
            status = SettingsStatus.danger(context.getString(R.string.tts_fill_server_url))
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
                    status = SettingsStatus.success(context.getString(R.string.tts_server_test_ok))
                }
                .onFailure {
                    status = SettingsStatus.danger(
                        it.message ?: context.getString(R.string.tts_test_failed)
                    )
                }
            busy = false
        }
    }

    fun testVolcano() {
        if (!settings.isConfigured) {
            status = SettingsStatus.danger(context.getString(R.string.tts_fill_volc_credentials))
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
                status = SettingsStatus.success(context.getString(R.string.tts_volc_test_ok))
            } else {
                status = SettingsStatus.danger(
                    (zh.exceptionOrNull() ?: en.exceptionOrNull())?.message
                        ?: context.getString(R.string.tts_test_failed)
                )
            }
            busy = false
        }
    }

    fun save() {
        if (settings.mode != TtsEngineMode.SYSTEM && !settings.isConfigured) {
            status = SettingsStatus.danger(context.getString(R.string.tts_cloud_incomplete))
            return
        }
        CloudTtsSettings.save(context, settings)
        TtsPlaybackController.onCloudSettingsChanged(context)
        status = SettingsStatus.success(context.getString(R.string.tts_saved))
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
        Text(stringResource(R.string.tts_settings_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.tts_engine_section), style = MaterialTheme.typography.labelLarge, color = InkSoft)
            Row(verticalAlignment = Alignment.CenterVertically) {
                EngineChoice(
                    label = stringResource(R.string.tts_engine_system),
                    selected = settings.mode == TtsEngineMode.SYSTEM,
                    onSelect = { settings = settings.copy(mode = TtsEngineMode.SYSTEM) }
                )
                Spacer(Modifier.weight(1f))
                EngineChoice(
                    label = stringResource(R.string.tts_engine_piper),
                    selected = settings.mode == TtsEngineMode.PIPER,
                    onSelect = { settings = settings.copy(mode = TtsEngineMode.PIPER) }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                EngineChoice(
                    label = stringResource(R.string.tts_engine_azure),
                    selected = settings.mode == TtsEngineMode.AZURE,
                    onSelect = { settings = settings.copy(mode = TtsEngineMode.AZURE) }
                )
                Spacer(Modifier.weight(1f))
                EngineChoice(
                    label = stringResource(R.string.tts_engine_volc),
                    selected = settings.mode == TtsEngineMode.VOLC,
                    onSelect = { settings = settings.copy(mode = TtsEngineMode.VOLC) }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                EngineChoice(
                    label = stringResource(R.string.tts_engine_openai_compat),
                    selected = settings.mode == TtsEngineMode.OPENAI_COMPAT,
                    onSelect = { settings = settings.copy(mode = TtsEngineMode.OPENAI_COMPAT) }
                )
            }

            if (settings.mode == TtsEngineMode.PIPER) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.tts_piper_intro),
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
                Text(stringResource(R.string.tts_piper_download_more), style = MaterialTheme.typography.labelLarge, color = InkSoft)
                Text(
                    stringResource(R.string.tts_piper_download_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { onnxPicker.launch(arrayOf("*/*")) },
                    enabled = !piperImporting
                ) {
                    Text(
                        if (piperImporting) stringResource(R.string.tts_piper_importing)
                        else stringResource(R.string.tts_piper_import_model)
                    )
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
                        stringResource(R.string.tts_piper_network_off),
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
                Text(stringResource(R.string.tts_system_section), style = MaterialTheme.typography.labelLarge, color = InkSoft)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.tts_system_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { loadSystemVoices(refresh = true) }) {
                    Text(
                        if (systemVoices.isEmpty()) stringResource(R.string.tts_load_voices)
                        else stringResource(R.string.tts_refresh_voices)
                    )
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
                        label = stringResource(R.string.tts_voice_chinese),
                        voices = if (hasLanguageSplit) chineseVoices else systemVoices,
                        selected = settings.systemZhVoice,
                        onSelect = { settings = settings.copy(systemZhVoice = it) }
                    )
                    Spacer(Modifier.height(6.dp))
                    SystemVoiceDropdown(
                        label = stringResource(R.string.tts_voice_english),
                        voices = if (hasLanguageSplit) englishVoices else systemVoices,
                        selected = settings.systemEnVoice,
                        onSelect = { settings = settings.copy(systemEnVoice = it) }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.tts_system_hidden_hint),
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
                    supportingText = { Text(stringResource(R.string.tts_azure_region_hint)) },
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
                        Text(stringResource(R.string.tts_fetch_voices))
                    }
                    TextButton(onClick = ::testAzure, enabled = !busy) {
                        Text(stringResource(R.string.tts_test_connection))
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
                            Text(stringResource(R.string.tts_multilingual_toggle), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.tts_multilingual_toggle_hint),
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
                            label = stringResource(R.string.tts_voice_multilingual),
                            voices = voices,
                            selected = settings.multilingualVoice,
                            onSelect = { settings = settings.copy(multilingualVoice = it) }
                        )
                    } else {
                        VoiceDropdown(
                            label = stringResource(R.string.tts_voice_english),
                            voices = voices.filter { it.supportsEnglish() },
                            selected = settings.enVoice,
                            onSelect = { settings = settings.copy(enVoice = it) }
                        )
                        Spacer(Modifier.height(6.dp))
                        VoiceDropdown(
                            label = stringResource(R.string.tts_voice_chinese),
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
                    label = { Text(stringResource(R.string.tts_server_url_label)) },
                    supportingText = { Text(stringResource(R.string.tts_server_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.serverModel,
                    onValueChange = { settings = settings.copy(serverModel = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_model_label)) },
                    supportingText = { Text(stringResource(R.string.tts_server_model_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.serverToken,
                    onValueChange = { settings = settings.copy(serverToken = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_token_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.serverVoice,
                    onValueChange = { settings = settings.copy(serverVoice = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_voice_label)) },
                    supportingText = { Text(stringResource(R.string.tts_server_voice_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.serverEnVoice,
                    onValueChange = { settings = settings.copy(serverEnVoice = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_en_voice_label)) },
                    supportingText = { Text(stringResource(R.string.tts_server_en_voice_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.serverZhVoice,
                    onValueChange = { settings = settings.copy(serverZhVoice = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_zh_voice_label)) },
                    supportingText = { Text(stringResource(R.string.tts_server_zh_voice_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.narratorVoice,
                    onValueChange = { settings = settings.copy(narratorVoice = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_narrator_label)) },
                    supportingText = { Text(stringResource(R.string.tts_server_narrator_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.dialogueVoice,
                    onValueChange = { settings = settings.copy(dialogueVoice = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_dialogue_label)) },
                    supportingText = { Text(stringResource(R.string.tts_server_dialogue_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = ::testServer, enabled = !busy) {
                    Text(stringResource(R.string.tts_test_connection))
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
                    label = { Text(stringResource(R.string.tts_volc_api_key_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.volcAppId,
                    onValueChange = { settings = settings.copy(volcAppId = it.trim()) },
                    label = { Text(stringResource(R.string.tts_volc_app_id_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.volcToken,
                    onValueChange = { settings = settings.copy(volcToken = it.trim()) },
                    label = { Text(stringResource(R.string.tts_volc_token_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                PresetField(
                    label = stringResource(R.string.tts_volc_resource_label),
                    value = settings.volcResourceId,
                    onValueChange = { settings = settings.copy(volcResourceId = it.trim()) },
                    presets = volcResourcePresets(),
                    supportingText = stringResource(R.string.tts_volc_resource_hint)
                )
                Spacer(Modifier.height(10.dp))
                PresetField(
                    label = stringResource(R.string.tts_voice_chinese),
                    value = settings.volcZhVoice,
                    onValueChange = { settings = settings.copy(volcZhVoice = it.trim()) },
                    presets = volcZhVoicePresets(settings.volcResourceId)
                )
                Spacer(Modifier.height(10.dp))
                PresetField(
                    label = stringResource(R.string.tts_voice_english),
                    value = settings.volcEnVoice,
                    onValueChange = { settings = settings.copy(volcEnVoice = it.trim()) },
                    presets = volcEnVoicePresets(settings.volcResourceId)
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = ::testVolcano, enabled = !busy) {
                    Text(stringResource(R.string.tts_test_connection))
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
                    stringResource(R.string.tts_cloud_notice),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
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
                ) { Text(stringResource(R.string.common_save)) }
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
                TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.tts_preset)) }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    presets.forEach { (preset, name) ->
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.tts_preset_item, name, preset))
                            },
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

@Composable
private fun volcResourcePresets(): List<Pair<String, String>> = listOf(
    "seed-tts-2.0" to stringResource(R.string.tts_volc_model_2),
    "seed-tts-1.0" to stringResource(R.string.tts_volc_model_1),
    "seed-tts-1.0-concurr" to stringResource(R.string.tts_volc_model_1_concurr)
)

@Composable
private fun volcZhVoicePresets(resourceId: String): List<Pair<String, String>> =
    if (resourceId.startsWith("seed-tts-2.0")) {
        listOf(
            "zh_female_shuangkuaisisi_uranus_bigtts" to stringResource(R.string.tts_volc_voice_shuangkuaisisi),
            "zh_female_cancan_uranus_bigtts" to stringResource(R.string.tts_volc_voice_cancan_2),
            "zh_female_vv_uranus_bigtts" to stringResource(R.string.tts_volc_voice_vv),
            "zh_female_xiaohe_uranus_bigtts" to stringResource(R.string.tts_volc_voice_xiaohe),
            "zh_male_m191_uranus_bigtts" to stringResource(R.string.tts_volc_voice_yunzhou),
            "zh_male_taocheng_uranus_bigtts" to stringResource(R.string.tts_volc_voice_xiaotian),
            "zh_female_kefunvsheng_uranus_bigtts" to stringResource(R.string.tts_volc_voice_nuanyang)
        )
    } else {
        listOf(
            "BV001_streaming" to stringResource(R.string.tts_volc_voice_standard_female),
            "BV002_streaming" to stringResource(R.string.tts_volc_voice_standard_male),
            "BV700_streaming" to stringResource(R.string.tts_volc_voice_cancan),
            "BV701_streaming" to stringResource(R.string.tts_volc_voice_qingcang)
        )
    }

@Composable
private fun volcEnVoicePresets(resourceId: String): List<Pair<String, String>> =
    if (resourceId.startsWith("seed-tts-2.0")) {
        listOf(
            "en_female_dacey_uranus_bigtts" to stringResource(R.string.tts_volc_voice_dacey),
            "en_male_tim_uranus_bigtts" to stringResource(R.string.tts_volc_voice_tim)
        )
    } else {
        listOf("BV503_streaming" to stringResource(R.string.tts_volc_voice_ariana))
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
                        ?.let { stringResource(R.string.tts_voice_with_locale, it.displayName, it.locale) }
                        ?: selected.ifBlank { stringResource(R.string.tts_voice_unselected) },
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
                            Text(
                                stringResource(
                                    R.string.tts_voice_with_locale,
                                    voice.displayName,
                                    voice.locale
                                )
                            )
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
                        ?: stringResource(R.string.tts_follow_system_default),
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
                    text = { Text(stringResource(R.string.tts_follow_system_default)) },
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
        Text(stringResource(R.string.tts_piper_voice_label), style = MaterialTheme.typography.labelMedium, color = InkSoft)
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
                            Text(
                                stringResource(
                                    if (voice.builtin) R.string.tts_voice_builtin
                                    else R.string.tts_voice_imported,
                                    voice.displayName
                                )
                            )
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
                    stringResource(
                        R.string.tts_piper_voice_meta,
                        voice.language,
                        when (voice.gender) {
                            "男" -> stringResource(R.string.multivoice_gender_male)
                            "女" -> stringResource(R.string.multivoice_gender_female)
                            else -> voice.gender
                        },
                        voice.sizeLabel
                    ),
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
                    }.onFailure {
                        onMessage(context.getString(R.string.tts_piper_open_failed))
                    }
                }
            ) { Text(stringResource(R.string.tts_download)) }
        }
    }
}

@Composable
private fun PlaySampleButton(
    url: String,
    enabled: Boolean,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
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
                    onMessage(context.getString(R.string.tts_sample_failed))
                    true
                }
                mp.prepareAsync()
            }.isSuccess
            if (!ok) {
                runCatching { mp.release() }
                playerState.value = null
                onMessage(context.getString(R.string.tts_sample_play_failed))
            }
        }
    ) { Text(stringResource(R.string.tts_audition)) }
}
