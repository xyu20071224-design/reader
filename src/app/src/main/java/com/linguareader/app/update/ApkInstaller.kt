package com.linguareader.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把下载好的 APK 交给系统安装器。
 *
 * 侧载应用无法静默安装：targetSdk 35 下需要 REQUEST_INSTALL_PACKAGES 声明 +
 * 用户在系统设置里给本应用开「允许安装未知应用」。[canInstall] 为 false 时
 * 用 [installPermissionIntent] 引导用户去开开关，回来再按 [installIntent] 安装。
 */
object ApkInstaller {

    /** 与 manifest 里 FileProvider 的 `${applicationId}.fileprovider` 对应。 */
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 跳到系统「安装未知应用」授权页（仅 API 26+ 有意义）。 */
    fun installPermissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 拉起系统安装器安装 [apk]。 */
    fun installIntent(context: Context, apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + AUTHORITY_SUFFIX,
            apk
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
