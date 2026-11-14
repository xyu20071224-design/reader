package com.linguareader.app.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.linguareader.app.R
import com.linguareader.app.skin.LocalDesignTokens

/** 折叠只管理展示；配置值由调用方持有，收起时不重置。 */
@Composable
internal fun ExpandableSettingsSection(
    title: String,
    summary: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val t = LocalDesignTokens.current
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val state = stringResource(if (expanded) R.string.settings_expanded else R.string.settings_collapsed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics(mergeDescendants = true) { stateDescription = state }
                .padding(vertical = t.spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = t.typography.label, color = t.colors.ink)
                if (!summary.isNullOrBlank()) {
                    Text(summary, style = t.typography.caption, color = t.colors.inkSoft)
                }
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null, tint = t.colors.inkSoft)
        }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(t.spacing.s), content = content)
    }
}
