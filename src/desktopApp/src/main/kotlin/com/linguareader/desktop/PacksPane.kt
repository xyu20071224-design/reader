package com.linguareader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.linguareader.shared.packs.InstalledPack
import com.linguareader.shared.packs.PackType
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** The page is passive: entering it only displays persisted state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PacksPane(controller: DesktopPackController) {
    val state by controller.state.collectAsState()
    var removing by remember { mutableStateOf<InstalledPack?>(null) }
    Column(Modifier.fillMaxSize().testTag("desktop_packs").verticalScroll(rememberScrollState())
        .padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("资源包", style = MaterialTheme.typography.headlineSmall)
        Text("本地导入始终可用。服务器与 GitHub 目录仅在点击获取后联网。")
        Text("桌面阅读支持词典包；音频与音色包可安装和管理，听书仍使用原有设置。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !state.busy, onClick = {
            val chooser = JFileChooser().apply {
                dialogTitle = "导入资源包"
                fileFilter = FileNameExtensionFilter("资源包 (*.lrpack)", "lrpack")
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) controller.importLocal(chooser.selectedFile)
        }, modifier = Modifier.testTag("desktop_pack_import")) { Text("导入本地资源包") }

        HorizontalDivider()
        Text("下载资源包", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.source == DesktopPackSource.SERVER,
                onClick = { controller.selectSource(DesktopPackSource.SERVER) }, label = { Text("服务器") })
            FilterChip(selected = state.source == DesktopPackSource.GITHUB,
                onClick = { controller.selectSource(DesktopPackSource.GITHUB) }, label = { Text("GitHub 备用") })
        }
        if (state.source == DesktopPackSource.SERVER) {
            OutlinedTextField(value = state.catalogUrl, onValueChange = controller::editCatalogUrl,
                label = { Text("服务器资源目录 HTTPS 地址") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("desktop_pack_catalog_url"))
        } else {
            Text("从项目 GitHub 最新稳定 Release 获取资源包。切换来源不会自动联网。")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.fetchCatalog() }, enabled = !state.busy,
                modifier = Modifier.testTag("desktop_pack_fetch")) { Text("获取目录") }
            if (state.busy) OutlinedButton(onClick = controller::cancel) { Text("取消") }
        }
        if (state.busy) {
            Text(state.operation)
            if (state.total > 0) {
                LinearProgressIndicator(progress = { (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
                Text("${packBytes(state.downloaded)} / ${packBytes(state.total)}")
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.status.isNotBlank()) Text(state.status, modifier = Modifier.testTag("desktop_pack_status"))
        if (state.fetched && state.offers.isEmpty()) Text("当前来源没有兼容的资源包。")
        state.offers.forEach { offer ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(offer.name, style = MaterialTheme.typography.titleMedium)
                    Text(packBytes(offer.bytes))
                    if (offer.server?.sha256 == null && offer.github?.sha256 == null)
                        Text("来源未提供整包摘要；安装时仍会验证包内全部文件。中断后重新下载。",
                            style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !state.busy, onClick = { controller.installOffer(offer) }) {
                        Text("下载并安装 / 重试")
                    }
                }
            }
        }

        HorizontalDivider()
        Text("已安装（${state.registry.packs.size}）", style = MaterialTheme.typography.titleLarge)
        state.registryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(if (state.registry.activeDictionary == null) "当前词典：默认词典"
            else "当前词典：${state.registry.activeDictionaryPack()?.nameZh?.ifBlank { state.registry.activeDictionary }.orEmpty()}")
        OutlinedButton(enabled = !state.busy && state.registry.activeDictionary != null,
            onClick = { controller.setActiveDictionary(null) }) { Text("恢复默认词典") }
        if (state.registry.packs.isEmpty()) Text("尚未安装资源包。已安装列表可在重启、断网后查看。")
        state.registry.packs.forEach { pack ->
            Card(Modifier.fillMaxWidth().testTag("desktop_pack_${pack.packId}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(pack.nameZh.ifBlank { pack.nameEn }, style = MaterialTheme.typography.titleMedium)
                    Text("${packTypeName(pack.type)} · v${pack.version} · ${packBytes(pack.bytes)}")
                    Text(pack.packId, style = MaterialTheme.typography.bodySmall)
                    if (state.registry.activeDictionary == pack.packId) Text("正在使用")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pack.type == PackType.DICTIONARY) TextButton(
                            enabled = !state.busy && state.registry.activeDictionary != pack.packId,
                            onClick = { controller.setActiveDictionary(pack.packId) }) { Text("使用此词典") }
                        TextButton(enabled = !state.busy, onClick = { controller.verify(pack.packId) }) { Text("验证完整性") }
                        TextButton(enabled = !state.busy, onClick = { removing = pack }) { Text("卸载") }
                    }
                }
            }
        }
    }
    removing?.let { pack ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("卸载资源包") },
            text = { Text("卸载「${pack.nameZh.ifBlank { pack.packId }}」？活动词典卸载后将使用默认词典。") },
            confirmButton = { TextButton(onClick = { removing = null; controller.uninstall(pack.packId) }) { Text("卸载") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } })
    }
}

private fun packTypeName(type: PackType) = when (type) {
    PackType.DICTIONARY -> "词典包"
    PackType.AUDIO -> "音频包"
    PackType.VOICE -> "音色包"
    PackType.BUNDLE -> "集合包"
}

private fun packBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
