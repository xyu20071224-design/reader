package com.linguareader.app

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.linguareader.app.data.Book
import com.linguareader.app.tts.BookVoiceMap
import com.linguareader.app.tts.CloudTtsSettings
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalAppSnackbar.current
    val engineSupported = MultiVoiceSupport.engineSupportsMultiVoice(
        settings,
        MultiVoiceSupport.systemUsableVoiceCount(context)
    )
    // M5b（§13.6）：Piper 仍一律置灰；SYSTEM 改为条件置灰 + 标注入口。
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
            snackbar.show(context.getString(R.string.multivoice_locked_notice, target.title))
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
            snackbar.show(context.getString(R.string.multivoice_released_notice, target.title))
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
                snackbar.show(it.message ?: auditionFailed)
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
                snackbar.show(it.message ?: auditionFailed)
                playingVoice = null
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    HorizontalDivider(color = Ink.copy(alpha = .1f))
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.multivoice_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                when {
                    engineSupported -> stringResource(R.string.multivoice_subtitle)
                    systemMode -> stringResource(R.string.multi_voice_annotate_hint)
                    else -> stringResource(R.string.multivoice_unsupported)
                },
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft
            )
        }
        Switch(
            checked = settings.multiVoiceEnabled && engineSupported,
            enabled = engineSupported,
            onCheckedChange = { onSettingsChange(settings.copy(multiVoiceEnabled = it)) }
        )
    }

    // M5c（§13.5）：SYSTEM 模式三态引导（推荐 / 可切换 / 未安装），纯提示不自动切换。
    if (systemMode) {
        val openSettingsFailed = stringResource(R.string.multi_voice_system_settings_open_failed)
        SystemEngineGuidance(
            onOpenSettingsFailed = { scope.launch { snackbar.show(openSettingsFailed) } }
        )
    }

    if (systemMode && !engineSupported) {
        TextButton(onClick = { annotating = true }) {
            Text(stringResource(R.string.multi_voice_annotate_entry))
        }
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
                    snackbar.show(context.getString(R.string.multi_voice_saved))
                    annotating = false
                    reloadToken++
                }
            },
            onDismiss = { annotating = false }
        )
    }

    if (!engineSupported || !settings.multiVoiceEnabled) return

    Spacer(Modifier.height(10.dp))
    if (preselectedBook == null) {
        if (books.isEmpty()) {
            Text(stringResource(R.string.multivoice_no_books), color = InkSoft)
            return
        }
        var bookMenu by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.multivoice_book_label),
                style = MaterialTheme.typography.labelLarge,
                color = InkSoft
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { bookMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        bookTitle ?: stringResource(R.string.multivoice_pick_book),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        color = Ink
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = InkSoft)
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
            style = MaterialTheme.typography.labelMedium,
            color = InkSoft
        )
    }

    status?.let {
        Spacer(Modifier.height(6.dp))
        Text(statusText(it), style = MaterialTheme.typography.labelSmall, color = InkSoft)
    }

    if (loading) {
        Spacer(Modifier.height(10.dp))
        CircularProgressIndicator(color = Accent, modifier = Modifier.size(26.dp))
        return
    }

    val map = voiceMap
    if (map == null || library.isEmpty) return

    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.multivoice_narrator_section),
        style = MaterialTheme.typography.labelLarge,
        color = InkSoft
    )
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

    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.multivoice_characters_section),
        style = MaterialTheme.typography.labelLarge,
        color = InkSoft
    )
    if (characters.isEmpty()) {
        Text(
            stringResource(R.string.multivoice_no_characters),
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft
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
                onRelease = { releaseVoice(target) }
            )
        }
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.multivoice_lock_hint),
        style = MaterialTheme.typography.labelSmall,
        color = InkFaint
    )
    Spacer(Modifier.height(4.dp))
    // PLAN-MULTI-VOICE §12.3 红线：克隆音色的素材来源与 AI 合成标识。
    Text(
        stringResource(R.string.multivoice_clone_notice),
        style = MaterialTheme.typography.labelSmall,
        color = InkFaint
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

}

/** 正在挑音色的对象：一个角色，或某个语言的旁白。 */
private data class VoicePickerTarget(
    val speaker: String,
    val title: String,
    val language: String,
    val gender: String?,
    val narratorLanguage: String?
)

@Composable
private fun characterSubtitle(character: VoiceCharacter): String {
    val parts = mutableListOf<String>()
    parts += when (character.importance.lowercase()) {
        "major" -> stringResource(R.string.multivoice_role_major)
        "medium" -> stringResource(R.string.multivoice_role_medium)
        else -> stringResource(R.string.multivoice_role_minor)
    }
    when (character.gender.lowercase()) {
        "male" -> parts += stringResource(R.string.multivoice_gender_male)
        "female" -> parts += stringResource(R.string.multivoice_gender_female)
    }
    if (character.style.isNotEmpty()) parts += character.style.take(2).joinToString("/")
    return parts.joinToString(" · ")
}

/** 状态提示文案：把 [MultiVoiceStatus] 映射到资源字符串。 */
@Composable
private fun statusText(status: MultiVoiceStatus): String = when (status.kind) {
    MultiVoiceStatusKind.NO_LIBRARY -> stringResource(R.string.multivoice_status_no_library)
    MultiVoiceStatusKind.NO_ROSTER -> stringResource(R.string.multivoice_status_no_roster)
    MultiVoiceStatusKind.RULE_MODE -> stringResource(R.string.multivoice_status_rule_mode)
    MultiVoiceStatusKind.NO_MAP -> stringResource(R.string.multivoice_status_no_map)
    MultiVoiceStatusKind.SHARED_VOICES -> pluralStringResource(
        R.plurals.multivoice_status_shared,
        status.characters,
        status.characters,
        status.shared
    )

    MultiVoiceStatusKind.READY -> pluralStringResource(
        R.plurals.multivoice_status_ready,
        status.characters,
        status.characters
    )
}

/** 一行可分配的说话人：名字、当前音色、试听/停止、恢复自动。 */
@Composable
private fun VoiceRow(
    title: String,
    currentVoice: String,
    locked: Boolean,
    playing: Boolean,
    onOpenPicker: () -> Unit,
    onAudition: () -> Unit,
    onRelease: () -> Unit,
    subtitle: String? = null
) {
    // semantics{} 不是 composable 作用域，标签先取出来。
    val auditionLabel = stringResource(R.string.multivoice_audition, title)
    val stopAuditionLabel = stringResource(R.string.multivoice_audition_stop)
    val releaseLabel = stringResource(R.string.multivoice_release, title)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (locked) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = stringResource(R.string.multivoice_locked),
                        tint = Accent,
                        modifier = Modifier.padding(start = 4.dp).size(13.dp)
                    )
                }
            }
            Text(
                currentVoice.ifBlank { stringResource(R.string.multivoice_auto) },
                style = MaterialTheme.typography.labelSmall,
                color = if (currentVoice.isBlank()) InkFaint else InkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = InkFaint)
            }
        }
        TextButton(onClick = onOpenPicker) { Text(stringResource(R.string.multivoice_change)) }
        IconButton(
            onClick = onAudition,
            enabled = currentVoice.isNotBlank(),
            modifier = Modifier.semantics {
                contentDescription = if (playing) stopAuditionLabel else auditionLabel
            }
        ) {
            Icon(
                if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (playing) Accent else InkSoft,
                modifier = Modifier.size(20.dp)
            )
        }
        if (locked) {
            IconButton(
                onClick = onRelease,
                modifier = Modifier.semantics { contentDescription = releaseLabel }
            ) {
                Icon(
                    Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = InkFaint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * 音色选择弹层：搜索 + 「推荐 / 语言·性别」分组 + 逐项试听。
 *
 * Kokoro 一台服务器就有 100+ 音色，所以用可搜索的分组列表代替长下拉。
 */
@Composable
private fun VoicePickerDialog(
    target: VoicePickerTarget,
    library: VoiceLibrary,
    currentVoice: String,
    locked: Boolean,
    playingVoice: String?,
    onAudition: (String) -> Unit,
    onPick: (String) -> Unit,
    onRelease: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val auditionTemplate = stringResource(R.string.multivoice_audition, "%s")
    val auditionOf: (String) -> String = { id -> auditionTemplate.replace("%s", id) }
    val groups = remember(library, query, target) {
        VoicePicker.groups(library.voices, target.language, target.gender, query)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = { Text(stringResource(R.string.multivoice_picker_title, target.title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.multivoice_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (groups.isEmpty()) {
                    Text(stringResource(R.string.multivoice_no_match), color = InkSoft)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        groups.forEach { group ->
                            item(key = "header-" + group.title) {
                                Text(
                                    group.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = InkFaint,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                )
                            }
                            items(group.options, key = { it.voice.id }) { option ->
                                val selected = option.voice.id == currentVoice
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (selected) AccentSoft else CardSurface)
                                        .clickable { onPick(option.voice.id) }
                                        .padding(horizontal = 6.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        VoicePicker.label(option.voice),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (selected) AccentDeep else Ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { onAudition(option.voice.id) },
                                        modifier = Modifier.semantics {
                                            contentDescription = auditionOf(option.voice.id)
                                        }
                                    ) {
                                        Icon(
                                            if (playingVoice == option.voice.id) {
                                                Icons.Default.Stop
                                            } else {
                                                Icons.Default.PlayArrow
                                            },
                                            contentDescription = null,
                                            tint = if (playingVoice == option.voice.id) Accent else InkSoft,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        dismissButton = {
            if (locked) {
                TextButton(onClick = onRelease) { Text(stringResource(R.string.multivoice_release_action)) }
            }
        }
    )
}

/**
 * SYSTEM 模式三态引导（M5c, PLAN-MULTI-VOICE §13.5）：
 * 态1 当前就是 Google TTS → 推荐提示；态2 已装未启用 → 提示 + 跳系统 TTS 设置
 * （`ACTION_TTS_SETTINGS`，绝不自动切换）；态3 未安装 → 安装引导文案。
 */
@Composable
private fun SystemEngineGuidance(onOpenSettingsFailed: () -> Unit) {
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
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft
        )
        if (state == SystemTtsEngines.Guide.SWITCH_AVAILABLE) {
            // startActivity 需在非 Activity context 下加 NEW_TASK；失败静默降级为 Snackbar。
            TextButton(
                onClick = {
                    runCatching {
                        // Settings.ACTION_TTS_SETTINGS 未进公开 SDK，用其字面值。
                        context.startActivity(
                            Intent("com.android.settings.TTS_SETTINGS")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { onOpenSettingsFailed() }
                }
            ) {
                Text(stringResource(R.string.multi_voice_system_open_settings))
            }
        }
    }
}

/** 一行系统音色的标注草稿（PLAN-MULTI-VOICE §13.6）。 */
private data class SystemVoiceDraft(
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
private fun SystemVoiceAnnotateDialog(
    playingVoice: String?,
    onAudition: (SystemVoiceInfo) -> Unit,
    onSave: (List<SystemVoiceAnnotation>) -> Unit,
    onDismiss: () -> Unit
) {
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
        containerColor = CardSurface,
        title = { Text(stringResource(R.string.multi_voice_annotate_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    engine.isBlank() -> Text(
                        stringResource(R.string.multi_voice_annotate_no_engine),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft
                    )

                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.multi_voice_annotate_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSoft
                        )
                    }

                    voices.isEmpty() -> Text(
                        stringResource(R.string.multi_voice_annotate_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft
                    )

                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                        items(voices, key = { it.name }) { voice ->
                            val draft = drafts[voice.name] ?: SystemVoiceDraft()
                            // semantics{} 不是 composable 作用域，标签先取出来。
                            val auditionLabel =
                                stringResource(R.string.multivoice_audition, voice.name)
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        voice.displayName(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Ink,
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
                                            tint = if (playingVoice == voice.name) Accent else InkSoft,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Switch(
                                        checked = draft.enabled,
                                        onCheckedChange = {
                                            drafts[voice.name] = draft.copy(enabled = it)
                                        }
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            TextButton(
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
            ) { Text(stringResource(R.string.multi_voice_annotate_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 性别三态小胶囊：选中反色，未选中淡底。 */
@Composable
private fun GenderPill(label: String, selected: Boolean, onSelect: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) CardSurface else InkSoft,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else Ink.copy(alpha = .08f))
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}
