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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linguareader.app.data.Book
import com.linguareader.app.tts.BookVoiceMap
import com.linguareader.app.tts.CloudTtsSettings
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
    val engineSupported = MultiVoiceSupport.engineSupportsMultiVoice(settings)

    var bookId by remember(preselectedBook?.id) {
        mutableStateOf(preselectedBook?.id ?: books.firstOrNull()?.id.orEmpty())
    }
    var library by remember { mutableStateOf(VoiceLibrary()) }
    var voiceMap by remember { mutableStateOf<BookVoiceMap?>(null) }
    var characters by remember { mutableStateOf<List<VoiceCharacter>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var auditionError by remember { mutableStateOf<String?>(null) }
    var playingVoice by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf<VoicePickerTarget?>(null) }

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
        status = MultiVoiceSupport.statusMessage(
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
            reloadToken++
        }
    }

    fun audition(speaker: String, voiceId: String) {
        if (voiceId.isBlank()) return
        auditionError = null
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
                auditionError = it.message ?: "试听失败"
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
                "多角色音色（实验）",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (engineSupported) {
                    "按「谁在说话」给旁白与各角色分配不同音色；需要 AI 语境档案提供角色表。"
                } else {
                    "本地 Piper 只有 2 个内置音色，系统语音音色不可控，均不支持多角色。"
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
            Text("书架还没有书，导入后即可分配角色音色。", color = InkSoft)
            return
        }
        var bookMenu by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("书目", style = MaterialTheme.typography.labelLarge, color = InkSoft)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { bookMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        bookTitle ?: "选择书籍",
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
            "本书：" + preselectedBook.title,
            style = MaterialTheme.typography.labelMedium,
            color = InkSoft
        )
    }

    status?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.labelSmall, color = InkSoft)
    }
    auditionError?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = Danger)
    }

    if (loading) {
        Spacer(Modifier.height(10.dp))
        CircularProgressIndicator(color = Accent, modifier = Modifier.size(26.dp))
        return
    }

    val map = voiceMap
    if (map == null || library.isEmpty) return

    Spacer(Modifier.height(12.dp))
    Text("旁白音色", style = MaterialTheme.typography.labelLarge, color = InkSoft)
    MultiVoiceSupport.NARRATOR_LANGUAGES.forEach { language ->
        val target = VoicePickerTarget(
            speaker = "narrator",
            title = if (language == TtsLanguage.CHINESE) "中文旁白" else "英文旁白",
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
    Text("角色音色", style = MaterialTheme.typography.labelLarge, color = InkSoft)
    if (characters.isEmpty()) {
        Text(
            "本书还没有角色条目：在 AI 中心生成语境档案，或在术语表手动添加角色。",
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
        "选定音色即锁定该角色（名字旁的锁形图标表示已锁定，点解锁图标或「恢复自动」交回自动分配）；" +
            "未锁定的角色会随新章节的共现关系自动微调。",
        style = MaterialTheme.typography.labelSmall,
        color = InkFaint
    )
    Spacer(Modifier.height(4.dp))
    // PLAN-MULTI-VOICE §12.3 红线：克隆音色的素材来源与 AI 合成标识。
    Text(
        "克隆音色请只使用自备或已获授权的参考音频，不要克隆真人/演员声音；" +
            "所有朗读音频均为 AI 合成，如需删除本机缓存可在书籍删除时一并清除。",
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

private fun characterSubtitle(character: VoiceCharacter): String {
    val parts = mutableListOf<String>()
    when (character.importance.lowercase()) {
        "major" -> parts += "主要角色"
        "medium" -> parts += "次要角色"
        else -> parts += "配角"
    }
    when (character.gender.lowercase()) {
        "male" -> parts += "男"
        "female" -> parts += "女"
    }
    if (character.style.isNotEmpty()) parts += character.style.take(2).joinToString("/")
    return parts.joinToString(" · ")
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
                        contentDescription = "已锁定音色",
                        tint = Accent,
                        modifier = Modifier.padding(start = 4.dp).size(13.dp)
                    )
                }
            }
            Text(
                currentVoice.ifBlank { "自动分配" },
                style = MaterialTheme.typography.labelSmall,
                color = if (currentVoice.isBlank()) InkFaint else InkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = InkFaint)
            }
        }
        TextButton(onClick = onOpenPicker) { Text("更换") }
        IconButton(
            onClick = onAudition,
            enabled = currentVoice.isNotBlank(),
            modifier = Modifier.semantics {
                contentDescription = if (playing) "停止试听" else "试听 " + title
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
                modifier = Modifier.semantics { contentDescription = "恢复自动分配 " + title }
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
    val groups = remember(library, query, target) {
        VoicePicker.groups(library.voices, target.language, target.gender, query)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = { Text(target.title + " · 选择音色") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索音色（名字/语言/性别/风格）") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (groups.isEmpty()) {
                    Text("没有匹配的音色。", color = InkSoft)
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
                                            contentDescription =
                                                "试听 " + option.voice.id
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
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            if (locked) {
                TextButton(onClick = onRelease) { Text("恢复自动") }
            }
        }
    )
}
