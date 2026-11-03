package com.linguareader.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linguareader.app.ai.AiProviderProfile
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.ai.BookGlossary
import com.linguareader.app.ai.GlossaryEntry
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.components.SettingRow
import com.linguareader.app.data.Book
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.TtsPlaybackController
import kotlinx.coroutines.delay

/**
 * "AI 中心"底部抽屉：一个入口集中管理所有 AI 能力。
 *
 *  - 头部是联网 AI 总开关：关闭后翻译（模型服务）与云端朗读
 *    （自建服务端/MiMo）全部回到离线模式，本地词典不受影响。
 *  - 三个 Tab：翻译设置 / 听书语音（复用 [ListeningSettingsBody]）/ 术语表。
 *
 * P3 迁移：外壳换成 [LinguaSheet]（拖柄/圆角/边距统一由组件给），总开关行换成
 * [SettingRow]，样式一律取 [LocalDesignTokens]。**三个 Tab 的内容、保存语义、
 * 行内「已保存」提示与隐私边界文案零改动。**
 *
 * 标题与副标题不放进 LinguaSheet 的 title 槽：这一屏的标题行右侧挂着总开关，
 * 是「标题 + 控件」的组合行，拆开会让开关跑到标题上方，语义与视觉都变差。
 */
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
    var tab by rememberSaveable { mutableStateOf(AiDrawerTab.TRANSLATION) }

    fun setPower(on: Boolean) {
        onAiSettingsChange(aiSettings.copy(powerEnabled = on))
        val tts = CloudTtsSettings.load(context).copy(networkAiEnabled = on)
        CloudTtsSettings.save(context, tts)
        TtsPlaybackController.onCloudSettingsChanged(context)
    }

    LinguaSheet(
        onDismiss = onDismiss,
        // 内容体自带显式 Spacer 排布（迁移前就是这样），关掉容器默认块间距避免双倍叠加。
        contentArrangement = Arrangement.Top
    ) {
        AiDrawerContent(
            tab = tab,
            powerEnabled = aiSettings.powerEnabled,
            onTabChange = { tab = it },
            onPowerChange = ::setPower,
            books = books,
            aiSettings = aiSettings,
            onAiSettingsChange = onAiSettingsChange,
            onLoadGlossary = onLoadGlossary,
            onAddGlossary = onAddGlossary,
            onUpdateGlossary = onUpdateGlossary,
            onRemoveGlossary = onRemoveGlossary
        )
    }
}

/** 抽屉的三个 Tab。外壳与内容体共用（内容体只读它决定渲染哪一页）。 */
internal object AiDrawerTab {
    const val TRANSLATION = 0
    const val TTS = 1
    const val GLOSSARY = 2
}

/**
 * 抽屉的**内容体**，与弹层外壳解耦。
 *
 * 抽出来的理由：`ModalBottomSheet` 在渲染台（Robolectric）里画不进位图，于是迁移前/后的
 * 视觉对比根本没法做。
 *
 * ⚠️ **抽取是有代价的：本组件不再自带宽度/背景约束**，单独使用时调用方必须补齐
 * （渲染台用法见 `SettingsSheetRenderTest`）。
 *
 * 每个 Tab 自己滚动（`AiTranslationSettingsBody` 内层 verticalScroll），外层只给最大高度，
 * 这样嵌套滚动器不会互相抢手势 —— 迁移前就是这个结构，原样保留。
 */
@Composable
internal fun AiDrawerContent(
    tab: Int,
    powerEnabled: Boolean,
    onTabChange: (Int) -> Unit,
    onPowerChange: (Boolean) -> Unit,
    books: List<Book>,
    aiSettings: AiSettings,
    onAiSettingsChange: (AiSettings) -> Unit,
    onLoadGlossary: suspend (String) -> BookGlossary,
    onAddGlossary: suspend (String, String, String) -> BookGlossary,
    onUpdateGlossary: suspend (String, GlossaryEntry) -> BookGlossary,
    onRemoveGlossary: suspend (String, String) -> BookGlossary
) {
    val t = LocalDesignTokens.current

    // 总开关行：标题 + 说明 + 尾部 Switch，由 SettingRow 统一行高与开关配色。
    SettingRow(
        title = stringResource(R.string.aidrawer_title),
        subtitle = stringResource(R.string.aidrawer_power_hint),
        checked = powerEnabled,
        onCheckedChange = onPowerChange
    )

    Spacer(Modifier.height(t.spacing.m))
    Row(verticalAlignment = Alignment.CenterVertically) {
        DrawerTabButton(
            label = stringResource(R.string.aidrawer_tab_translation),
            selected = tab == AiDrawerTab.TRANSLATION,
            onClick = { onTabChange(AiDrawerTab.TRANSLATION) }
        )
        DrawerTabButton(
            label = stringResource(R.string.aidrawer_tab_tts),
            selected = tab == AiDrawerTab.TTS,
            onClick = { onTabChange(AiDrawerTab.TTS) }
        )
        DrawerTabButton(
            label = stringResource(R.string.shelf_glossary),
            selected = tab == AiDrawerTab.GLOSSARY,
            onClick = { onTabChange(AiDrawerTab.GLOSSARY) }
        )
    }
    // 迁移前是 Ink.copy(alpha = .1f) 的一道浅线；分隔线原语用的是 outline token，
    // 在纸感皮肤下观感一致且换肤后仍然成立，所以换成 token 而不是保留 alpha 硬编码。
    HorizontalDivider(color = t.colors.outline)
    Spacer(Modifier.height(t.spacing.m))
    Box(Modifier.fillMaxWidth().heightIn(max = DrawerBodyMaxHeight)) {
        when (tab) {
            AiDrawerTab.TRANSLATION -> AiTranslationSettingsBody(
                settings = aiSettings,
                onSave = onAiSettingsChange,
                enabled = powerEnabled
            )
            AiDrawerTab.TTS -> ListeningSettingsBody(onDismiss = null, onSaved = {}, books = books)
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

/**
 * Tab 切换按钮。
 *
 * 刻意仍是 Material `TextButton` 而不是 [com.linguareader.app.components.LinguaTextButton]：
 * 选中态靠**字重 + 颜色**两个维度表达（选中＝accent+SemiBold，未选中＝inkSoft+Normal），
 * 而原语只有一种主色、不接受字重参数。套上去会把「当前在哪个 Tab」这个唯一线索抹掉。
 * 这是组件层缺口（还没有「可选中文字按钮」原语），已在提交信息里记给 Lead。
 */
@Composable
private fun DrawerTabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (selected) t.colors.accent else t.colors.inkSoft
        )
    ) {
        Text(
            text = label,
            style = t.typography.body,
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
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    var remoteEnabled by remember(settings) { mutableStateOf(settings.enabled) }
    var providers by remember(settings) { mutableStateOf(settings.providers) }
    var activeId by remember(settings) { mutableStateOf(settings.activeProviderId) }
    var editorDraft by remember { mutableStateOf<AiProviderProfile?>(null) }
    var editorIsNew by remember { mutableStateOf(false) }

    // 保存后必须有明确反馈：此前点「保存」界面毫无变化，用户会以为没响应。
    // 抽屉是独立窗口（全局 Snackbar 会被它遮住），所以确认信息放在行内。
    var savedNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savedNotice) {
        if (savedNotice != null) {
            delay(3000)
            savedNotice = null
        }
    }
    // 「保存后会发生什么」文案（onClick 不是 @Composable 作用域，先取出来复用；
    // 编辑卡「保存即落盘」与底部大「保存」共用同一套持久化与提示）。
    val savedPowerOffText = stringResource(R.string.aidrawer_saved_power_off)
    val savedReadyText = stringResource(R.string.aidrawer_saved_ready)
    val savedLocalText = stringResource(R.string.aidrawer_saved_local)
    val savedOffText = stringResource(R.string.aidrawer_saved_off)

    // 用当前草稿（开关状态 + 服务商列表 + 生效项）构造完整 AiSettings 并写盘。
    // 编辑卡「保存」与底部「保存」都走这一处，保证退出重进后 Key 不丢。
    fun persistCurrent() {
        val updated = settings.copy(
            enabled = remoteEnabled,
            providers = providers,
            activeProviderId = activeId
        ).withActiveMirrored()
        onSave(updated)
        savedNotice = when {
            !enabled -> savedPowerOffText
            updated.remoteReady -> savedReadyText
            remoteEnabled -> savedLocalText
            else -> savedOffText
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        // 「联网语境翻译」开关行。总开关关闭时这一行必须置灰不可点（迁移前是
        // Switch(enabled = enabled)）；SettingRow 的 checked 槽不接受 enabled，
        // 因此走 trailing 槽自己画 —— 行高与配色仍由 SettingRow 统一。
        SettingRow(
            title = stringResource(R.string.aidrawer_remote_title),
            trailing = {
                Switch(
                    checked = remoteEnabled,
                    onCheckedChange = { remoteEnabled = it },
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = t.colors.onAccent,
                        checkedTrackColor = t.colors.accent,
                        checkedBorderColor = t.colors.accent,
                        uncheckedThumbColor = t.colors.inkSoft,
                        uncheckedTrackColor = t.colors.paperDeep,
                        uncheckedBorderColor = t.colors.outline
                    )
                )
            }
        )
        if (remoteEnabled) {
            Spacer(Modifier.height(t.spacing.s))
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
                },
                onAddPreset = { preset ->
                    // 官方预设：端点/协议/模型按目录预填，用户只需补 Key。
                    editorDraft = AiProviderProfile(
                        id = "",
                        name = context.getString(preset.nameRes),
                        baseUrl = preset.baseUrl,
                        protocol = preset.protocol,
                        model = preset.defaultModel
                    )
                    editorIsNew = true
                }
            )
            Spacer(Modifier.height(t.spacing.s))
            Text(
                text = stringResource(R.string.aidrawer_remote_on_hint),
                style = t.typography.label,
                color = t.colors.inkSoft
            )
        } else {
            Spacer(Modifier.height(t.spacing.s))
            Text(
                text = stringResource(R.string.aidrawer_remote_off_hint),
                style = t.typography.label,
                color = t.colors.inkSoft
            )
        }
        Spacer(Modifier.height(t.spacing.l))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            savedNotice?.let {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SavedNoticeIconGap)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = t.colors.success,
                        modifier = Modifier.size(SavedNoticeIconSize)
                    )
                    Text(
                        text = it,
                        style = t.typography.caption,
                        color = t.colors.success
                    )
                }
            } ?: Spacer(Modifier.weight(1f))
            PrimaryButton(text = stringResource(R.string.common_save), onClick = { persistCurrent() })
        }
    }
    editorDraft?.let { draft ->
        ProviderEditorDialog(
            initial = draft,
            isNew = editorIsNew,
            masterEnabled = enabled,
            onSave = { saved ->
                if (saved.id.isBlank()) {
                    val withId = saved.copy(id = java.util.UUID.randomUUID().toString())
                    providers = providers + withId
                    if (providers.none { it.id == activeId }) activeId = withId.id
                } else {
                    providers = providers.map { if (it.id == saved.id) saved else it }
                }
                editorDraft = null
                // 「卡片保存即落盘」：编辑卡里的保存直接持久化服务商 + 当前开关状态，
                // 退出重进后不再丢 Key，也无需再回到底部大「保存」二次提交。
                persistCurrent()
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

// ── 组件专有几何常量 ──────────────────────────────────────────────
//
// Tab 内容区的最大高度：这不是「间距」，不进间距梯。560dp 是迁移前的实测值 ——
// 手机上它让内容区约占屏幕 2/3、超出的部分由各 Tab 自己滚动，改大改小都会改变
// 首屏能看到多少内容。梯子里也没有对应档位（最大档 48dp）。
private val DrawerBodyMaxHeight = 560.dp

// 「已保存」行内提示的图标尺寸与其与文字的间隙：16/6 都不是间距梯的档位
// （4/8/16/24/32/48）。16 硬套到 24 会把整行行高撑起来，6 硬套到 8 会让图标与文字松脱。
private val SavedNoticeIconSize = 16.dp
private val SavedNoticeIconGap = 6.dp