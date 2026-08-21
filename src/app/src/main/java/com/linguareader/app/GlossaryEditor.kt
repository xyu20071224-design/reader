package com.linguareader.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linguareader.app.ai.BookGlossary
import com.linguareader.app.ai.GlossaryEntry
import com.linguareader.app.data.Book
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 本书术语表编辑器：书架弹窗与 AI 抽屉「术语表」Tab 共用。
 *
 * 相比旧版（每行常驻输入框）的改进：
 *  - 行默认只读展示（术语 + 译法预览 + 来源徽标 + 开关），点行才展开编辑，
 *     长列表可扫读；
 *  - 顶部搜索框 + 来源过滤 chips，条目多时能快速定位；
 *  - 按「手动 / AI 自动 / 本地词频」分组展示，组内按字母排序；
 *  - 增删改都有行内确认反馈（抽屉与弹窗都是覆盖窗口，全局 Snackbar 会被遮住，
 *     所以沿用 AiDrawerSheet 的行内提示方案）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlossaryEditorBody(
    books: List<Book>,
    /** 弹窗入口固定书目时传非空；抽屉入口传 null，由组件自己提供书目下拉。 */
    lockedBookId: String?,
    onLoad: suspend (String) -> BookGlossary,
    onAdd: suspend (String, String, String) -> BookGlossary,
    onUpdate: suspend (String, GlossaryEntry) -> BookGlossary,
    onRemove: suspend (String, String) -> BookGlossary
) {
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable { mutableStateOf(lockedBookId ?: books.firstOrNull()?.id ?: "") }
    val book = books.firstOrNull { it.id == selectedId }
    var entries by remember(selectedId) { mutableStateOf<List<GlossaryEntry>>(emptyList()) }
    var loading by remember(selectedId) { mutableStateOf(false) }
    var query by remember(selectedId) { mutableStateOf("") }
    var originFilter by remember(selectedId) { mutableStateOf<String?>(null) }
    var editingTerm by remember(selectedId) { mutableStateOf<String?>(null) }
    var newTerm by remember { mutableStateOf("") }
    var newTranslation by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }

    val addedMsg = stringResource(R.string.glossary_added, "%s")
    val savedMsg = stringResource(R.string.glossary_saved, "%s")
    val removedMsg = stringResource(R.string.glossary_removed, "%s")
    val toggleOnMsg = stringResource(R.string.glossary_toggle_on, "%s")
    val toggleOffMsg = stringResource(R.string.glossary_toggle_off, "%s")

    // 行内反馈自动消失；同一时刻只保留最新一条。
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2500)
            notice = null
        }
    }

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
            stringResource(R.string.glossary_no_books),
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft
        )
        return
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (lockedBookId == null) {
            BookPickerRow(books, book) { selectedId = it.id }
            Spacer(Modifier.height(6.dp))
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.glossary_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = originFilter == null,
                onClick = { originFilter = null },
                label = { Text(stringResource(R.string.glossary_filter_all)) }
            )
            listOf("manual", "auto", "local").forEach { origin ->
                FilterChip(
                    selected = originFilter == origin,
                    onClick = { originFilter = if (originFilter == origin) null else origin },
                    label = { Text(originLabel(origin)) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = newTerm,
            onValueChange = { newTerm = it },
            label = { Text(stringResource(R.string.glossary_term_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newTranslation,
                onValueChange = { newTranslation = it },
                label = { Text(stringResource(R.string.glossary_translation_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val target = book ?: return@Button
                    val term = newTerm.trim()
                    if (term.isBlank()) return@Button
                    scope.launch {
                        entries = onAdd(target.id, term, newTranslation).entries
                        newTerm = ""
                        newTranslation = ""
                        notice = addedMsg.format(term)
                    }
                },
                enabled = newTerm.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.glossary_add))
            }
        }
        Text(
            stringResource(R.string.glossary_hint),
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft
        )
        Spacer(Modifier.height(8.dp))

        val needle = query.trim().lowercase()
        val filtered = entries.filter { entry ->
            (originFilter == null || entry.origin == originFilter) &&
                (needle.isEmpty() ||
                    entry.term.lowercase().contains(needle) ||
                    entry.translation.contains(needle))
        }
        when {
            loading -> CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp))
            entries.isEmpty() -> Text(stringResource(R.string.glossary_empty), color = InkSoft)
            filtered.isEmpty() -> Text(stringResource(R.string.glossary_no_match), color = InkSoft)
            else -> groupedEntries(filtered).forEach { (origin, list) ->
                Text(
                    "${originLabel(origin)} · ${list.size}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = InkSoft,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
                list.forEach { entry ->
                    GlossaryEntryRow(
                        entry = entry,
                        editing = editingTerm == entry.key,
                        onToggleEdit = {
                            editingTerm = if (editingTerm == entry.key) null else entry.key
                        },
                        onUpdate = { updated ->
                            val target = book ?: return@GlossaryEntryRow
                            scope.launch {
                                entries = onUpdate(target.id, updated).entries
                                editingTerm = null
                                notice = savedMsg.format(updated.term)
                            }
                        },
                        onToggleEnabled = { enabled ->
                            val target = book ?: return@GlossaryEntryRow
                            scope.launch {
                                entries = onUpdate(target.id, entry.copy(enabled = enabled)).entries
                                notice = if (enabled) toggleOnMsg.format(entry.term)
                                else toggleOffMsg.format(entry.term)
                            }
                        },
                        onRemove = {
                            val target = book ?: return@GlossaryEntryRow
                            scope.launch {
                                entries = onRemove(target.id, entry.term).entries
                                if (editingTerm == entry.key) editingTerm = null
                                notice = removedMsg.format(entry.term)
                            }
                        }
                    )
                    HorizontalDivider(color = InkFaint.copy(alpha = .18f))
                }
            }
        }

        notice?.let {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Success,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = Success)
            }
        }
    }
}

/** 手动条目排最前（优先级最高），其后 AI 自动、本地词频；组内按字母排序便于扫读。 */
private fun groupedEntries(entries: List<GlossaryEntry>): List<Pair<String, List<GlossaryEntry>>> =
    listOf("manual", "auto", "local").mapNotNull { origin ->
        val group = entries
            .filter { normalizeOrigin(it.origin) == origin }
            .sortedBy { it.term.lowercase() }
        if (group.isEmpty()) null else origin to group
    }

private fun normalizeOrigin(origin: String): String =
    if (origin in setOf("manual", "auto")) origin else "local"

@Composable
private fun originLabel(origin: String): String = stringResource(
    when (origin) {
        "manual" -> R.string.glossary_origin_manual
        "auto" -> R.string.glossary_origin_auto
        else -> R.string.glossary_origin_local
    }
)

@Composable
private fun BookPickerRow(
    books: List<Book>,
    selected: Book?,
    onSelect: (Book) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.multivoice_book_label), style = MaterialTheme.typography.labelLarge, color = InkSoft)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    selected?.title ?: stringResource(R.string.multivoice_pick_book),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    color = Ink
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = InkSoft)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                books.forEach { candidate ->
                    DropdownMenuItem(
                        text = {
                            Text(candidate.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        onClick = {
                            onSelect(candidate)
                            menu = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * 单条术语行：默认只读（术语 + 译法预览 + 来源徽标 + 开关），点文字区展开编辑态
 * （译法输入框 + 保存 / 删除）。开关直接切换是否参与整句翻译并给出反馈。
 */
@Composable
internal fun GlossaryEntryRow(
    entry: GlossaryEntry,
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onUpdate: (GlossaryEntry) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    var translation by remember(entry.key, entry.translation) {
        mutableStateOf(entry.translation)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onToggleEdit)
            ) {
                Text(entry.term, fontWeight = FontWeight.Medium)
                Text(
                    entry.translation.ifBlank { stringResource(R.string.glossary_keep_original) },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.translation.isBlank()) InkFaint else InkSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            OriginBadge(entry.origin)
            Spacer(Modifier.width(8.dp))
            Switch(checked = entry.enabled, onCheckedChange = onToggleEnabled)
        }
        if (!editing && entry.note.isNotBlank()) {
            Text(
                entry.note,
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (editing) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = translation,
                    onValueChange = { translation = it },
                    label = { Text(stringResource(R.string.glossary_translation_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = {
                    onUpdate(entry.copy(translation = translation.trim(), origin = "manual"))
                }) { Text(stringResource(R.string.glossary_save)) }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.glossary_delete),
                        tint = Danger,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OriginBadge(origin: String) {
    val label = originLabel(origin)
    val color = when (origin) {
        "manual" -> Accent
        "auto" -> Gold
        else -> InkFaint
    }
    Surface(color = color.copy(alpha = 0.12f), shape = PillShape) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
