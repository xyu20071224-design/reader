package com.linguareader.app

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.ai.BookGlossary
import com.linguareader.app.ai.GlossaryEntry
import com.linguareader.app.data.Book
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.TtsPlaybackController
import kotlinx.coroutines.launch

/**
 * "AI 中心"底部抽屉：一个入口集中管理所有 AI 能力。
 *
 *  - 头部是联网 AI 总开关：关闭后翻译（DeepSeek/Azure）与云端朗读
 *    （Azure/火山/自建）全部回到离线模式，本地词典、本地 Piper 不受影响。
 *  - 三个 Tab：翻译设置 / 听书语音（复用 [ListeningSettingsBody]）/ 术语表。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var tab by rememberSaveable { mutableStateOf(0) }

    fun setPower(on: Boolean) {
        onAiSettingsChange(aiSettings.copy(powerEnabled = on))
        val tts = CloudTtsSettings.load(context).copy(networkAiEnabled = on)
        CloudTtsSettings.save(context, tts)
        TtsPlaybackController.onCloudSettingsChanged(context)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 30.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI 中心", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "联网 AI 总开关：关闭后翻译与云端朗读全部离线。",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSoft
                    )
                }
                Switch(
                    checked = aiSettings.powerEnabled,
                    onCheckedChange = ::setPower
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DrawerTab("翻译", tab == 0) { tab = 0 }
                DrawerTab("听书语音", tab == 1) { tab = 1 }
                DrawerTab("术语表", tab == 2) { tab = 2 }
            }
            HorizontalDivider(color = Ink.copy(alpha = .1f))
            Spacer(Modifier.height(12.dp))
            // Each tab scrolls itself so nested scrollers never fight.
            Box(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                when (tab) {
                    0 -> AiTranslationSettingsBody(
                        settings = aiSettings,
                        onSave = onAiSettingsChange,
                        enabled = aiSettings.powerEnabled
                    )
                    1 -> ListeningSettingsBody(onDismiss = null, onSaved = {}, books = books)
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
    }
}

@Composable
private fun DrawerTab(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            color = if (selected) Accent else InkSoft,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * Translation settings (DeepSeek contextual AI + Azure sentence translator).
 * Mirrors the former Bookshelf AI dialog; [enabled] is false while the master
 * power switch is off, disabling every networked field.
 */
@Composable
private fun AiTranslationSettingsBody(
    settings: AiSettings,
    onSave: (AiSettings) -> Unit,
    enabled: Boolean
) {
    var deepSeekEnabled by remember(settings) { mutableStateOf(settings.enabled) }
    var apiKey by remember(settings) { mutableStateOf(settings.apiKey) }
    var baseUrl by remember(settings) { mutableStateOf(settings.baseUrl) }
    var model by remember(settings) { mutableStateOf(settings.model) }
    var azureEnabled by remember(settings) { mutableStateOf(settings.azureTranslationEnabled) }
    var azureKey by remember(settings) { mutableStateOf(settings.azureKey) }
    var azureRegion by remember(settings) { mutableStateOf(settings.azureRegion) }
    var azureEndpoint by remember(settings) { mutableStateOf(settings.azureEndpoint) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "DeepSeek 语境翻译",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = deepSeekEnabled,
                onCheckedChange = { deepSeekEnabled = it },
                enabled = enabled
            )
        }
        if (deepSeekEnabled) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("DeepSeek API Key") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("接口地址") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "启用后，书籍章节文本与点击的词句会发送到 DeepSeek 以生成/更新本书语境档案；" +
                    "未填 API Key 时自动使用不联网的本地轻量语境。",
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "关闭时查词保持纯本地词典，不发送任何文本。",
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Azure 整句翻译",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = azureEnabled,
                onCheckedChange = { azureEnabled = it },
                enabled = enabled
            )
        }
        if (azureEnabled) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = azureKey,
                onValueChange = { azureKey = it },
                label = { Text("Azure Translator Key") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = azureRegion,
                onValueChange = { azureRegion = it },
                label = { Text("区域（如 eastasia，可留空）") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = azureEndpoint,
                onValueChange = { azureEndpoint = it },
                label = { Text("接口地址") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "整句翻译会把当前句发送到 Azure AI Translator；本书术语表条目会用动态词典标记" +
                    "（<mstrans:dictionary>）随请求生效，保证专名译法一致。",
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft
            )
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                "关闭时查词面板不显示整句翻译。",
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = {
                    onSave(
                        settings.copy(
                            enabled = deepSeekEnabled,
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            model = model,
                            azureTranslationEnabled = azureEnabled,
                            azureKey = azureKey,
                            azureRegion = azureRegion,
                            azureEndpoint = azureEndpoint
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color.White
                ),
                shape = PillShape
            ) { Text("保存") }
        }
    }
}

/** Per-book glossary editor: pick a book, then manage its terms. */
@Composable
private fun GlossaryTabBody(
    books: List<Book>,
    onLoad: suspend (String) -> BookGlossary,
    onAdd: suspend (String, String, String) -> BookGlossary,
    onUpdate: suspend (String, GlossaryEntry) -> BookGlossary,
    onRemove: suspend (String, String) -> BookGlossary
) {
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable { mutableStateOf(books.firstOrNull()?.id ?: "") }
    val book = books.firstOrNull { it.id == selectedId }
    var entries by remember(selectedId) { mutableStateOf<List<GlossaryEntry>>(emptyList()) }
    var loading by remember(selectedId) { mutableStateOf(false) }
    var newTerm by remember { mutableStateOf("") }
    var newTranslation by remember { mutableStateOf("") }

    LaunchedEffect(selectedId) {
        if (book != null) {
            loading = true
            entries = onLoad(book.id).entries
            loading = false
        } else {
            entries = emptyList()
        }
    }

    if (books.isEmpty()) {
        Text(
            "书架还没有书，导入后即可管理每本书的术语表。",
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft
        )
        return
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        var bookMenu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("书目", style = MaterialTheme.typography.labelLarge, color = InkSoft)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { bookMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    book?.title ?: "选择书籍",
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
                            selectedId = candidate.id
                            bookMenu = false
                        }
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newTerm,
            onValueChange = { newTerm = it },
            label = { Text("英文术语") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(
            value = newTranslation,
            onValueChange = { newTranslation = it },
            label = { Text("译法（留空=保留原文）") },
            singleLine = true,
            modifier = Modifier.weight(1.3f)
        )
        IconButton(onClick = {
            val term = newTerm.trim()
            if (term.isBlank()) return@IconButton
            val target = book ?: return@IconButton
            scope.launch {
                entries = onAdd(target.id, term, newTranslation).entries
                newTerm = ""
                newTranslation = ""
            }
        }) {
            Icon(
                Icons.Default.Add,
                contentDescription = "添加术语",
                tint = Accent
            )
        }
    }
    Text(
        "手动条目优先于 AI 自动条目；开关控制是否参与 Azure 整句翻译，关闭后仅用于点词提示。",
        style = MaterialTheme.typography.labelSmall,
        color = InkSoft
    )
    Spacer(Modifier.height(8.dp))
    if (loading) {
        CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp))
    } else if (entries.isEmpty()) {
        Text("还没有术语条目。", color = InkSoft)
    } else {
        entries.forEach { entry ->
            val target = book ?: return@forEach
            GlossaryEntryRow(
                entry = entry,
                onUpdate = { updated ->
                    scope.launch {
                        entries = onUpdate(target.id, updated).entries
                    }
                },
                onRemove = {
                    scope.launch {
                        entries = onRemove(target.id, entry.term).entries
                    }
                }
            )
            HorizontalDivider(color = InkFaint.copy(alpha = .25f))
        }
    }
    }
}

/** One glossary entry row: origin label, enable switch, editable translation. */
@Composable
internal fun GlossaryEntryRow(
    entry: GlossaryEntry,
    onUpdate: (GlossaryEntry) -> Unit,
    onRemove: () -> Unit
) {
    var translation by remember(entry.key, entry.translation) {
        mutableStateOf(entry.translation)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.term, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append(
                            when (entry.origin) {
                                "manual" -> "手动"
                                "auto" -> "AI 自动"
                                else -> "本地词频"
                            }
                        )
                        if (entry.translation.isBlank()) append(" · 保留原文")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint
                )
            }
            Switch(
                checked = entry.enabled,
                onCheckedChange = { enabled -> onUpdate(entry.copy(enabled = enabled)) }
            )
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除术语",
                    tint = InkFaint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = translation,
                onValueChange = { translation = it },
                label = { Text("译法（留空=保留原文）") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = {
                onUpdate(entry.copy(translation = translation.trim(), origin = "manual"))
            }) { Text("保存") }
        }
        if (entry.note.isNotBlank()) {
            Text(
                entry.note,
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
