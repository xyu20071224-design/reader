package com.linguareader.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.shared.update.ReleaseUrls
import java.net.URI

@Composable
internal fun UpdateSourceSection(catalogUrl: String?, enabled: Boolean, onChange: (String?) -> Unit) {
    val t = LocalDesignTokens.current
    var editing by remember { mutableStateOf(false) }
    Text(stringResource(R.string.update_source_title), color = t.colors.ink, style = t.typography.label)
    Text(catalogUrl ?: stringResource(R.string.update_source_github),
        color = t.colors.inkSoft, style = t.typography.caption)
    LinguaTextButton(stringResource(R.string.update_source_change), onClick = { editing = true }, enabled = enabled)
    if (editing) {
        UpdateSourceDialog(catalogUrl, onDismiss = { editing = false }, onSave = {
            onChange(it)
            editing = false
        })
    }
}

@Composable
private fun UpdateSourceDialog(catalogUrl: String?, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var mirror by remember { mutableStateOf(catalogUrl != null) }
    var address by remember { mutableStateOf(catalogUrl.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_source_title)) },
        text = {
            Column {
                listOf(false to R.string.update_source_github, true to R.string.update_source_mirror).forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().clickable { mirror = value; invalid = false },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mirror == value, onClick = { mirror = value; invalid = false })
                        Text(stringResource(label))
                    }
                }
                if (mirror) OutlinedTextField(
                    value = address, onValueChange = { address = it; invalid = false },
                    label = { Text(stringResource(R.string.update_source_catalog)) },
                    singleLine = true, isError = invalid, modifier = Modifier.fillMaxWidth()
                )
                if (invalid) Text(stringResource(R.string.update_source_invalid))
                Text(stringResource(R.string.update_source_hint))
            }
        },
        confirmButton = { LinguaTextButton(stringResource(R.string.common_save), onClick = {
            if (!mirror) onSave(null)
            else {
                val normalized = runCatching {
                    URI(address.trim()).toASCIIString().also { ReleaseUrls.mirror(it) }
                }.getOrNull()
                if (normalized == null) invalid = true else onSave(normalized)
            }
        }) },
        dismissButton = { LinguaTextButton(stringResource(R.string.common_cancel), onClick = onDismiss) }
    )
}
