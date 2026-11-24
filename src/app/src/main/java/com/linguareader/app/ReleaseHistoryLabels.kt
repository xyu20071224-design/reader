package com.linguareader.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.linguareader.app.update.ReleaseHistoryLabels

@Composable
internal fun releaseHistoryLabels(): ReleaseHistoryLabels {
    val context = LocalContext.current
    return ReleaseHistoryLabels(
        title = stringResource(R.string.release_history_title),
        currentTab = stringResource(R.string.release_history_current_tab),
        upgradeTab = stringResource(R.string.release_history_upgrade_tab),
        historyTab = stringResource(R.string.release_history_all_tab),
        refresh = stringResource(R.string.release_history_refresh),
        refreshing = stringResource(R.string.release_history_refreshing),
        markRead = stringResource(R.string.release_history_mark_read),
        read = stringResource(R.string.release_history_read),
        missingCurrent = stringResource(R.string.release_history_current_missing),
        previousUnknown = stringResource(R.string.release_history_previous_unknown),
        noUpgrade = stringResource(R.string.release_history_no_upgrade),
        emptyHistory = stringResource(R.string.release_history_empty),
        emptyNotes = stringResource(R.string.release_history_notes_empty),
        notLoaded = stringResource(R.string.release_history_not_loaded),
        incomplete = stringResource(R.string.release_history_incomplete),
        cached = stringResource(R.string.release_history_cached),
        failed = stringResource(R.string.release_history_failed),
        currentVersion = { context.getString(R.string.release_history_current_version, it) },
        releaseRange = { from, to -> context.getString(R.string.release_history_release_range, from, to) },
        upgradeRange = { from, to -> context.getString(R.string.release_history_upgrade_range, from, to) },
        downgrade = { from, to -> context.getString(R.string.release_history_downgrade, from, to) },
        patchRevision = { context.getString(R.string.release_history_patch_revision, it) }
    )
}
