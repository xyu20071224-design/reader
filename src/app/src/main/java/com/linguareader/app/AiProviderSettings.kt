package com.linguareader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linguareader.app.ai.AiProtocol
import com.linguareader.app.ai.AiProviderProfile
import com.linguareader.app.ai.AiTranslators
import com.linguareader.app.ai.DiscoveredModel
import com.linguareader.app.ai.discoverModels
import com.linguareader.app.ai.probeKeyUsable
import kotlinx.coroutines.launch

/** 协议常量 → 本地化标签。 */
internal fun protocolLabelRes(protocol: String): Int = when (protocol) {
    AiProtocol.ANTHROPIC -> R.string.aidrawer_protocol_anthropic
    AiProtocol.GEMINI -> R.string.aidrawer_protocol_gemini
    else -> R.string.aidrawer_protocol_openai
}

/**
 * 「联网语境翻译」的服务商列表：每行 单选 + 名称·协议·模型 + 编辑入口，
 * 「添加服务商」拉起编辑卡。列表是草稿态——切换/编辑都只改父级的 draft
 * state，统一由底部的保存按钮落盘。
 */
@Composable
internal fun ProviderSettingsBody(
    providers: List<AiProviderProfile>,
    activeId: String,
    masterEnabled: Boolean,
    onSelectActive: (String) -> Unit,
    onEdit: (AiProviderProfile) -> Unit,
    onAdd: () -> Unit
) {
    val noneName = stringResource(R.string.aidrawer_provider_none_name)
    Column(Modifier.fillMaxWidth()) {
        providers.forEach { provider ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = masterEnabled) { onSelectActive(provider.id) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = provider.id == activeId,
                    onClick = { onSelectActive(provider.id) },
                    enabled = masterEnabled
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        provider.displayLabel.ifBlank { noneName },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${stringResource(protocolLabelRes(provider.protocol))} · ${provider.model}",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { onEdit(provider) }, enabled = masterEnabled) {
                    Text(stringResource(R.string.aidrawer_provider_edit_action))
                }
            }
        }
        if (providers.isEmpty()) {
            Text(
                stringResource(R.string.aidrawer_provider_empty),
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft
            )
            Spacer(Modifier.height(6.dp))
        }
        TextButton(onClick = onAdd, enabled = masterEnabled) {
            Text(stringResource(R.string.aidrawer_provider_add))
        }
    }
}

/**
 * 服务商编辑卡：名称 / 接口地址 / API Key / 协议 / 模型，外加两项
 * 草稿探测——「获取可用模型」（仅 OpenAI 兼容协议：只有这一族有通用的
 * GET /models 列表）与「测试连接」（按协议发一条最小请求）。卡内保存
 * 回传完整档案；删除走确认弹窗后回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderEditorDialog(
    initial: AiProviderProfile,
    isNew: Boolean,
    masterEnabled: Boolean,
    onSave: (AiProviderProfile) -> Unit,
    onDelete: ((AiProviderProfile) -> Unit)?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var protocol by remember {
        mutableStateOf(initial.protocol.ifBlank { AiProtocol.OPENAI_COMPAT })
    }
    var model by remember { mutableStateOf(initial.model) }
    // 「获取可用模型」瞬态（草稿探测语义：用卡里正在编辑的值，与保存无关）。
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchStatus by remember { mutableStateOf<String?>(null) }
    var modelCandidates by remember { mutableStateOf<List<DiscoveredModel>?>(null) }
    // 「测试连接」瞬态。
    var testingConnection by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    fun draft(): AiProviderProfile = initial.copy(
        name = name.trim(),
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        protocol = protocol,
        model = model.trim()
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.aidrawer_provider_new else R.string.aidrawer_provider_edit
                )
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.aidrawer_provider_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.aidrawer_base_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (protocol == AiProtocol.OPENAI_COMPAT) {
                    Text(
                        stringResource(R.string.aidrawer_provider_local_key_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSoft
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.aidrawer_provider_protocol_label),
                    style = MaterialTheme.typography.labelMedium
                )
                Row {
                    AiProtocol.ALL.forEach { candidate ->
                        FilterChip(
                            selected = protocol == candidate,
                            onClick = { protocol = candidate },
                            label = { Text(stringResource(protocolLabelRes(candidate))) },
                            enabled = masterEnabled,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                if (protocol != AiProtocol.OPENAI_COMPAT) {
                    Text(
                        stringResource(R.string.aidrawer_provider_discovery_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSoft
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.aidrawer_model_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val probe = draft()
                            when {
                                probe.baseUrl.isEmpty() ->
                                    fetchStatus = context.getString(R.string.aidrawer_fetch_models_need_url)
                                !probeKeyUsable(probe.apiKey) ->
                                    fetchStatus = context.getString(R.string.aidrawer_fetch_models_bad_key)
                                else -> {
                                    fetchingModels = true
                                    fetchStatus = null
                                    scope.launch {
                                        val outcome = runCatching {
                                            discoverModels(probe.baseUrl, probe.apiKey)
                                        }
                                        fetchingModels = false
                                        outcome.fold(
                                            onSuccess = { found ->
                                                if (found.isEmpty()) {
                                                    fetchStatus = context.getString(
                                                        R.string.aidrawer_fetch_models_empty
                                                    )
                                                } else {
                                                    modelCandidates = found
                                                }
                                            },
                                            onFailure = {
                                                fetchStatus = context.getString(
                                                    R.string.aidrawer_fetch_models_fail,
                                                    it.message ?: ""
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        enabled = masterEnabled && !fetchingModels &&
                            protocol == AiProtocol.OPENAI_COMPAT
                    ) {
                        Text(
                            stringResource(
                                if (fetchingModels) R.string.aidrawer_fetch_models_fetching
                                else R.string.aidrawer_fetch_models
                            )
                        )
                    }
                    fetchStatus?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Danger
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val probe = draft()
                            if (probe.apiKey.isEmpty()) {
                                testOk = false
                                testStatus =
                                    context.getString(R.string.aidrawer_deepseek_test_need_key)
                                return@TextButton
                            }
                            testingConnection = true
                            testStatus = null
                            scope.launch {
                                val outcome = runCatching {
                                    AiTranslators.forProvider(probe).verifyConnection()
                                }
                                testingConnection = false
                                outcome.fold(
                                    onSuccess = {
                                        testOk = true
                                        testStatus =
                                            context.getString(R.string.aidrawer_deepseek_test_ok)
                                    },
                                    onFailure = {
                                        testOk = false
                                        testStatus = context.getString(
                                            R.string.aidrawer_deepseek_test_fail,
                                            it.message ?: ""
                                        )
                                    }
                                )
                            }
                        },
                        enabled = masterEnabled && !testingConnection
                    ) { Text(stringResource(R.string.aidrawer_deepseek_test)) }
                    val statusText = when {
                        testingConnection -> context.getString(R.string.aidrawer_deepseek_testing)
                        testStatus != null -> testStatus
                        else -> null
                    }
                    statusText?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                testingConnection -> InkSoft
                                testOk -> Success
                                else -> Danger
                            }
                        )
                    }
                }
                validation?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Danger)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val candidate = draft()
                if (candidate.baseUrl.isBlank() || candidate.apiKey.isBlank() ||
                    candidate.model.isBlank()
                ) {
                    validation = context.getString(R.string.aidrawer_provider_save_error)
                    return@TextButton
                }
                onSave(candidate)
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(
                        onClick = { confirmingDelete = true },
                        enabled = masterEnabled
                    ) {
                        Text(
                            stringResource(R.string.common_delete),
                            color = Danger
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    )
    modelCandidates?.let { found ->
        ModelPickerDialog(
            candidates = found,
            current = model.trim(),
            onPick = { picked ->
                model = picked
                fetchStatus = null
                modelCandidates = null
            },
            onDismiss = { modelCandidates = null }
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            containerColor = CardSurface,
            title = { Text(stringResource(R.string.aidrawer_provider_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.aidrawer_provider_delete_message,
                        initial.displayLabel.ifBlank {
                            context.getString(R.string.aidrawer_provider_none_name)
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete?.invoke(initial) }) {
                    Text(stringResource(R.string.common_delete), color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * 模型选择弹层：搜索 + 点行即选（结构对齐 [VoicePickerDialog] 的先例）。
 * OpenRouter 之类的聚合端点动辄几百个模型，长列表必须可搜索、限高滚动。
 */
@Composable
private fun ModelPickerDialog(
    candidates: List<DiscoveredModel>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(candidates, query) {
        if (query.isBlank()) candidates
        else candidates.filter { candidate ->
            candidate.id.contains(query, ignoreCase = true) ||
                candidate.name.contains(query, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = { Text(stringResource(R.string.aidrawer_model_picker_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.aidrawer_model_picker_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Text(stringResource(R.string.aidrawer_model_picker_no_match), color = InkSoft)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(filtered, key = { it.id }) { candidate ->
                            val selected = candidate.id.equals(current, ignoreCase = true)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (selected) AccentSoft else CardSurface)
                                    .clickable { onPick(candidate.id) }
                                    .padding(horizontal = 6.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    candidate.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (selected) AccentDeep else Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (candidate.subtitle.isNotBlank()) {
                                    Text(
                                        candidate.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = InkSoft,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
