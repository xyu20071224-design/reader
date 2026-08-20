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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.linguareader.app.tts.VoiceInfo
import com.linguareader.app.tts.VoiceLibrary
import kotlinx.coroutines.launch

/**
 * 多角色音色设置（PLAN-MULTI-VOICE §8, M4）。
 *
 * The switch itself is part of the listening settings and saved with them; the
 * per-character choices are applied immediately (they are locks in the book
 * voice map) and the playback service is asked to reload right away.
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
    var loading by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }

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

    fun applyVoice(speaker: String, voiceId: String, language: String?) {
        scope.launch {
            val repository = MultiVoiceSupport.voiceMapRepository(context)
            voiceMap = if (language != null) {
                repository.lockNarrator(bookId, language, voiceId)
            } else {
                repository.lock(bookId, speaker, voiceId)
            }
            // The service reloads the mapping on reconfigure, so the change is
            // audible from the next sentence on.
            TtsPlaybackController.onCloudSettingsChanged(context)
            reloadToken++
        }
    }

    fun audition(speaker: String, voiceId: String) {
        if (voiceId.isBlank()) return
        auditionError = null
        scope.launch {
            val voiceLanguage = library.byId(voiceId)?.language ?: TtsLanguage.ENGLISH
            val text = MultiVoiceSupport.sampleText(speaker, voiceLanguage)
            VoiceAudition.play(context, settings, voiceId, text)
                .onFailure { auditionError = it.message ?: "试听失败" }
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
                    Text("▾", color = InkSoft)
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
        Text("本书：" + preselectedBook.title, style = MaterialTheme.typography.labelMedium, color = InkSoft)
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
        VoiceRow(
            title = if (language == TtsLanguage.CHINESE) "中文旁白" else "英文旁白",
            locked = map.isLocked(BookVoiceMap.narratorKey(language)),
            currentVoice = map.narratorFor(language).orEmpty(),
            candidates = candidatesFor(library, language, null),
            onSelect = { applyVoice("narrator", it, language) },
            onAudition = { audition("narrator", it) }
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
    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        characters
            .sortedWith(compareByDescending<VoiceCharacter> { it.importanceRank }.thenBy { it.key })
            .forEach { character ->
                VoiceRow(
                    title = character.name,
                    subtitle = characterSubtitle(character),
                    locked = map.isLocked(character.key),
                    currentVoice = map.voiceFor(character.name).orEmpty(),
                    candidates = candidatesFor(library, character.language, character.gender),
                    onSelect = { applyVoice(character.name, it, null) },
                    onAudition = { audition(character.name, it) }
                )
            }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "选定音色即锁定该角色（自动分配不再改动它）；未锁定的角色会随新章节的共现关系自动微调。",
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
}

/** Recommended candidates first (language + gender match), then the whole library. */
private fun candidatesFor(
    library: VoiceLibrary,
    language: String,
    gender: String?
): List<VoiceInfo> {
    val recommended = library.voices.filter { voice ->
        voice.available && voice.speaks(language) &&
            (gender.isNullOrBlank() || voice.gender.isBlank() || voice.gender.equals(gender, true))
    }
    val rest = library.voices.filter { it.available && it !in recommended }
    return recommended + rest
}

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

/** One assignable speaker: name, current voice picker and an audition button. */
@Composable
private fun VoiceRow(
    title: String,
    currentVoice: String,
    candidates: List<VoiceInfo>,
    locked: Boolean,
    onSelect: (String) -> Unit,
    onAudition: (String) -> Unit,
    subtitle: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (locked) title + " 🔒" else title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = InkFaint)
            }
        }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(
                    currentVoice.ifBlank { "自动" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Ink
                )
                Text("▾", color = InkSoft)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                candidates.take(60).forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voiceLabel(voice), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            expanded = false
                            onSelect(voice.id)
                        }
                    )
                }
            }
        }
        TextButton(
            onClick = { onAudition(currentVoice) },
            enabled = currentVoice.isNotBlank()
        ) { Text("试听") }
    }
}

private fun voiceLabel(voice: VoiceInfo): String {
    val tags = mutableListOf<String>()
    when (voice.gender.lowercase()) {
        "male" -> tags += "男"
        "female" -> tags += "女"
    }
    if (voice.language.isNotBlank()) tags += voice.language
    if (voice.style.isNotEmpty()) tags += voice.style.first()
    return if (tags.isEmpty()) voice.id else voice.id + "（" + tags.joinToString("·") + "）"
}
