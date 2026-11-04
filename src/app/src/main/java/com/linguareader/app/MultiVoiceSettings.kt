package com.linguareader.app

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import java.io.File
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.components.SettingRow
import com.linguareader.app.data.Book
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.multivoice.AnnotateListMaxHeight
import com.linguareader.app.multivoice.AnnotateRowVerticalPadding
import com.linguareader.app.multivoice.FieldGap
import com.linguareader.app.multivoice.InlineIconGap
import com.linguareader.app.multivoice.LabelGap
import com.linguareader.app.multivoice.ListHeaderBottomGap
import com.linguareader.app.multivoice.ListRowHorizontalPadding
import com.linguareader.app.multivoice.LoadingSpinnerSize
import com.linguareader.app.multivoice.LockIconSize
import com.linguareader.app.multivoice.MediumIconSize
import com.linguareader.app.multivoice.MiMoPanelGap
import com.linguareader.app.multivoice.PillHorizontalPadding
import com.linguareader.app.multivoice.PillWashAlpha
import com.linguareader.app.multivoice.RowIconSize
import com.linguareader.app.multivoice.RowVerticalGap
import com.linguareader.app.multivoice.SectionGap
import com.linguareader.app.multivoice.SectionRuleGap
import com.linguareader.app.multivoice.SmallIconSize
import com.linguareader.app.multivoice.VoicePickerMaxHeight
import com.linguareader.app.multivoice.AddCharacterDialog
import com.linguareader.app.multivoice.EditCharacterDialog
import com.linguareader.app.multivoice.VoicePickerTarget
import com.linguareader.app.multivoice.VoicePickerDialog
import com.linguareader.app.multivoice.GenderPill
import com.linguareader.app.multivoice.SystemEngineGuidance
import com.linguareader.app.multivoice.SystemVoiceAnnotateDialog
import com.linguareader.app.multivoice.VoiceRow
import com.linguareader.app.multivoice.characterSubtitle
import com.linguareader.app.multivoice.statusText
import com.linguareader.app.multivoice.MiMoCustomVoicesPanel
import com.linguareader.app.tts.BookVoiceMap
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.MiMoVoiceStore
import com.linguareader.app.tts.MultiVoiceStatus
import com.linguareader.app.tts.MultiVoiceStatusKind
import com.linguareader.app.tts.MultiVoiceSupport
import com.linguareader.app.tts.SystemTtsEngines
import com.linguareader.app.tts.SystemTtsVoices
import com.linguareader.app.tts.SystemVoiceAnnotation
import com.linguareader.app.tts.SystemVoiceInfo
import com.linguareader.app.tts.SystemVoiceStore
import com.linguareader.app.tts.TtsEngineMode
import com.linguareader.app.tts.TtsLanguage
import com.linguareader.app.tts.TtsPlaybackController
import com.linguareader.app.tts.VoiceAudition
import com.linguareader.app.tts.VoiceCharacter
import com.linguareader.app.tts.VoiceLibrary
import com.linguareader.app.tts.VoicePicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 多角色音色设置（PLAN-MULTI-VOICE §8, M4）。
 *
 * 开关随听书设置一起保存；角色/旁白的音色选择是即时生效的（写入书级音色映射的
 * 锁定项并让听书服务重载），可随时「恢复自动」交回自动分配。
 */
@Composable
internal fun MultiVoiceSection(
    settings: CloudTtsSettings,
    onSettingsChange: (CloudTtsSettings) -> Unit,
    books: List<Book>,
    preselectedBook: Book?
) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 本区块住在 ModalBottomSheet / AI 抽屉里，全局 Snackbar 会被弹层整个盖住，
    // 所以反馈走弹层内联状态（SettingsStatus 模式），不再出 Snackbar。
    var inlineNotice by remember { mutableStateOf<SettingsStatus?>(null) }
    LaunchedEffect(inlineNotice) {
        if (inlineNotice != null) {
            delay(2500)
            inlineNotice = null
        }
    }
    fun notify(value: SettingsStatus?) {
        inlineNotice = value
    }
    val engineSupported = MultiVoiceSupport.engineSupportsMultiVoice(
        settings,
        MultiVoiceSupport.systemUsableVoiceCount(context)
    )
    // 云引擎均支持多角色；SYSTEM（M5b §13.6）条件置灰 + 标注入口。
    val systemMode = settings.mode == TtsEngineMode.SYSTEM
    val auditionFailed = stringResource(R.string.multivoice_audition_failed)

    var bookId by remember(preselectedBook?.id) {
        mutableStateOf(preselectedBook?.id ?: books.firstOrNull()?.id.orEmpty())
    }
    var library by remember { mutableStateOf(VoiceLibrary()) }
    var voiceMap by remember { mutableStateOf<BookVoiceMap?>(null) }
    var characters by remember { mutableStateOf<List<VoiceCharacter>>(emptyList()) }
    var status by remember { mutableStateOf<MultiVoiceStatus?>(null) }
    var playingVoice by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf<VoicePickerTarget?>(null) }
    var annotating by remember { mutableStateOf(false) }
    var addingCharacter by remember { mutableStateOf(false) }
    // 只记名字：增删别名/改属性后 characters 会整体刷新，弹层从最新列表取数据。
    var editTargetName by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<VoiceCharacter?>(null) }
    val editTarget = characters.firstOrNull { it.name == editTargetName }

    val bookTitle = (preselectedBook ?: books.firstOrNull { it.id == bookId })?.title

    suspend fun reload() {
        if (!settings.multiVoiceEnabled || !engineSupported || bookId.isBlank()) return
        loading = true
        val loadedLibrary = runCatching { MultiVoiceSupport.library(context, settings) }
            .getOrDefault(VoiceLibrary())
        val roster = runCatching { MultiVoiceSupport.characters(context, bookId) }
            .getOrDefault(emptyList())
        val map = runCatching {
            MultiVoiceSupport.voiceMapRepository(context).ensureFor(
                bookId = bookId,
                library = loadedLibrary,
                narratorLanguages = MultiVoiceSupport.NARRATOR_LANGUAGES,
                reserved = MultiVoiceSupport.reservedVoices(settings)
            )
        }.getOrNull()
        library = loadedLibrary
        characters = roster
        voiceMap = map
        status = MultiVoiceSupport.status(
            taggingReady = MultiVoiceSupport.taggingReady(context),
            rosterSize = roster.size,
            library = loadedLibrary,
            map = map
        )
        loading = false
    }

    LaunchedEffect(bookId, settings.mode, settings.multiVoiceEnabled, reloadToken) {
        reload()
    }

    /** 选定音色 = 锁定该说话人；服务重载后下一句生效。 */
    fun applyVoice(target: VoicePickerTarget, voiceId: String) {
        scope.launch {
            val repository = MultiVoiceSupport.voiceMapRepository(context)
            voiceMap = if (target.narratorLanguage != null) {
                repository.lockNarrator(bookId, target.narratorLanguage, voiceId)
            } else {
                repository.lock(bookId, target.speaker, voiceId)
            }
            TtsPlaybackController.onCloudSettingsChanged(context)
            notify(SettingsStatus.success(context.getString(R.string.multivoice_locked_notice, target.title)))
            reloadToken++
        }
    }

    /** 恢复自动：解除锁定并立刻重跑一次分配。 */
    fun releaseVoice(target: VoicePickerTarget) {
        scope.launch {
            val repository = MultiVoiceSupport.voiceMapRepository(context)
            val speaker = target.narratorLanguage?.let { BookVoiceMap.narratorKey(it) } ?: target.speaker
            repository.unlock(bookId, speaker)
            TtsPlaybackController.onCloudSettingsChanged(context)
            notify(SettingsStatus.success(context.getString(R.string.multivoice_released_notice, target.title)))
            reloadToken++
        }
    }

    fun audition(speaker: String, voiceId: String) {
        if (voiceId.isBlank()) return
        if (playingVoice == voiceId) {
            VoiceAudition.stop()
            playingVoice = null
            return
        }
        playingVoice = voiceId
        scope.launch {
            val voiceLanguage = library.byId(voiceId)?.language ?: TtsLanguage.ENGLISH
            val text = MultiVoiceSupport.sampleText(speaker, voiceLanguage)
            VoiceAudition.play(
                context = context,
                settings = settings,
                voiceId = voiceId,
                text = text,
                onFinished = { if (playingVoice == voiceId) playingVoice = null }
            ).onFailure {
                notify(SettingsStatus.danger(it.message ?: auditionFailed))
                playingVoice = null
            }
        }
    }

    /** 标注对话框里的逐音色试听：语言取音色 locale 归一化结果（未知则按英文）。 */
    fun auditionSystemVoice(voice: SystemVoiceInfo) {
        if (playingVoice == voice.name) {
            VoiceAudition.stop()
            playingVoice = null
            return
        }
        playingVoice = voice.name
        scope.launch {
            val language = voice.assignerLanguage.ifBlank { TtsLanguage.ENGLISH }
            val text = MultiVoiceSupport.sampleText("", language)
            VoiceAudition.play(
                context = context,
                settings = settings,
                voiceId = voice.name,
                text = text,
                onFinished = { if (playingVoice == voice.name) playingVoice = null }
            ).onFailure {
                notify(SettingsStatus.danger(it.message ?: auditionFailed))
                playingVoice = null
            }
        }
    }

    /** 手动添加角色：写进术语表（origin=manual），成功后刷新角色列表。 */
    fun addCharacter(name: String, gender: String): Boolean {
        if (bookId.isBlank()) {
            notify(SettingsStatus.danger(context.getString(R.string.multivoice_book_required)))
            return false
        }
        val clean = name.trim()
        if (clean.isEmpty()) {
            notify(SettingsStatus.danger(context.getString(R.string.multivoice_name_required)))
            return false
        }
        if (characters.any { it.name.equals(clean, ignoreCase = true) }) {
            notify(SettingsStatus.danger(context.getString(R.string.multivoice_character_exists, clean)))
            return false
        }
        scope.launch {
            val created = runCatching {
                MultiVoiceSupport.glossaryRepository(context)
                    .addManualCharacter(bookId, clean, gender)
            }.getOrNull()
            if (created == null) {
                notify(SettingsStatus.danger(context.getString(R.string.multivoice_add_failed)))
                return@launch
            }
            TtsPlaybackController.onCloudSettingsChanged(context)
            notify(SettingsStatus.success(context.getString(R.string.multivoice_character_added, clean)))
            reloadToken++
        }
        return true
    }

    /** 给角色加别名：写进术语表条目，LLM 标注的角色表随之更新。 */
    fun addAlias(character: VoiceCharacter, alias: String): Boolean {
        val clean = alias.trim()
        if (clean.isEmpty()) {
            notify(SettingsStatus.danger(context.getString(R.string.multivoice_alias_required)))
            return false
        }
        if (clean.equals(character.name, ignoreCase = true) ||
            character.aliases.any { it.equals(clean, ignoreCase = true) }
        ) {
            notify(SettingsStatus.danger(context.getString(R.string.multivoice_alias_exists)))
            return false
        }
        scope.launch {
            val updated = runCatching {
                MultiVoiceSupport.glossaryRepository(context)
                    .addAlias(bookId, character.name, clean)
            }.getOrNull()
            if (updated == null) {
                notify(SettingsStatus.danger(context.getString(R.string.multivoice_add_failed)))
                return@launch
            }
            notify(SettingsStatus.success(context.getString(R.string.multivoice_alias_added, clean)))
            reloadToken++
        }
        return true
    }

    /** 删除角色的一个别名。 */
    fun removeAlias(character: VoiceCharacter, alias: String) {
        scope.launch {
            val updated = runCatching {
                MultiVoiceSupport.glossaryRepository(context)
                    .removeAlias(bookId, character.name, alias)
            }.getOrNull()
            if (updated == null) {
                notify(SettingsStatus.danger(context.getString(R.string.multivoice_add_failed)))
                return@launch
            }
            notify(SettingsStatus.success(context.getString(R.string.multivoice_alias_removed, alias.trim())))
            reloadToken++
        }
    }

    /** 编辑角色：更新 性别/年龄组/风格/重要性，成功后刷新角色表。 */
    fun updateCharacterProperties(
        character: VoiceCharacter,
        gender: String,
        ageGroup: String,
        style: List<String>,
        importance: String
    ): Boolean {
        if (characters.none { it.name.equals(character.name, ignoreCase = true) }) {
            notify(SettingsStatus.danger(context.getString(R.string.multivoice_edit_failed)))
            return false
        }
        scope.launch {
            val updated = runCatching {
                MultiVoiceSupport.glossaryRepository(context)
                    .updateCharacter(bookId, character.name, gender, ageGroup, style, importance)
            }.getOrNull()
            if (updated == null) {
                notify(SettingsStatus.danger(context.getString(R.string.multivoice_edit_failed)))
                return@launch
            }
            notify(SettingsStatus.success(context.getString(R.string.multivoice_character_updated, character.name)))
            editTargetName = null
            reloadToken++
        }
        return true
    }

    /** 删除角色：清 glossary 条目 + 音色映射（含锁定），自动分配随之释放。 */
    fun deleteCharacter(character: VoiceCharacter) {
        scope.launch {
            val removedGlossary = runCatching {
                MultiVoiceSupport.glossaryRepository(context).remove(bookId, character.name)
            }.getOrNull()
            val clearedMap = runCatching {
                MultiVoiceSupport.voiceMapRepository(context)
                    .removeCharacter(bookId, character.name)
            }.getOrNull()
            if (removedGlossary == null) {
                notify(SettingsStatus.danger(context.getString(R.string.multivoice_delete_failed)))
                return@launch
            }
            TtsPlaybackController.onCloudSettingsChanged(context)
            notify(SettingsStatus.success(context.getString(R.string.multivoice_character_deleted, character.name)))
            deleteTarget = null
            reloadToken++
        }
    }

    Spacer(Modifier.height(SectionRuleGap))
    HorizontalDivider(color = t.colors.outline)
    Spacer(Modifier.height(SectionGap))
    // 「多角色朗读」总开关行：标题 + 随状态变化的说明 + 尾部开关，由 SettingRow 统一
    // 行高与开关配色。**开关走 trailing 槽而不是 checked 槽**：SettingRow 的 checked 槽
    // 不支持 enabled，而这一行的开关在引擎不支持时必须置灰（MultiVoiceSupport 判定）。
    // 与 AiDrawerSheet 的「联网语境翻译」行同一写法。
    SettingRow(
        title = stringResource(R.string.multivoice_title),
        subtitle = when {
            engineSupported -> stringResource(R.string.multivoice_subtitle)
            systemMode -> stringResource(R.string.multi_voice_annotate_hint)
            else -> stringResource(R.string.multivoice_unsupported)
        },
        trailing = {
            Switch(
                checked = settings.multiVoiceEnabled && engineSupported,
                enabled = engineSupported,
                onCheckedChange = { onSettingsChange(settings.copy(multiVoiceEnabled = it)) },
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

    // 放在「不支持即提前 return」之前：标注保存成功、引擎不支持时的操作反馈
    // 也必须能展示出来（历史上对话框渲染点就吃过提前 return 的亏）。
    inlineNotice?.let {
        Spacer(Modifier.height(LabelGap))
        SettingsStatusText(it)
    }

    // M5c（§13.5）：SYSTEM 模式三态引导（推荐 / 可切换 / 未安装），纯提示不自动切换。
    if (systemMode) {
        val openSettingsFailed = stringResource(R.string.multi_voice_system_settings_open_failed)
        SystemEngineGuidance(
            onOpenSettingsFailed = { notify(SettingsStatus.danger(openSettingsFailed)) }
        )
    }

    if (systemMode && !engineSupported) {
        LinguaTextButton(
            text = stringResource(R.string.multi_voice_annotate_entry),
            onClick = { annotating = true }
        )
    }

    // 标注对话框必须放在提前 return 之前：SYSTEM 引擎未完成标注时 engineSupported == false，
    // 否则入口按钮点了也不会弹出对话框。
    if (annotating) {
        SystemVoiceAnnotateDialog(
            playingVoice = playingVoice,
            onAudition = { voice -> auditionSystemVoice(voice) },
            onSave = { annotations ->
                scope.launch {
                    val enginePackage = SystemTtsVoices.currentEngine(context)
                    if (enginePackage.isNotBlank()) {
                        // 以 voiceName 为 key 覆盖合并（对话框已把存量标注并入草稿）。
                        SystemVoiceStore.saveAnnotations(context, enginePackage, annotations)
                        SystemVoiceStore.setCurrentEngine(context, enginePackage)
                    }
                    notify(SettingsStatus.success(context.getString(R.string.multi_voice_saved)))
                    annotating = false
                    reloadToken++
                }
            },
            onDismiss = { annotating = false }
        )
    }

    if (!engineSupported || !settings.multiVoiceEnabled) return

    Spacer(Modifier.height(FieldGap))
    if (preselectedBook == null) {
        if (books.isEmpty()) {
            Text(stringResource(R.string.multivoice_no_books), color = t.colors.inkSoft)
            return
        }
        var bookMenu by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.multivoice_book_label),
                style = t.typography.label,
                color = t.colors.inkSoft
            )
            Spacer(Modifier.width(FieldGap))
            Box(Modifier.weight(1f)) {
                // 刻意保留 Material TextButton：内容是「占满宽度的书名 + 下拉箭头」复合行，
                // 需要同时放**两段可伸缩文本 + 一个尾部图标**，不是「图标 + 一段文字」。
                // `LinguaIconTextButton` 是后者（icon 在左、text 单段），套不上这个结构。
                // 配色/字级全取 token。
                TextButton(
                    onClick = { bookMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = t.colors.ink)
                ) {
                    Text(
                        bookTitle ?: stringResource(R.string.multivoice_pick_book),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        color = t.colors.ink
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = t.colors.inkSoft)
                }
                DropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                    books.forEach { candidate ->
                        DropdownMenuItem(
                            text = {
                                Text(candidate.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            onClick = {
                                bookId = candidate.id
                                bookMenu = false
                            }
                        )
                    }
                }
            }
        }
    } else {
        Text(
            stringResource(R.string.multivoice_current_book, preselectedBook.title),
            style = t.typography.caption,
            color = t.colors.inkSoft
        )
    }

    status?.let {
        Spacer(Modifier.height(LabelGap))
        Text(statusText(it), style = t.typography.label, color = t.colors.inkSoft)
    }

    if (loading) {
        Spacer(Modifier.height(FieldGap))
        CircularProgressIndicator(color = t.colors.accent, modifier = Modifier.size(LoadingSpinnerSize))
        return
    }

    val map = voiceMap
    if (map == null || library.isEmpty) return

    // MiMo 专属音色（多角色服务）：预置音色之外的「设计/复刻」音色；
    // 建好后会进音色库，可直接分给下方任意角色（M3 分配器无感知差异）。
    if (settings.mode == TtsEngineMode.MIMO) {
        MiMoCustomVoicesPanel(
            context = context,
            scope = scope,
            onChanged = { reloadToken++ },
            notify = { notify(it) },
            playingVoice = playingVoice,
            onAudition = { voice -> audition(voice.name, voice.id) }
        )
    }

    // 分区标题换成 SectionHeader：它的上下留白就是这一屏原本「区块间 12dp + 标题」的
    // token 版（spacing.l / spacing.s），不再另外叠 Spacer。
    SectionHeader(text = stringResource(R.string.multivoice_narrator_section))
    MultiVoiceSupport.NARRATOR_LANGUAGES.forEach { language ->
        val target = VoicePickerTarget(
            speaker = "narrator",
            title = if (language == TtsLanguage.CHINESE) {
                stringResource(R.string.multivoice_narrator_zh)
            } else {
                stringResource(R.string.multivoice_narrator_en)
            },
            language = language,
            gender = null,
            narratorLanguage = language
        )
        VoiceRow(
            title = target.title,
            currentVoice = map.narratorFor(language).orEmpty(),
            locked = map.isLocked(BookVoiceMap.narratorKey(language)),
            playing = playingVoice != null && playingVoice == map.narratorFor(language),
            onOpenPicker = { picker = target },
            onAudition = { audition("narrator", map.narratorFor(language).orEmpty()) },
            onRelease = { releaseVoice(target) }
        )
    }

    // 「角色」分区标题 + 尾部「添加角色」入口：SectionHeader 的 trailing 槽正是为此存在，
    // 直接吃掉了原来手写的「标题 + weight(1f) + 按钮」三件套。
    SectionHeader(
        text = stringResource(R.string.multivoice_characters_section),
        trailing = {
            // 刻意保留 Material TextButton：这个槽位需要 accent 色的「图标 + 文字」小按钮，
            // 且要贴着 SectionHeader 的 trailing 边缘。
            // `LinguaIconTextButton` 已存在（`01461c0`），但它默认 `PAGE` 几何（48dp 最小高），
            // 放进 trailing 会把 SectionHeader 整行撑高；若改用 `layout = INLINE` 则可行。
            // 当前保留本地写法是因为改动未验证视觉基线——**不是组件缺能力**。
            TextButton(
                onClick = { addingCharacter = true },
                colors = ButtonDefaults.textButtonColors(contentColor = t.colors.accent)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = t.colors.accent,
                    modifier = Modifier.size(SmallIconSize)
                )
                Spacer(Modifier.width(InlineIconGap))
                Text(stringResource(R.string.multivoice_add_character), style = t.typography.body)
            }
        }
    )
    if (bookId.isBlank()) {
        Text(
            stringResource(R.string.multivoice_book_required),
            style = t.typography.label,
            color = t.colors.inkSoft
        )
        return
    }
    if (characters.isEmpty()) {
        Text(
            stringResource(R.string.multivoice_no_characters),
            style = t.typography.label,
            color = t.colors.inkSoft
        )
        return
    }
    characters
        .sortedWith(compareByDescending<VoiceCharacter> { it.importanceRank }.thenBy { it.key })
        .forEach { character ->
            val current = map.voiceFor(character.name).orEmpty()
            val target = VoicePickerTarget(
                speaker = character.name,
                title = character.name,
                language = character.language,
                gender = character.gender,
                narratorLanguage = null
            )
            VoiceRow(
                title = character.name,
                subtitle = characterSubtitle(character),
                currentVoice = current,
                locked = map.isLocked(character.key),
                playing = playingVoice != null && playingVoice == current,
                onOpenPicker = { picker = target },
                onAudition = { audition(character.name, current) },
                onRelease = { releaseVoice(target) },
                onEdit = { editTargetName = character.name },
                onDelete = { deleteTarget = character }
            )
        }
    Spacer(Modifier.height(LabelGap))
    Text(
        stringResource(R.string.multivoice_lock_hint),
        style = t.typography.label,
        color = t.colors.inkFaint
    )
    Spacer(Modifier.height(t.spacing.xs))
    // PLAN-MULTI-VOICE §12.3 红线：克隆音色的素材来源与 AI 合成标识。
    Text(
        stringResource(R.string.multivoice_clone_notice),
        style = t.typography.label,
        color = t.colors.inkFaint
    )

    picker?.let { target ->
        VoicePickerDialog(
            target = target,
            library = library,
            currentVoice = if (target.narratorLanguage != null) {
                map.narratorFor(target.narratorLanguage).orEmpty()
            } else {
                map.voiceFor(target.speaker).orEmpty()
            },
            locked = if (target.narratorLanguage != null) {
                map.isLocked(BookVoiceMap.narratorKey(target.narratorLanguage))
            } else {
                map.isLocked(BookVoiceMap.keyOf(target.speaker))
            },
            playingVoice = playingVoice,
            onAudition = { voice -> audition(target.speaker, voice) },
            onPick = { voice ->
                applyVoice(target, voice)
                picker = null
            },
            onRelease = {
                releaseVoice(target)
                picker = null
            },
            onDismiss = { picker = null }
        )
    }

    if (addingCharacter) {
        AddCharacterDialog(
            onConfirm = { name, gender ->
                if (addCharacter(name, gender)) addingCharacter = false
            },
            onDismiss = { addingCharacter = false }
        )
    }

    editTarget?.let { character ->
        EditCharacterDialog(
            character = character,
            onSave = { gender, ageGroup, style, importance ->
                if (updateCharacterProperties(character, gender, ageGroup, style, importance)) {
                    editTargetName = null
                }
            },
            onAddAlias = { alias -> addAlias(character, alias) },
            onRemoveAlias = { alias -> removeAlias(character, alias) },
            onDismiss = { editTargetName = null }
        )
    }

    deleteTarget?.let { character ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = t.colors.cardSurface,
            title = { Text(stringResource(R.string.multivoice_confirm_delete_title)) },
            text = { Text(stringResource(R.string.multivoice_confirm_delete_body, character.name)) },
            confirmButton = {
                // 删除是破坏性动作：刻意不是 LinguaTextButton（原语只有一种主色 accent），
                // 保留 Material TextButton 但配色取 token 的 danger —— 与 PacksSheet 的
                // DangerTextButton 同一打法，不写裸色值。文案与确认逻辑零改动。
                TextButton(
                    onClick = { deleteCharacter(character) },
                    colors = ButtonDefaults.textButtonColors(contentColor = t.colors.danger)
                ) {
                    Text(
                        stringResource(R.string.multivoice_delete_confirm),
                        color = t.colors.danger
                    )
                }
            },
            dismissButton = {
                LinguaTextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { deleteTarget = null }
                )
            }
        )
    }
}
