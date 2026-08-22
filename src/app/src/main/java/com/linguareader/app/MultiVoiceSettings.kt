package com.linguareader.app

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val engineSupported = MultiVoiceSupport.engineSupportsMultiVoice(settings)
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
    var addingCharacter by remember { mutableStateOf(false) }
    // 只记名字：增删别名后 characters 会整体刷新，弹层从最新列表取数据。
    var aliasTargetName by remember { mutableStateOf<String?>(null) }
    val aliasTarget = characters.firstOrNull { it.name == aliasTargetName }

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

    /** 手动添加角色：写进术语表（origin=manual），成功后刷新角色列表。 */
    fun addCharacter(name: String, gender: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) {
            snackbar.show(context.getString(R.string.multivoice_name_required))
            return false
        }
        if (characters.any { it.name.equals(clean, ignoreCase = true) }) {
            snackbar.show(context.getString(R.string.multivoice_character_exists, clean))
            return false
        }
        scope.launch {
            val created = runCatching {
                MultiVoiceSupport.glossaryRepository(context)
                    .addManualCharacter(bookId, clean, gender)
            }.getOrNull()
            if (created == null) {
                snackbar.show(context.getString(R.string.multivoice_add_failed))
                return@launch
            }
            TtsPlaybackController.onCloudSettingsChanged(context)
            snackbar.show(context.getString(R.string.multivoice_character_added, clean))
            reloadToken++
        }
        return true
    }

    /** 给角色加别名：写进术语表条目，LLM 标注的角色表随之更新。 */
    fun addAlias(character: VoiceCharacter, alias: String): Boolean {
        val clean = alias.trim()
        if (clean.isEmpty()) {
            snackbar.show(context.getString(R.string.multivoice_alias_required))
            return false
        }
        if (clean.equals(character.name, ignoreCase = true) ||
            character.aliases.any { it.equals(clean, ignoreCase = true) }
        ) {
            snackbar.show(context.getString(R.string.multivoice_alias_exists))
            return false
        }
        scope.launch {
            val updated = runCatching {
                MultiVoiceSupport.glossaryRepository(context)
                    .addAlias(bookId, character.name, clean)
            }.getOrNull()
            if (updated == null) {
                snackbar.show(context.getString(R.string.multivoice_add_failed))
                return@launch
            }
            snackbar.show(context.getString(R.string.multivoice_alias_added, clean))
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
                snackbar.show(context.getString(R.string.multivoice_add_failed))
                return@launch
            }
            snackbar.show(context.getString(R.string.multivoice_alias_removed, alias.trim()))
            reloadToken++
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
                if (engineSupported) {
                    stringResource(R.string.multivoice_subtitle)
                } else {
                    stringResource(R.string.multivoice_unsupported)
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.multivoice_characters_section),
            style = MaterialTheme.typography.labelLarge,
            color = InkSoft,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { addingCharacter = true }) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(2.dp))
            Text(stringResource(R.string.multivoice_add_character))
        }
    }
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
                onRelease = { releaseVoice(target) },
                onEditAliases = { aliasTargetName = character.name }
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

    if (addingCharacter) {
        AddCharacterDialog(
            onConfirm = { name, gender ->
                if (addCharacter(name, gender)) addingCharacter = false
            },
            onDismiss = { addingCharacter = false }
        )
    }

    aliasTarget?.let { character ->
        AliasDialog(
            character = character,
            onAdd = { alias -> addAlias(character, alias) },
            onRemove = { alias -> removeAlias(character, alias) },
            onDismiss = { aliasTargetName = null }
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

/**
 * 手动添加角色的弹层：名字必填，性别可选（空 = 未指定，交给自动分配推断）。
 * 确认时先做重名/空名校验，失败不关弹层，反馈走全局 Snackbar。
 */
@Composable
private fun AddCharacterDialog(
    onConfirm: (name: String, gender: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = { Text(stringResource(R.string.multivoice_add_character_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.multivoice_character_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.multivoice_gender_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GenderChip(
                        label = stringResource(R.string.multivoice_gender_male),
                        selected = gender == "male",
                        onClick = { gender = if (gender == "male") "" else "male" }
                    )
                    GenderChip(
                        label = stringResource(R.string.multivoice_gender_female),
                        selected = gender == "female",
                        onClick = { gender = if (gender == "female") "" else "female" }
                    )
                    GenderChip(
                        label = stringResource(R.string.multivoice_gender_unknown),
                        selected = gender.isEmpty(),
                        onClick = { gender = "" }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, gender) }) {
                Text(stringResource(R.string.multivoice_add_character))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun GenderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

/**
 * 别名编辑弹层：列出已有别名（可删），输入新别名即时添加。
 * 增删立即写库并 Snackbar 反馈；重名/空名不关弹层。
 */
@Composable
private fun AliasDialog(
    character: VoiceCharacter,
    onAdd: (alias: String) -> Boolean,
    onRemove: (alias: String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    val deleteTemplate = stringResource(R.string.multivoice_alias_delete, "%s")
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = { Text(stringResource(R.string.multivoice_alias_title, character.name)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.multivoice_alias_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(stringResource(R.string.multivoice_alias_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            if (onAdd(draft)) draft = ""
                        }
                    ) {
                        Text(stringResource(R.string.multivoice_alias_add))
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (character.aliases.isEmpty()) {
                    Text(
                        stringResource(R.string.multivoice_alias_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint
                    )
                } else {
                    character.aliases.forEach { alias ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                alias,
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onRemove(alias) },
                                modifier = Modifier.semantics {
                                    contentDescription = deleteTemplate.replace("%s", alias)
                                }
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = null,
                                    tint = InkFaint,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        }
    )
}

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
    subtitle: String? = null,
    onEditAliases: (() -> Unit)? = null
) {
    // semantics{} 不是 composable 作用域，标签先取出来。
    val auditionLabel = stringResource(R.string.multivoice_audition, title)
    val stopAuditionLabel = stringResource(R.string.multivoice_audition_stop)
    val releaseLabel = stringResource(R.string.multivoice_release, title)
    val editAliasesLabel = stringResource(R.string.multivoice_alias_edit, title)
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
        if (onEditAliases != null) {
            IconButton(
                onClick = onEditAliases,
                modifier = Modifier.semantics { contentDescription = editAliasesLabel }
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    tint = InkFaint,
                    modifier = Modifier.size(16.dp)
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
