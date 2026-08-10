package com.linguareader.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FileOutputStream

@Composable
actual fun rememberFileOpenLauncher(onResult: (File?) -> Unit): () -> Unit {
    return remember {
        {
            val dialog = FileDialog(parentFrame(), "选择电子书", FileDialog.LOAD)
            dialog.isMultipleMode = false
            dialog.isVisible = true
            val selected = dialog.files.firstOrNull()
            onResult(selected)
        }
    }
}

@Composable
actual fun rememberFileSaveLauncher(
    defaultName: String,
    onResult: (OutputTarget?) -> Unit
): () -> Unit {
    return remember {
        {
            val dialog = FileDialog(parentFrame(), "导出生词表", FileDialog.SAVE)
            dialog.file = defaultName
            dialog.isVisible = true
            val file = dialog.files.firstOrNull()
            if (file == null) {
                onResult(null)
            } else {
                onResult(OutputTarget { FileOutputStream(file) })
            }
        }
    }
}

private fun parentFrame(): Frame? =
    Frame.getFrames().firstOrNull { it.isFocused } ?: Frame.getFrames().firstOrNull()
