package com.linguareader.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.shared.update.InstallationChange
import com.linguareader.shared.update.ReleaseHistoryEntry

/** Caller supplies localized resources, so this component does not own shared strings.xml. */
data class ReleaseHistoryLabels(
    val title: String,
    val currentTab: String,
    val upgradeTab: String,
    val historyTab: String,
    val refresh: String,
    val refreshing: String,
    val markRead: String,
    val read: String,
    val missingCurrent: String,
    val previousUnknown: String,
    val noUpgrade: String,
    val emptyHistory: String,
    val emptyNotes: String,
    val notLoaded: String,
    val incomplete: String,
    val cached: String,
    val failed: String,
    val currentVersion: (String) -> String,
    val releaseRange: (String, String) -> String,
    val upgradeRange: (String, String) -> String,
    val downgrade: (String, String) -> String,
    val patchRevision: (Int) -> String
)

@Composable
fun ReleaseHistorySheet(
    view: AppReleaseHistoryView,
    labels: ReleaseHistoryLabels,
    onRefresh: () -> Unit,
    onMarkRead: () -> Unit,
    onDismiss: () -> Unit,
    loading: Boolean = false
) {
    LinguaSheet(onDismiss = onDismiss, contentArrangement = Arrangement.Top) {
        ReleaseHistoryContent(view, labels, onRefresh, onMarkRead, loading)
    }
}

/** A persistent history surface, independent from the APK download or native-patch phase. */
@Composable
fun ReleaseHistoryContent(
    view: AppReleaseHistoryView,
    labels: ReleaseHistoryLabels,
    onRefresh: () -> Unit,
    onMarkRead: () -> Unit,
    loading: Boolean = false
) {
    val t = LocalDesignTokens.current
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(t.spacing.m)) {
        Text(labels.title, style = t.typography.title, color = t.colors.ink)
        Text(labels.currentVersion(view.installation.current.versionName), style = t.typography.caption, color = t.colors.inkSoft)
        if (view.nativePatchRevision > 0) {
            Text(labels.patchRevision(view.nativePatchRevision), style = t.typography.caption, color = t.colors.inkSoft)
        }
        Row {
            PrimaryButton(if (loading) labels.refreshing else labels.refresh, onClick = onRefresh, enabled = !loading)
            if (view.unread) LinguaTextButton(labels.markRead, onClick = onMarkRead)
            else Text(labels.read, style = t.typography.caption, color = t.colors.inkFaint)
        }
        if (view.history.issues.isNotEmpty()) {
            Text(labels.failed, style = t.typography.caption, color = t.colors.danger)
        }
        if (view.history.usesCachedEntries) Text(labels.cached, style = t.typography.caption, color = t.colors.inkFaint)
        if (!view.history.complete) Text(
            if (view.history.fetchedAtMillis == 0L) labels.notLoaded else labels.incomplete,
            style = t.typography.caption, color = t.colors.inkSoft)
        TabRow(selectedTabIndex = selected, containerColor = t.colors.paper, contentColor = t.colors.accent) {
            listOf(labels.currentTab, labels.upgradeTab, labels.historyTab).forEachIndexed { index, text ->
                Tab(selected = selected == index, onClick = { selected = index }, text = { Text(text) })
            }
        }
        when (selected) {
            0 -> {
                val notes = view.currentNotes
                if (notes == null) Text(labels.missingCurrent, color = t.colors.inkSoft)
                else {
                    Text(labels.releaseRange(notes.previousReleaseTag, notes.releaseTag),
                        style = t.typography.label, color = t.colors.inkFaint)
                    Text(notes.notes.ifBlank { labels.emptyNotes }, style = t.typography.body, color = t.colors.ink)
                }
            }
            1 -> {
                val installation = view.installation
                when (installation.change) {
                    InstallationChange.UPGRADE -> {
                        Text(labels.upgradeRange(installation.previousVersion.orEmpty(), installation.current.versionName),
                            style = t.typography.label, color = t.colors.inkFaint)
                        if (!view.upgrade.complete) Text(labels.incomplete, color = t.colors.inkSoft)
                        HistoryEntries(view.upgrade.entries, labels)
                    }
                    InstallationChange.FIRST_OBSERVED -> Text(labels.previousUnknown, color = t.colors.inkSoft)
                    InstallationChange.DOWNGRADE -> Text(labels.downgrade(installation.previousVersion.orEmpty(),
                        installation.current.versionName), color = t.colors.inkSoft)
                    else -> Text(labels.noUpgrade, color = t.colors.inkSoft)
                }
            }
            else -> HistoryEntries(view.publishedEntries, labels)
        }
        Spacer(Modifier.height(t.spacing.m))
    }
}

@Composable
private fun HistoryEntries(entries: List<ReleaseHistoryEntry>, labels: ReleaseHistoryLabels) {
    val t = LocalDesignTokens.current
    if (entries.isEmpty()) Text(labels.emptyHistory, color = t.colors.inkSoft)
    entries.forEach { entry ->
        Text(entry.tag, style = t.typography.label, color = t.colors.accent)
        Text(entry.notes.ifBlank { labels.emptyNotes }, style = t.typography.body, color = t.colors.ink)
    }
}
