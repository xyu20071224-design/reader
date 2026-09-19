package com.linguareader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.linguareader.shared.sync.SyncSettings

/**
 * 设置页里的「云同步」区块。只负责收集输入并把动作转发给 [DesktopSyncController]；
 * 具体网络与冲突逻辑在 :shared。
 */
@Composable
fun SyncSettingsSection(sync: DesktopSyncController) {
    var serverUrl by remember { mutableStateOf(sync.settings.serverUrl) }
    var username by remember { mutableStateOf(sync.settings.username) }
    var password by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf(sync.settings.pinnedCertSha256) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("云同步", style = MaterialTheme.typography.titleMedium)
        Text(
            "REST + JSON，自托管服务端；只同步阅读进度、生词、术语备注与偏好。令牌存入系统凭据管理器。",
            style = MaterialTheme.typography.labelSmall
        )

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("服务器地址（https://IP:端口）") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = fingerprint,
            onValueChange = { fingerprint = it },
            label = { Text("服务端证书 SHA-256 指纹（自签证书必填）") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    sync.update(
                        SyncSettings(
                            enabled = true,
                            serverUrl = serverUrl.trim(),
                            username = username.trim(),
                            pinnedCertSha256 = fingerprint.trim()
                        )
                    )
                },
                enabled = !sync.busy
            ) { Text("保存设置") }
            Button(onClick = { sync.login(password) }, enabled = !sync.busy) { Text("登录") }
            Button(onClick = { sync.syncNow() }, enabled = !sync.busy) { Text("立即同步") }
            OutlinedButton(onClick = { sync.logout() }, enabled = !sync.busy) { Text("登出") }
        }

        if (!sync.secureStorage) {
            Text(
                "注意：未检测到系统凭据管理器，令牌将以 0600 文件保存，安全性较弱。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (sync.status.isNotBlank()) {
            Text(sync.status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
