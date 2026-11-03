package com.linguareader.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.components.SecondaryButton
import com.linguareader.app.components.SettingRow
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.update.ApkInstaller
import com.linguareader.shared.update.AppUpdatePhase
import com.linguareader.shared.update.AppUpdateUiState

/** Release 页地址（与 GitHubUpdateChecker 的 repo 对应），浏览器跳转用。 */
private const val RELEASE_PAGE_BASE = "https://github.com/xyu20071224-design/reader/releases/tag/"

/**
 * 检查更新弹层：版本信息、自动检查开关、检查/下载/安装的状态行。
 *
 * ModalBottomSheet 会盖住全局 Snackbar，所有检查/下载反馈都画在弹层内部。
 *
 * P3 迁移：外壳换成 [LinguaSheet]（拖柄/圆角/底部留白统一由组件给），
 * 内部样式一律取 [LocalDesignTokens]。**交互与文案零改动**。
 */
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

    LinguaSheet(onDismiss = onDismiss) {
        UpdateSheetContent(
            update = update,
            canInstall = canInstall,
            onAutoCheckChange = onAutoCheckChange,
            onCheckNow = onCheckNow,
            onDownload = onDownload,
            onCancelDownload = onCancelDownload,
            onGrantInstallPermission = {
                context.startActivity(ApkInstaller.installPermissionIntent(context))
            }
        )
    }
}

/**
 * 弹层的**内容体**，与 sheet 外壳解耦。
 *
 * 抽出来的理由：`ModalBottomSheet` 在渲染台（Robolectric）里画不进位图，
 * 于是迁移前/后的视觉对比根本没法做。把内容体单独拎出来之后，它可以被独立渲染、
 * 也可以在没有 sheet 的场景（例如以后的平板双栏）里复用。
 *
 * ⚠️ **抽取是有代价的：本组件不再自带宽度/背景约束。**
 * 真实使用时它继承弹层 `Column` 的宽度与 `ModalBottomSheet` 的纸色底；单独使用
 * （渲染台、其它宿主）时必须由调用方提供——否则文字会按可用的窄宽度排版而被裁断、
 * 内容底部以下会露出画布底色。渲染台里的正确用法见 `SheetMigrationRenderTest`：
 * 外面套 `Box(Modifier.fillMaxSize().background(Paper))` 并用 `renderDp(411, 891)`。
 *
 * @param canInstall 系统「安装未知应用」授权是否已给——由外壳查（它需要 Activity），
 *   这里只负责画，保持无副作用。
 */
@Composable
internal fun ColumnScope.UpdateSheetContent(
    update: AppUpdateUiState,
    canInstall: Boolean,
    onAutoCheckChange: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onGrantInstallPermission: () -> Unit
) {
    val t = LocalDesignTokens.current

    Text(
        text = stringResource(R.string.update_sheet_title),
        style = t.typography.title,
        color = t.colors.ink
    )
    Spacer(Modifier.height(t.spacing.xs))
    Text(
        text = stringResource(
            R.string.update_current_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        ),
        style = t.typography.caption,
        color = t.colors.inkFaint
    )
    Spacer(Modifier.height(t.spacing.m))

    // 自动检查开关：标题 + 说明 + 开关一行，由 SettingRow 统一行高与开关配色。
    SettingRow(
        title = stringResource(R.string.update_auto_check_label),
        subtitle = stringResource(R.string.update_auto_check_hint),
        checked = update.autoCheckEnabled,
        onCheckedChange = onAutoCheckChange
    )

    Spacer(Modifier.height(t.spacing.m))
    UpdatePhaseBody(
        update = update,
        canInstall = canInstall,
        onCheckNow = onCheckNow,
        onDownload = onDownload,
        onCancelDownload = onCancelDownload,
        onGrantInstallPermission = onGrantInstallPermission
    )
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
    val t = LocalDesignTokens.current
    when (update.phase) {
        AppUpdatePhase.IDLE -> {
            PrimaryButton(text = stringResource(R.string.update_check_now), onClick = onCheckNow)
        }

        AppUpdatePhase.CHECKING -> {
            CircularProgressIndicator(
                modifier = Modifier.height(t.spacing.l),
                strokeWidth = t.hairline,
                color = t.colors.accent
            )
            Spacer(Modifier.height(t.spacing.s))
            Text(
                text = stringResource(R.string.update_checking),
                style = t.typography.body,
                color = t.colors.inkSoft
            )
        }

        AppUpdatePhase.UP_TO_DATE -> {
            Text(
                text = stringResource(R.string.update_up_to_date),
                style = t.typography.body,
                color = t.colors.success
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
                text = stringResource(R.string.update_downloading, update.progress),
                style = t.typography.body,
                color = t.colors.ink
            )
            Spacer(Modifier.height(t.spacing.s))
            LinearProgressIndicator(
                progress = { update.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = t.colors.accent,
                trackColor = t.colors.paperDeep
            )
            Spacer(Modifier.height(t.spacing.s))
            LinguaTextButton(
                text = stringResource(R.string.update_download_cancel),
                onClick = onCancelDownload
            )
        }

        AppUpdatePhase.DOWNLOADED -> DownloadedBody(
            apk = update.downloadedApk,
            canInstall = canInstall,
            onGrantInstallPermission = onGrantInstallPermission
        )

        AppUpdatePhase.ERROR -> {
            Text(
                text = stringResource(R.string.update_error, update.error ?: ""),
                style = t.typography.body,
                color = t.colors.danger
            )
            Spacer(Modifier.height(t.spacing.s))
            SecondaryButton(text = stringResource(R.string.update_retry), onClick = onCheckNow)
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
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val info = update.info
    if (info != null) {
        Text(
            text = stringResource(R.string.update_available_title, info.versionName),
            style = t.typography.title,
            color = t.colors.accent
        )
        if (info.releaseNotes.isNotBlank()) {
            Spacer(Modifier.height(t.spacing.xs))
            Text(
                text = stringResource(R.string.update_notes_title),
                style = t.typography.label,
                color = t.colors.inkFaint
            )
            Column(
                Modifier
                    .heightIn(max = t.proseMaxWidth)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = info.releaseNotes,
                    style = t.typography.caption,
                    color = t.colors.inkSoft
                )
            }
        }
        Spacer(Modifier.height(t.spacing.m))
        PrimaryButton(text = stringResource(R.string.update_download), onClick = onDownload)
        Spacer(Modifier.height(t.spacing.xs))
        if (!canInstall) {
            Text(
                text = stringResource(R.string.update_install_permission_hint),
                style = t.typography.label,
                color = t.colors.inkFaint
            )
            Spacer(Modifier.height(t.spacing.xs))
            SecondaryButton(
                text = stringResource(R.string.update_go_permission_settings),
                onClick = onGrantInstallPermission
            )
        }
        val releasePage = RELEASE_PAGE_BASE + info.tag
        LinguaTextButton(
            text = stringResource(R.string.update_open_release_page),
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releasePage)))
            }
        )
    }
}

@Composable
private fun DownloadedBody(
    apk: java.io.File?,
    canInstall: Boolean,
    onGrantInstallPermission: () -> Unit
) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    if (canInstall && apk != null) {
        PrimaryButton(
            text = stringResource(R.string.update_install),
            onClick = { context.startActivity(ApkInstaller.installIntent(context, apk)) }
        )
    } else {
        Text(
            text = stringResource(R.string.update_install_permission_hint),
            style = t.typography.label,
            color = t.colors.inkFaint
        )
        Spacer(Modifier.height(t.spacing.xs))
        PrimaryButton(
            text = stringResource(R.string.update_go_permission_settings),
            onClick = onGrantInstallPermission
        )
    }
}
