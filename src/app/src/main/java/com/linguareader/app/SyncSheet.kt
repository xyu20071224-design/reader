package com.linguareader.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.linguareader.shared.sync.SyncSettings

/**
 * 云同步设置弹层（F-160，自托管服务端）。
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
    onLogin: (String) -> Unit,
    onSyncNow: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    var serverUrl by remember { mutableStateOf(settings.serverUrl) }
    var username by remember { mutableStateOf(settings.username) }
    var password by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf(settings.pinnedCertSha256) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(R.string.sync_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text(stringResource(R.string.sync_server_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
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
            OutlinedTextField(
                value = fingerprint,
                onValueChange = { fingerprint = it },
                label = { Text(stringResource(R.string.sync_fingerprint_label)) },
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSave(
                            SyncSettings(
                                enabled = true,
                                serverUrl = serverUrl.trim(),
                                username = username.trim(),
                                pinnedCertSha256 = fingerprint.trim()
                            )
                        )
                    },
                    enabled = !busy
                ) { Text(stringResource(R.string.sync_save)) }
                Button(onClick = { onLogin(password) }, enabled = !busy) {
                    Text(stringResource(R.string.sync_login))
                }
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
        }
    }
}
