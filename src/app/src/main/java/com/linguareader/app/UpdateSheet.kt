package com.linguareader.app

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.linguareader.app.BuildConfig
import com.linguareader.app.update.ApkInstaller
import com.linguareader.shared.update.AppUpdatePhase
import com.linguareader.shared.update.AppUpdateUiState

/** Release 页地址（与 GitHubUpdateChecker 的 repo 对应），浏览器跳转用。 */
private const val RELEASE_PAGE_BASE = "https://github.com/xyu20071224-design/reader/releases/tag/"

/**
 * 检查更新弹层：版本信息、自动检查开关、检查/下载/安装的状态行。
 *
 * ModalBottomSheet 会盖住全局 Snackbar，所有检查/下载反馈都画在弹层内部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateSheet(
    update: AppUpdateUiState,
    onDismiss: () -> Unit,
    onCheckNow: () -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit
) {
    val context = LocalContext.current
    // 从系统「安装未知应用」授权页返回时要重查授权状态。
    var canInstall by remember { mutableStateOf(ApkInstaller.canInstall(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canInstall = ApkInstaller.canInstall(context)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                stringResource(R.string.update_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.labelMedium,
                color = InkFaint
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.update_auto_check_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink
                    )
                    Text(
                        stringResource(R.string.update_auto_check_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = update.autoCheckEnabled, onCheckedChange = onAutoCheckChange)
            }

            Spacer(Modifier.height(16.dp))
            UpdatePhaseBody(
                update = update,
                canInstall = canInstall,
                onCheckNow = onCheckNow,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
                onGrantInstallPermission = {
                    context.startActivity(ApkInstaller.installPermissionIntent(context))
                }
            )
        }
    }
}

/** 按 [AppUpdatePhase] 渲染弹层的状态区。 */
@Composable
private fun UpdatePhaseBody(
    update: AppUpdateUiState,
    canInstall: Boolean,
    onCheckNow: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onGrantInstallPermission: () -> Unit
) {
    when (update.phase) {
        AppUpdatePhase.IDLE -> {
            Button(onClick = onCheckNow, shape = PillShape) {
                Text(stringResource(R.string.update_check_now))
            }
        }

        AppUpdatePhase.CHECKING -> PhaseRow {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.update_checking),
                style = MaterialTheme.typography.bodyMedium,
                color = InkSoft
            )
        }

        AppUpdatePhase.UP_TO_DATE -> PhaseRow {
            Text(
                stringResource(R.string.update_up_to_date),
                style = MaterialTheme.typography.bodyMedium,
                color = Success
            )
        }

        AppUpdatePhase.AVAILABLE -> AvailableBody(
            update = update,
            canInstall = canInstall,
            onDownload = onDownload,
            onGrantInstallPermission = onGrantInstallPermission
        )

        AppUpdatePhase.DOWNLOADING -> {
            Text(
                stringResource(R.string.update_downloading, update.progress),
                style = MaterialTheme.typography.bodyMedium,
                color = Ink
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { update.progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancelDownload) {
                Text(stringResource(R.string.update_download_cancel))
            }
        }

        AppUpdatePhase.DOWNLOADED -> DownloadedBody(
            apk = update.downloadedApk,
            canInstall = canInstall,
            onGrantInstallPermission = onGrantInstallPermission
        )

        AppUpdatePhase.ERROR -> {
            Text(
                stringResource(R.string.update_error, update.error ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = Danger
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCheckNow) {
                Text(stringResource(R.string.update_retry))
            }
        }
    }
}

@Composable
private fun AvailableBody(
    update: AppUpdateUiState,
    canInstall: Boolean,
    onDownload: () -> Unit,
    onGrantInstallPermission: () -> Unit
) {
    val context = LocalContext.current
    val info = update.info
    if (info != null) {
        Text(
            stringResource(R.string.update_available_title, info.versionName),
            style = MaterialTheme.typography.titleSmall,
            color = Accent
        )
        if (info.releaseNotes.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.update_notes_title),
                style = MaterialTheme.typography.labelMedium,
                color = InkFaint
            )
            Column(
                Modifier
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    info.releaseNotes,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onDownload, shape = PillShape) {
            Text(stringResource(R.string.update_download))
        }
        Spacer(Modifier.height(4.dp))
        if (!canInstall) {
            Text(
                stringResource(R.string.update_install_permission_hint),
                style = MaterialTheme.typography.labelMedium,
                color = InkFaint
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onGrantInstallPermission) {
                Text(stringResource(R.string.update_go_permission_settings))
            }
        }
        val releasePage = RELEASE_PAGE_BASE + info.tag
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releasePage)))
        }) {
            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.update_open_release_page))
        }
    }
}

@Composable
private fun DownloadedBody(
    apk: java.io.File?,
    canInstall: Boolean,
    onGrantInstallPermission: () -> Unit
) {
    val context = LocalContext.current
    if (canInstall && apk != null) {
        Button(
            onClick = { context.startActivity(ApkInstaller.installIntent(context, apk)) },
            shape = PillShape,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
        ) {
            Text(stringResource(R.string.update_install))
        }
    } else {
        Text(
            stringResource(R.string.update_install_permission_hint),
            style = MaterialTheme.typography.labelMedium,
            color = InkFaint
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onGrantInstallPermission) {
            Text(stringResource(R.string.update_go_permission_settings))
        }
    }
}

@Composable
private fun PhaseRow(content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}
