package com.linguareader.app.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
actual fun rememberFileOpenLauncher(onResult: (File?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        onResult(uri?.let { copyUriToCache(context, it) })
    }
    return remember {
        {
            launcher.launch(
                arrayOf(
                    "application/epub+zip",
                    "application/zip",
                    "application/octet-stream",
                    "text/plain",
                    "application/x-fictionbook+xml",
                    "application/pdf"
                )
            )
        }
    }
}

@Composable
actual fun rememberFileSaveLauncher(
    defaultName: String,
    onResult: (OutputTarget?) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) {
            onResult(null)
        } else {
            onResult(OutputTarget { context.contentResolver.openOutputStream(uri, "w")!! })
        }
    }
    return remember {
        { launcher.launch(defaultName) }
    }
}

private fun copyUriToCache(context: Context, uri: Uri): File {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
                } else null
            }
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "book"
    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val target = File(context.cacheDir, "import-${System.currentTimeMillis()}-$safeName")
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: throw IllegalArgumentException("无法读取所选文件")
    return target
}
