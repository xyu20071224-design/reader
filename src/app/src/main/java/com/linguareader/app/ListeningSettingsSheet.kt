package com.linguareader.app

import com.linguareader.app.tts.TtsAudioCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import android.content.Context
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.data.Book
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.MiMoTtsBackend
import com.linguareader.app.tts.MiMoVoiceCatalog
import com.linguareader.app.tts.OpenAiCompatTtsBackend
import com.linguareader.app.tts.SystemTtsVoices
import com.linguareader.app.tts.SystemVoiceInfo
import com.linguareader.app.tts.TtsEngineMode
import com.linguareader.app.tts.TtsPlaybackController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 听书设置弹层（引擎 / 音色 / 语速 / 试听）。
 *
 * P3 迁移：外壳换成 [LinguaSheet]（拖柄/圆角/边距统一由组件给），样式一律取
 * [LocalDesignTokens]。**三个引擎分支的内容、保存语义、行内反馈与文案零改动。**
 *
 * 标题留在内容体里而不是走 [LinguaSheet] 的 title 槽：同一个内容体还被
 * AI 抽屉的「听书语音」Tab 复用（那里上方是总开关行与 Tab 行，没有本屏标题），
 * 把标题挪进外壳会让抽屉那条路径丢掉这个标题 —— 那属于行为改动，不在迁移范围内。
 */
@Composable
internal fun ListeningSettingsSheet(onDismiss: () -> Unit, book: Book? = null) {
    LinguaSheet(
        onDismiss = onDismiss,
        // 内容体自带显式 Spacer 排布（迁移前就是这样），关掉容器的默认块间距，
        // 否则会 16（默认 spacing.m）+ 自带 Spacer 双倍叠加。
        contentArrangement = Arrangement.Top
    ) {
        ListeningSettingsBody(onDismiss = onDismiss, onSaved = onDismiss, book = book)
    }
}

/**
 * 行内反馈的语义（决定颜色），与文案解耦——禁止再用文案前缀判断样式。
 * public：AppUiState.noticeTone 需要跨类型引用（单模块内无暴露面问题）。
 */
enum class StatusTone { SUCCESS, DANGER, NEUTRAL }

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
 * 行内状态文本（弹层内反馈的统一渲染）：ModalBottomSheet 会盖住全局 Snackbar，
 * 所以弹层打开期间发生的保存/删除类反馈必须画在弹层内部，沿 [SettingsStatus]
 * 的显式语义着色。null 时什么都不画。
 */
@Composable
internal fun SettingsStatusText(status: SettingsStatus?, modifier: Modifier = Modifier) {
    if (status == null) return
    val t = LocalDesignTokens.current
    Text(
        status.text,
        color = when (status.tone) {
            StatusTone.SUCCESS -> t.colors.success
            StatusTone.DANGER -> t.colors.danger
            StatusTone.NEUTRAL -> t.colors.inkFaint
        },
        style = t.typography.label,
        modifier = modifier
    )
}

/**
 * Reusable body of the listening settings (engine / voices / speed / test).
 * Shared by the reader's quick sheet and the AI drawer's "听书语音" tab.
 *
 * ## P3 迁移：**本组件不自带横向留白，由调用方外壳提供**
 *
 * 迁移前它在自己身上写死 `padding(horizontal = 24.dp).padding(bottom = 30.dp)`。
 * 现在两个宿主（[ListeningSettingsSheet] 与 AI 抽屉）都由 [LinguaSheet] 提供
 * spacing.l / spacing.xl 的留白，内容体再写一遍会变成 48dp —— 而且 AI 抽屉那条路径
 * 迁移前就已经是「弹层 24 + 内容体 24 = 48dp」，比其他 Tab 宽一圈。
 * 所以留白**上移到外壳**，两个宿主各拿到一份，抽屉里那个双倍留白顺带被修正。
 *
 * ⚠️ **复用本组件时必须自己给横向留白**（否则内容会顶到屏幕边缘）。它只提供
 * 「填满宽度 + 可滚动」，不给 padding —— 这是为了让宿主唯一的留白来源是外壳，
 * 不会两层叠成 48dp。同理，单独使用（渲染台、未来的平板双栏）时调用方还要提供纸色底。
 *
 * 滚动仍然由本组件自己拥有（`verticalScroll`）：[LinguaSheet] 的内容槽只是普通
 * Column，不给滚动；这一屏在手机上远超一屏，丢了滚动就是真机拖不动的回归
 * （PacksSheet 迁移时踩过的 PacksSheet.kt:141 那条）。
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
    val t = LocalDesignTokens.current
    var settings by remember { mutableStateOf(CloudTtsSettings.load(context)) }
    var systemVoices by remember { mutableStateOf<List<SystemVoiceInfo>>(emptyList()) }
    var systemVoicesLoaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<SettingsStatus?>(null) }

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

    /** MiMo 连通性测试：分别合成一句中文与一句英文探针（BUG-012 教训：只验
     *  键不验合成会漏掉「能连上但不发声」这类故障）。 */
    fun testMimo() {
        if (settings.mode != TtsEngineMode.MIMO || settings.mimoApiKey.isBlank()) {
            status = SettingsStatus.danger(context.getString(R.string.tts_mimo_key_required))
            return
        }
        busy = true
        status = null
        scope.launch {
            val backend = MiMoTtsBackend(settings, context)
            val zhProbe = File(context.cacheDir, "tts_probe_mimo_zh.wav")
            val enProbe = File(context.cacheDir, "tts_probe_mimo_en.wav")
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
                status = SettingsStatus.success(context.getString(R.string.tts_mimo_test_ok))
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
        // 保存成功先在弹层内联展示「已保存」，稍等片刻再回调关闭：
        // 直接关闭会让行内反馈一闪而过（ModalBottomSheet 又会盖住全局
        // Snackbar），用户得到「毫无动静」的观感。失败路径不关闭，反馈常驻。
        status = SettingsStatus.success(context.getString(R.string.tts_settings_saved))
        scope.launch {
            delay(800)
            onSaved()
        }
    }

    LaunchedEffect(settings.mode) {
        status = null
        if (settings.mode == TtsEngineMode.SYSTEM) loadSystemVoices()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // 标题：headlineSmall(24sp) 在 5 档排版梯里没有同名角色，归到 title（18sp/600/衬线）——
        // 与其余已迁移弹层的标题观感一致；差别记在提交信息的「已知差异」里。
        Text(stringResource(R.string.tts_settings_title), style = t.typography.title, color = t.colors.ink)
            // 分区标题换成 SectionHeader：它的上下留白（spacing.l / spacing.s）就是
            // 这一屏原本「14dp 空行 + 分区标题」的 token 版，不再另外叠 Spacer。
            SectionHeader(text = stringResource(R.string.tts_engine_section))
            // 每个引擎独占一行（全宽 radio + 横排 label）。之前把它们塞进
            // `Row` 里左右并排，但 EngineChoice 内部用 `fillMaxWidth()`，在
            // Row 内会两两争抢宽度被压成竖排（真机截图 2026-08-26）。改为
            // Column 单列布局，每个选项都能正常横排，含 MiMo 引擎。
            Column(Modifier.fillMaxWidth()) {
                EngineChoice(
                    label = stringResource(R.string.tts_engine_system),
                    selected = settings.mode == TtsEngineMode.SYSTEM,
                    onSelect = { settings = settings.copy(mode = TtsEngineMode.SYSTEM) }
                )
                EngineChoice(
                    label = stringResource(R.string.tts_engine_openai_compat),
                    selected = settings.mode == TtsEngineMode.OPENAI_COMPAT,
                    onSelect = { settings = settings.copy(mode = TtsEngineMode.OPENAI_COMPAT) }
                )
                EngineChoice(
                    label = stringResource(R.string.tts_engine_mimo),
                    selected = settings.mode == TtsEngineMode.MIMO,
                    onSelect = { settings = settings.copy(mode = TtsEngineMode.MIMO) }
                )
            }

            if (settings.mode == TtsEngineMode.SYSTEM) {
                SectionHeader(text = stringResource(R.string.tts_system_section))
                Text(
                    stringResource(R.string.tts_system_hint),
                    style = t.typography.label,
                    color = t.colors.inkFaint
                )
                Spacer(Modifier.height(t.spacing.xs))
                LinguaTextButton(
                    text = if (systemVoices.isEmpty()) {
                        stringResource(R.string.tts_load_voices)
                    } else {
                        stringResource(R.string.tts_refresh_voices)
                    },
                    onClick = { loadSystemVoices(refresh = true) }
                )
                status?.let { SettingsStatusText(it) }
                if (systemVoices.isNotEmpty()) {
                    Spacer(Modifier.height(t.spacing.xs))
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
                    Spacer(Modifier.height(t.spacing.xs))
                    SystemVoiceDropdown(
                        label = stringResource(R.string.tts_voice_english),
                        voices = if (hasLanguageSplit) englishVoices else systemVoices,
                        selected = settings.systemEnVoice,
                        onSelect = { settings = settings.copy(systemEnVoice = it) }
                    )
                    Spacer(Modifier.height(t.spacing.xs))
                    Text(
                        stringResource(R.string.tts_system_hidden_hint),
                        style = t.typography.label,
                        color = t.colors.inkFaint
                    )
                }
            }

            if (settings.mode == TtsEngineMode.OPENAI_COMPAT) {
                Spacer(Modifier.height(t.spacing.m))
                OutlinedTextField(
                    value = settings.serverUrl,
                    onValueChange = { settings = settings.copy(serverUrl = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_url_label)) },
                    supportingText = { Text(stringResource(R.string.tts_server_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(t.spacing.s))
                OutlinedTextField(
                    value = settings.serverModel,
                    onValueChange = { settings = settings.copy(serverModel = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_model_label)) },
                    supportingText = { Text(stringResource(R.string.tts_server_model_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(t.spacing.s))
                OutlinedTextField(
                    value = settings.serverToken,
                    onValueChange = { settings = settings.copy(serverToken = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_token_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(t.spacing.s))
                OutlinedTextField(
                    value = settings.serverVoice,
                    onValueChange = { settings = settings.copy(serverVoice = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_voice_label)) },
                    supportingText = { Text(stringResource(R.string.tts_server_voice_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(t.spacing.s))
                OutlinedTextField(
                    value = settings.serverEnVoice,
                    onValueChange = { settings = settings.copy(serverEnVoice = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_en_voice_label)) },
                    supportingText = { Text(stringResource(R.string.tts_server_en_voice_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(t.spacing.s))
                OutlinedTextField(
                    value = settings.serverZhVoice,
                    onValueChange = { settings = settings.copy(serverZhVoice = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_zh_voice_label)) },
                    supportingText = { Text(stringResource(R.string.tts_server_zh_voice_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(t.spacing.s))
                OutlinedTextField(
                    value = settings.narratorVoice,
                    onValueChange = { settings = settings.copy(narratorVoice = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_narrator_label)) },
                    supportingText = { Text(stringResource(R.string.tts_server_narrator_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(t.spacing.s))
                OutlinedTextField(
                    value = settings.dialogueVoice,
                    onValueChange = { settings = settings.copy(dialogueVoice = it.trim()) },
                    label = { Text(stringResource(R.string.tts_server_dialogue_label)) },
                    supportingText = { Text(stringResource(R.string.tts_server_dialogue_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(t.spacing.s))
                LinguaTextButton(
                    text = stringResource(R.string.tts_test_connection),
                    onClick = ::testServer,
                    enabled = !busy
                )
                status?.let { SettingsStatusText(it) }
            }

            if (settings.mode == TtsEngineMode.MIMO) {
                SectionHeader(text = stringResource(R.string.tts_mimo_section))
                Text(
                    stringResource(R.string.tts_mimo_section_hint),
                    style = t.typography.label,
                    color = t.colors.inkFaint
                )
                Spacer(Modifier.height(t.spacing.s))
                OutlinedTextField(
                    value = settings.mimoApiKey,
                    onValueChange = { settings = settings.copy(mimoApiKey = it.trim()) },
                    label = { Text(stringResource(R.string.tts_mimo_api_key_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(t.spacing.s))
                PresetField(
                    label = stringResource(R.string.tts_mimo_zh_voice_label),
                    value = settings.mimoZhVoice,
                    onValueChange = { settings = settings.copy(mimoZhVoice = it.trim()) },
                    presets = mimoZhVoicePresets()
                )
                Spacer(Modifier.height(t.spacing.s))
                PresetField(
                    label = stringResource(R.string.tts_mimo_en_voice_label),
                    value = settings.mimoEnVoice,
                    onValueChange = { settings = settings.copy(mimoEnVoice = it.trim()) },
                    presets = mimoEnVoicePresets()
                )
                Spacer(Modifier.height(t.spacing.s))
                OutlinedTextField(
                    value = settings.mimoStyleInstruction,
                    onValueChange = { settings = settings.copy(mimoStyleInstruction = it) },
                    label = { Text(stringResource(R.string.tts_mimo_style_label)) },
                    supportingText = { Text(stringResource(R.string.tts_mimo_style_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(t.spacing.s))
                LinguaTextButton(
                    text = stringResource(R.string.tts_test_connection),
                    onClick = ::testMimo,
                    enabled = !busy
                )
                status?.let { SettingsStatusText(it) }
            }

            // Multi-voice M4: switch + narrator/character voices (`8).
            MultiVoiceSection(
                settings = settings,
                onSettingsChange = { settings = it },
                books = books,
                preselectedBook = book
            )

            if (settings.mode != TtsEngineMode.SYSTEM) {
                Spacer(Modifier.height(t.spacing.m))
                Text(
                    stringResource(R.string.tts_cloud_notice),
                    style = t.typography.label,
                    color = t.colors.inkFaint
                )
            }

            // D2.4：音频缓存的占用/上限/清理。
            // 「必须显示实测占用」是这块的重点——配额是个魔法数字，占用不是；
            // 用户只有看得见数字，才知道 512 MB 对自己是宽是紧。
            AudioCacheSection(
                limitMb = settings.cacheLimitMb,
                onLimitChange = { settings = settings.copy(cacheLimitMb = it) },
                onStatus = { status = it }
            )

            Spacer(Modifier.height(t.spacing.l))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onDismiss != null) {
                    LinguaTextButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = onDismiss
                    )
                    Spacer(Modifier.width(t.spacing.s))
                }
                PrimaryButton(
                    text = stringResource(R.string.common_save),
                    onClick = ::save,
                    enabled = !busy
                )
            }
        }
}

/** 按资源名（如 `tts_mimo_voice_default`）解析预置音色显示名；找不到时回退 key。 */
private fun mimoPresetName(context: Context, nameKey: String): String {
    val id = context.resources.getIdentifier(nameKey, "string", context.packageName)
    return if (id != 0) context.getString(id) else nameKey
}

/** MiMo 中文预置音色下拉数据：[（显示名，音色 id）]。 */
@Composable
private fun mimoZhVoicePresets(): List<Pair<String, String>> {
    val context = LocalContext.current
    // PresetField 约定：[（音色 id，显示名）]——第一项写入 value，第二项展示。
    return MiMoVoiceCatalog.zhVoices.map { it.id to mimoPresetName(context, it.nameKey) }
}

/** MiMo 英文预置音色下拉数据：[（音色 id，显示名）]。 */
@Composable
private fun mimoEnVoicePresets(): List<Pair<String, String>> {
    val context = LocalContext.current
    return MiMoVoiceCatalog.enVoices.map { it.id to mimoPresetName(context, it.nameKey) }
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
                LinguaTextButton(
                    text = stringResource(R.string.tts_preset),
                    onClick = { expanded = true }
                )
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

/**
 * 引擎单选行。
 *
 * 整行可点（`clickable` 挂在整行 Row 上）：需求里的「点文字也能选中」是历史事故的
 * 修复结果（只有圆钮可点时，自动化与手指都会点空），迁移时不能退回局部 clickable。
 */
@Composable
private fun EngineChoice(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val t = LocalDesignTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = t.spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            label,
            style = t.typography.body,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) t.colors.accent else t.colors.ink
        )
    }
}

/**
 * 系统音色下拉。
 *
 * 刻意仍是 Material `TextButton` 而不是 [LinguaTextButton]：它要用「文字占满剩余宽度 +
 * 右侧下拉箭头」的复合内容，原语只接受一个字符串。配色与字级全部取 token，不写裸色值。
 * （与 AiDrawerSheet 的 DrawerTabButton、PacksSheet 的 DangerTextButton 同一类取舍。）
 */
@Composable
private fun SystemVoiceDropdown(
    label: String,
    voices: List<SystemVoiceInfo>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val t = LocalDesignTokens.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = t.typography.label, color = t.colors.inkSoft)
        Box(Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = t.colors.ink)
            ) {
                Text(
                    voices.firstOrNull { it.name == selected }?.displayName()
                        ?: stringResource(R.string.tts_follow_system_default),
                    modifier = Modifier.weight(1f),
                    style = t.typography.body,
                    color = t.colors.ink
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = t.colors.inkSoft)
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
/**
 * 音频缓存：占用、上限、清空（方案 D2.4）。
 *
 * 放在听书设置里而不是单开一个「存储」页：缓存本来就是听书产生的，而这个 App
 * 目前没有统一的设置页（各功能各自弹层）。全局的「各类占用 + 孤儿数据清理」需要
 * 共享 AppViewModel 里那份 per-book 存储清单，单开一轮做，别在这里复制第二份清单。
 */
@Composable
private fun AudioCacheSection(
    limitMb: Int,
    onLimitChange: (Int) -> Unit,
    onStatus: (SettingsStatus) -> Unit
) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cache = remember { TtsAudioCache(context) }
    // 每次进入设置都重新量一次；清理后也刷新。
    var usedBytes by remember { mutableStateOf(-1L) }
    LaunchedEffect(Unit) {
        usedBytes = withContext(Dispatchers.IO) { cache.totalBytes() }
    }

    SectionHeader(text = stringResource(R.string.tts_cache_section))
    Text(
        when {
            usedBytes < 0 -> ""
            usedBytes == 0L -> stringResource(R.string.tts_cache_usage_empty)
            else -> stringResource(R.string.tts_cache_usage, formatBytes(usedBytes))
        },
        style = t.typography.body,
        color = t.colors.ink
    )
    Spacer(Modifier.height(t.spacing.xs))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.tts_cache_limit_label),
            style = t.typography.label,
            color = t.colors.inkSoft
        )
        Spacer(Modifier.width(t.spacing.s))
        CloudTtsSettings.CACHE_LIMIT_OPTIONS_MB.forEach { option ->
            val selected = limitMb == option
            CacheLimitOption(
                text = if (option <= 0) {
                    stringResource(R.string.tts_cache_limit_unlimited)
                } else {
                    stringResource(R.string.tts_cache_limit_mb, option)
                },
                selected = selected,
                onClick = { onLimitChange(option) }
            )
        }
    }
    Text(
        stringResource(R.string.tts_cache_hint),
        style = t.typography.label,
        color = t.colors.inkFaint
    )
    LinguaTextButton(
        text = stringResource(R.string.tts_cache_clear),
        onClick = {
            scope.launch {
                val freed = withContext(Dispatchers.IO) { cache.clearAll() }
                usedBytes = withContext(Dispatchers.IO) { cache.totalBytes() }
                onStatus(
                    SettingsStatus.success(
                        context.getString(R.string.tts_cache_cleared, formatBytes(freed))
                    )
                )
            }
        },
        enabled = usedBytes > 0
    )
}

/**
 * 缓存上限的候选值（256MB / 512MB / 1024MB / 不限）。
 *
 * 刻意仍是 Material `TextButton`：选中态靠**字重 + 颜色**两个维度表达，
 * 而 [LinguaTextButton] 只有一种主色、不接受字重参数 —— 套上去「当前选中哪一档」
 * 这条唯一线索就没了。这是组件层缺口（还没有「可选中文字按钮」原语），
 * 与 AiDrawerSheet 的 DrawerTabButton 同一处缺口，已在提交信息里记给 Lead。
 * 配色与字级取 token，不写裸色值。
 */
@Composable
private fun CacheLimitOption(text: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (selected) t.colors.accent else t.colors.inkSoft
        )
    ) {
        Text(
            text = text,
            style = t.typography.body,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** 人类可读的字节数（占用要给人看，不是给机器看）。 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "${bytes} B"
}
