package com.linguareader.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.components.SecondaryButton
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.skin.LocalDesignTokens
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
 *
 * P3 迁移：外壳换成 [LinguaSheet]（拖柄/圆角/边距统一由组件给，标题走 title 槽），
 * 按钮换成 [PrimaryButton]/[SecondaryButton]/[LinguaTextButton]，样式一律取
 * [LocalDesignTokens]。**校验规则、协议推导、默认值优先级与文案零改动。**
 */
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
    /** 打开「同步明细」二级页面（调用方负责关弹层 + 开二级页）。 */
    onOpenDetail: () -> Unit = {},
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
    // http/https 的简单 IPv4[:port] 都回填地址框；域名/带路径回填高级区的完整 URL。
    val simpleAddress = remember(initial.serverUrl) { ServerAddress.toInput(initial.serverUrl) }
    var address by remember { mutableStateOf(simpleAddress ?: "") }
    var fullUrl by remember { mutableStateOf(if (simpleAddress == null) initial.serverUrl else "") }
    var fingerprint by remember { mutableStateOf(initial.pinnedCertSha256) }
    var username by remember { mutableStateOf(settings.username) }
    var password by remember { mutableStateOf("") }
    // 无已保存值也无默认值 → 展开高级区提示手填；否则收起，日常只填用户名+密码。
    var advanced by remember { mutableStateOf(initial.needsManualAddress) }
    var error by remember { mutableStateOf<ServerAddressError?>(null) }

    // 协议推导（task-6）：证书指纹非空 → 推 https（自签部署只需填指纹，不必手写完整 URL）；
    // 初值本身就是 https（公网 CA、无需固定指纹）也保持 https；否则维持 http。
    val secure = fingerprint.trim().isNotEmpty() || ServerAddress.isSecure(initial.serverUrl)

    val errorText = when (error) {
        ServerAddressError.EMPTY -> stringResource(R.string.sync_error_empty)
        ServerAddressError.BAD_IPV4 -> stringResource(R.string.sync_error_bad_ipv4)
        ServerAddressError.BAD_PORT -> stringResource(R.string.sync_error_bad_port)
        ServerAddressError.BAD_URL -> stringResource(R.string.sync_error_bad_url)
        null -> null
    }

    // 保存与登录共用同一套输入校验：非法输入行内报错并拒绝动作。
    // 两个地址框都空且存在默认值 → 回落默认 URL（不得报「请填写服务器地址」）。
    fun submit(onValid: (SyncSettings) -> Unit) {
        val input = SyncServerResolver.submitInput(initial, address, fullUrl)
        when (val result = ServerAddress.compose(input.address, input.fullUrl, secure)) {
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

    LinguaSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.sync_title),
        // 内容体自带显式 spacedBy（既有排布），关掉容器默认块间距，避免双倍叠加。
        contentArrangement = Arrangement.Top
    ) {
        SyncSheetContent(
            form = SyncSheetForm(
                username = username,
                password = password,
                address = address,
                fullUrl = fullUrl,
                fingerprint = fingerprint,
                advanced = advanced,
                needsManualAddress = initial.needsManualAddress,
                errorText = errorText
            ),
            busy = busy,
            status = status,
            cloudBooks = cloudBooks,
            cloudBooksLoaded = cloudBooksLoaded,
            cloudBooksLoading = cloudBooksLoading,
            onUsernameChange = { username = it },
            onPasswordChange = { password = it },
            onToggleAdvanced = { advanced = !advanced },
            onAddressChange = {
                address = it
                error = null
            },
            onFullUrlChange = {
                fullUrl = it
                error = null
            },
            onFingerprintChange = { fingerprint = it },
            // 登录用「当前输入」推导的设置（不是已保存的旧值）：改了地址没点保存也能登录；
            // 只有登录成功才由 AndroidSyncController 落库（失败不写设置）。
            onSave = { submit { onSave(it) } },
            onLogin = { submit { settings -> onLogin(settings, password) } },
            onSyncNow = onSyncNow,
            onLogout = onLogout,
            onOpenDetail = onOpenDetail,
            onRefreshCloudBooks = onRefreshCloudBooks,
            onDownloadCloudBook = onDownloadCloudBook
        )
    }
}

/**
 * 云同步表单草稿：用户输入 + 由输入派生出的行内校验标记。外壳持有，内容体只读。
 *
 * 抽成数据类而不是把十几个参数摊平：内容体的参数已经很多，再把「输入」与「回调」
 * 混在一起，渲染台夹具会读不出哪几个是同一份表单的数据。
 */
internal data class SyncSheetForm(
    val username: String = "",
    val password: String = "",
    val address: String = "",
    val fullUrl: String = "",
    val fingerprint: String = "",
    val advanced: Boolean = false,
    val needsManualAddress: Boolean = false,
    val errorText: String? = null
) {
    /** 高级区非空时 compose 只认完整 URL，行内报错要落在真正出错的那个框上。 */
    val addressInvalid: Boolean get() = errorText != null && fullUrl.isBlank()
    val fullUrlInvalid: Boolean get() = errorText != null && fullUrl.isNotBlank()
}

/**
 * 弹层的**内容体**，与弹层外壳解耦。
 *
 * 抽出来的理由：`ModalBottomSheet` 在渲染台（Robolectric）里画不进位图，于是迁移前/后的
 * 视觉对比根本没法做。
 *
 * ⚠️ **抽取是有代价的：本组件不再自带宽度/背景约束**，单独使用时调用方必须补齐
 * （渲染台用法见 `SettingsSheetRenderTest`）。
 *
 * 内容（输入框 + 高级区 + 两行按钮 + 状态 + 云端书单）在手机屏高下超出一屏，
 * 因此整块可纵向滚动 —— 这是 Pixel 5 实机 dump 发现「按钮被顶到屏幕外」后加的，
 * 迁移时原样保留。
 */
@Composable
internal fun SyncSheetContent(
    form: SyncSheetForm,
    busy: Boolean,
    status: String?,
    cloudBooks: List<CloudBook>,
    cloudBooksLoaded: Boolean,
    cloudBooksLoading: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onToggleAdvanced: () -> Unit,
    onAddressChange: (String) -> Unit,
    onFullUrlChange: (String) -> Unit,
    onFingerprintChange: (String) -> Unit,
    onSave: () -> Unit,
    onLogin: () -> Unit,
    onSyncNow: () -> Unit,
    onLogout: () -> Unit,
    onOpenDetail: () -> Unit,
    onRefreshCloudBooks: () -> Unit,
    onDownloadCloudBook: (CloudBook) -> Unit
) {
    val t = LocalDesignTokens.current
    // 弹层里的输入框没有原语组件，配色统一走 token（与 VocabularyScreen 搜索框同一打法）。
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = t.colors.accent,
        focusedLabelColor = t.colors.accent,
        cursorColor = t.colors.accent,
        unfocusedBorderColor = t.colors.outline,
        unfocusedLabelColor = t.colors.inkFaint,
        errorBorderColor = t.colors.danger,
        errorLabelColor = t.colors.danger
    )

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SyncContentGap)
    ) {
        OutlinedTextField(
            value = form.username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.sync_username_label)) },
            singleLine = true,
            shape = t.smallShape,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = form.password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.sync_password_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = t.smallShape,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        // 高级设置：服务器地址 + 完整 URL（HTTPS/反代）+ 证书指纹，默认收起并已预填。
        LinguaTextButton(
            text = stringResource(R.string.sync_advanced_toggle),
            onClick = onToggleAdvanced
        )
        if (form.advanced) {
            OutlinedTextField(
                value = form.address,
                onValueChange = onAddressChange,
                label = { Text(stringResource(R.string.sync_server_label)) },
                singleLine = true,
                isError = form.addressInvalid,
                shape = t.smallShape,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.fullUrl,
                onValueChange = onFullUrlChange,
                label = { Text(stringResource(R.string.sync_full_url_label)) },
                singleLine = true,
                isError = form.fullUrlInvalid,
                shape = t.smallShape,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.fingerprint,
                onValueChange = onFingerprintChange,
                label = { Text(stringResource(R.string.sync_fingerprint_label)) },
                shape = t.smallShape,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            // task-6：填了指纹就自动按 https 推导，不必再去上面手写完整 URL。
            Text(
                text = stringResource(R.string.sync_https_fingerprint_hint),
                style = t.typography.caption,
                color = t.colors.inkSoft
            )
            if (form.needsManualAddress) {
                // 没有内置默认服务器：明确告诉用户要在这里填地址（保留手填能力）。
                Text(
                    text = stringResource(R.string.sync_manual_server_hint),
                    style = t.typography.caption,
                    color = t.colors.inkSoft
                )
            }
        }

        form.errorText?.let { message ->
            Text(
                text = message,
                style = t.typography.caption,
                color = t.colors.danger
            )
        }

        // 四个按钮排一行会在手机宽度下溢出（实机 dump 时「登出」跑到屏幕外），
        // 因此拆成两行。两行内各自 spacedBy(spacing.s)。
        Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
            PrimaryButton(text = stringResource(R.string.sync_save), onClick = onSave, enabled = !busy)
            PrimaryButton(text = stringResource(R.string.sync_login), onClick = onLogin, enabled = !busy)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(t.spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrimaryButton(text = stringResource(R.string.sync_now), onClick = onSyncNow, enabled = !busy)
            LinguaTextButton(
                text = stringResource(R.string.sync_logout),
                onClick = onLogout,
                enabled = !busy
            )
        }

        // 二级界面入口：登录后一眼能看清「到底同步了什么」，不必只靠一行状态文案。
        LinguaTextButton(
            text = stringResource(R.string.sync_detail_entry),
            onClick = onOpenDetail,
            enabled = !busy
        )

        if (status != null) {
            Spacer(Modifier.height(SyncTightGap))
            Text(text = status, style = t.typography.caption, color = t.colors.inkSoft)
        }

        // 云端书单：书籍正文（blob）按内容寻址存在服务端，这里列「远端有、本机没有」的书，
        // 点「下载」重跑导入器落到本地书库。没登录/没拉过显示引导文案。
        SectionHeader(text = stringResource(R.string.sync_cloud_books_title))
        Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
            SecondaryButton(
                text = stringResource(
                    if (cloudBooksLoaded) R.string.sync_cloud_refresh else R.string.sync_cloud_load
                ),
                onClick = onRefreshCloudBooks,
                enabled = !busy && !cloudBooksLoading
            )
        }
        when {
            cloudBooksLoading -> Text(
                text = stringResource(R.string.sync_cloud_loading),
                style = t.typography.caption,
                color = t.colors.inkSoft
            )
            !cloudBooksLoaded -> Text(
                text = stringResource(R.string.sync_cloud_hint),
                style = t.typography.caption,
                color = t.colors.inkSoft
            )
            cloudBooks.isEmpty() -> Text(
                text = stringResource(R.string.sync_cloud_empty),
                style = t.typography.caption,
                color = t.colors.inkSoft
            )
            else -> cloudBooks.forEach { cloud ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = cloud.displayName + " · " + formatStorageBytes(cloud.sizeBytes),
                        style = t.typography.caption,
                        color = t.colors.ink
                    )
                    LinguaTextButton(
                        text = stringResource(R.string.sync_cloud_download),
                        onClick = { onDownloadCloudBook(cloud) },
                        enabled = !busy
                    )
                }
            }
        }
    }
}

// ── 组件专有几何常量 ──────────────────────────────────────────────
//
// 这两个值不进间距梯：梯里只有 4/8/16/24/32/48，而这一屏的内容块间距沿用迁移前的
// 10dp、状态行前的细间隙沿用 2dp。硬套档位（10→8、2→4）会改变像素，而 P3 的验收前提是
// 「迁移前后视觉等价」。它们又确实是这一屏自己的排布常量，不是「同一语义跨屏」的间距，
// 故集中命名而非塞进梯子。
private val SyncContentGap = 10.dp
private val SyncTightGap = 2.dp
