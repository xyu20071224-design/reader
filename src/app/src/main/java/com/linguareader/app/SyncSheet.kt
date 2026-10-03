package com.linguareader.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.linguareader.shared.data.formatStorageBytes
import com.linguareader.shared.sync.CloudBook
import com.linguareader.shared.sync.ServerAddress
import com.linguareader.shared.sync.ServerAddressError
import com.linguareader.shared.sync.ServerAddressResult
import com.linguareader.shared.sync.SyncServerDefaults
import com.linguareader.shared.sync.SyncServerResolver
import com.linguareader.shared.sync.SyncSettings

/**
 * 云同步设置弹层（F-160，自托管服务端）。
 *
 * 日常只填「用户名 + 密码」：服务器地址与证书指纹收进高级折叠区，默认用构建期注入的
 * 默认服务器（[syncDefaults]，来自 gitignored 的 local.properties → BuildConfig）预填；
 * 已保存的 serverUrl 优先于默认值。既无已保存值也无默认值时高级区默认展开，保留手填能力。
 * 主输入框仍支持手填 IPv4[:端口]（协议固定 http://、端口缺省 8787），完整 URL 走高级区。
 *
 * **默认值不会自动开启同步**：enabled 仍只能由用户点保存/登录置 true（离线优先）。
 *
 * 只负责收集输入并把动作转发给 AppViewModel；网络、离线队列与冲突合并全在 :shared
 * 的 SyncCoordinator / SyncEngine。令牌不在这里持久化——由 AndroidSyncController 交给
 * Android Keystore 加密后存独立 prefs。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncSheet(
    settings: SyncSettings,
    status: String?,
    busy: Boolean,
    onSave: (SyncSettings) -> Unit,
    onLogin: (SyncSettings, String) -> Unit,
    onSyncNow: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
    /** 云端书单（书籍正文 blob）：远端有、本机没有的书。 */
    cloudBooks: List<CloudBook> = emptyList(),
    cloudBooksLoaded: Boolean = false,
    cloudBooksLoading: Boolean = false,
    onRefreshCloudBooks: () -> Unit = {},
    onDownloadCloudBook: (CloudBook) -> Unit = {},
    /** 构建期注入的默认服务器（BuildConfig；空串 = 无默认，保持手填行为）。 */
    syncDefaults: SyncServerDefaults = SyncServerDefaults.of(
        BuildConfig.SYNC_DEFAULT_SERVER_URL,
        BuildConfig.SYNC_DEFAULT_CERT_SHA256
    )
) {
    // 地址初值：已保存值优先，否则构建期默认值（URL + 指纹成对取，见 SyncServerResolver）。
    val initial = remember(settings.serverUrl, settings.pinnedCertSha256, syncDefaults) {
        SyncServerResolver.initial(settings, syncDefaults)
    }
    // 简单 http://IPv4[:port] 回填地址框；https/域名/带路径回填高级区的完整 URL。
    val simpleAddress = remember(initial.serverUrl) { ServerAddress.toInput(initial.serverUrl) }
    var address by remember { mutableStateOf(simpleAddress ?: "") }
    var fullUrl by remember { mutableStateOf(if (simpleAddress == null) initial.serverUrl else "") }
    var fingerprint by remember { mutableStateOf(initial.pinnedCertSha256) }
    var username by remember { mutableStateOf(settings.username) }
    var password by remember { mutableStateOf("") }
    // 无已保存值也无默认值 → 展开高级区提示手填；否则收起，日常只填用户名+密码。
    var advanced by remember { mutableStateOf(initial.needsManualAddress) }
    var error by remember { mutableStateOf<ServerAddressError?>(null) }

    val errorText = when (error) {
        ServerAddressError.EMPTY -> stringResource(R.string.sync_error_empty)
        ServerAddressError.BAD_IPV4 -> stringResource(R.string.sync_error_bad_ipv4)
        ServerAddressError.BAD_PORT -> stringResource(R.string.sync_error_bad_port)
        ServerAddressError.BAD_URL -> stringResource(R.string.sync_error_bad_url)
        null -> null
    }
    // 高级区非空时 compose 只认完整 URL，行内报错要落在真正出错的那个框上。
    val addressInvalid = error != null && fullUrl.isBlank()
    val fullUrlInvalid = error != null && fullUrl.isNotBlank()

    // 保存与登录共用同一套输入校验：非法输入行内报错并拒绝动作。
    // 两个地址框都空且存在默认值 → 回落默认 URL（不得报「请填写服务器地址」）。
    fun submit(onValid: (SyncSettings) -> Unit) {
        val input = SyncServerResolver.submitInput(initial, address, fullUrl)
        when (val result = ServerAddress.compose(input.address, input.fullUrl)) {
            is ServerAddressResult.Ok -> {
                error = null
                onValid(
                    SyncSettings(
                        enabled = true,
                        serverUrl = result.serverUrl,
                        username = username.trim(),
                        pinnedCertSha256 = fingerprint.trim()
                    )
                )
            }
            is ServerAddressResult.Invalid -> error = result.error
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        // 内容（输入框 + 高级区 + 两行按钮 + 状态）在手机屏高下超出一屏，不给滚动会把
        // 下面那行按钮顶到屏幕外（Pixel 5 实机 dump 实测）。故整块可纵向滚动。
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(R.string.sync_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.sync_username_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.sync_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            // 高级设置：服务器地址 + 完整 URL（HTTPS/反代）+ 证书指纹，默认收起并已预填。
            TextButton(onClick = { advanced = !advanced }) {
                Text(stringResource(R.string.sync_advanced_toggle))
            }
            if (advanced) {
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.sync_server_label)) },
                    singleLine = true,
                    isError = addressInvalid,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fullUrl,
                    onValueChange = {
                        fullUrl = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.sync_full_url_label)) },
                    singleLine = true,
                    isError = fullUrlInvalid,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = { Text(stringResource(R.string.sync_fingerprint_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (initial.needsManualAddress) {
                    // 没有内置默认服务器：明确告诉用户要在这里填地址（保留手填能力）。
                    Text(
                        stringResource(R.string.sync_manual_server_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft
                    )
                }
            }

            if (errorText != null) {
                Text(
                    errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // 四个按钮排一行会在手机宽度下溢出（实机 dump 时「登出」跑到屏幕外），
            // 因此拆成两行。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { submit { onSave(it) } }, enabled = !busy) {
                    Text(stringResource(R.string.sync_save))
                }
                // 登录用「当前输入」推导的设置（不是已保存的旧值）：改了地址没点保存也能登录；
                // 只有登录成功才由 AndroidSyncController 落库（失败不写设置）。
                Button(
                    onClick = { submit { settings -> onLogin(settings, password) } },
                    enabled = !busy
                ) {
                    Text(stringResource(R.string.sync_login))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSyncNow, enabled = !busy) {
                    Text(stringResource(R.string.sync_now))
                }
                TextButton(onClick = onLogout, enabled = !busy) {
                    Text(stringResource(R.string.sync_logout))
                }
            }

            if (status != null) {
                Spacer(Modifier.height(2.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = InkSoft)
            }

            // 云端书单：书籍正文（blob）按内容寻址存在服务端，这里列「远端有、本机没有」的书，
            // 点「下载」重跑导入器落到本地书库。没登录/没拉过显示引导文案。
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.sync_cloud_books_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefreshCloudBooks,
                    enabled = !busy && !cloudBooksLoading
                ) {
                    Text(
                        stringResource(
                            if (cloudBooksLoaded) R.string.sync_cloud_refresh else R.string.sync_cloud_load
                        )
                    )
                }
            }
            when {
                cloudBooksLoading -> Text(
                    stringResource(R.string.sync_cloud_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft
                )
                !cloudBooksLoaded -> Text(
                    stringResource(R.string.sync_cloud_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft
                )
                cloudBooks.isEmpty() -> Text(
                    stringResource(R.string.sync_cloud_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft
                )
                else -> cloudBooks.forEach { cloud ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            cloud.displayName + " · " + formatStorageBytes(cloud.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink
                        )
                        TextButton(
                            onClick = { onDownloadCloudBook(cloud) },
                            enabled = !busy
                        ) {
                            Text(stringResource(R.string.sync_cloud_download))
                        }
                    }
                }
            }
        }
    }
}
