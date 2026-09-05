package com.linguareader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.LibraryRepository
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 书架屏（M2 桌面）：导入 TXT/EPUB/FB2（JFileChooser 原生对话框）+ 列表 +
 * 打开阅读。导入真相是 :shared 的 BookImporter/LibraryRepository（刀7）。
 */
@Composable
fun LibraryPane(
    library: LibraryRepository,
    onOpenBook: (Book) -> Unit
) {
    val scope = rememberCoroutineScope()
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }

    suspend fun reload() { books = library.loadBooks() }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("书架（${books.size}）", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(
                enabled = !importing,
                onClick = {
                    val chooser = JFileChooser()
                    chooser.dialogTitle = "导入电子书（TXT / EPUB / FB2）"
                    chooser.fileFilter = FileNameExtensionFilter("电子书 (txt, epub, fb2)", "txt", "epub", "fb2")
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        importing = true
                        status = ""
                        scope.launch {
                            runCatching {
                                val file = chooser.selectedFile
                                val imported = com.linguareader.shared.importer.BookImporter(library.booksDir)
                                    .import(file, file.name)
                                library.registerImportedBook(imported)
                            }.onSuccess {
                                status = "导入成功"
                                reload()
                            }.onFailure {
                                status = "导入失败：${it.message}"
                            }
                            importing = false
                        }
                    }
                }
            ) { Text(if (importing) "导入中…" else "导入电子书") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelMedium)

        if (books.isEmpty()) Text("书架为空。点「导入电子书」选择 TXT / EPUB / FB2 文件。")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(books, key = { it.id }) { book ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "${book.author} · ${book.chapters.size} 章 · " +
                                    (book.sourceFormat.ifBlank { "?" }),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        TextButton(onClick = { onOpenBook(book) }) { Text("阅读") }
                        TextButton(onClick = {
                            scope.launch {
                                library.deleteBook(book)
                                status = "已删除《${book.title}》"
                                reload()
                            }
                        }) { Text("删除") }
                    }
                }
            }
        }
    }
}
