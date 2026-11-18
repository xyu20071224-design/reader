package com.linguareader.app.update

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.R
import com.linguareader.app.UpdateSheetContent
import com.linguareader.shared.update.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class NativeUpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val patch = NativePatchInfo("reader-25",25,1,"fix.lrpatch",
        "https://github.com/xyu20071224-design/reader/releases/download/v2.3.0/fix.lrpatch","a".repeat(64),10,"")
    private fun show(state: AppUpdateUiState, download: () -> Unit = {}, rollback: () -> Unit = {}, restart: () -> Unit = {}) {
        compose.setContent { MaterialTheme { Column {
            UpdateSheetContent(state, false, {}, {}, download, {}, {}, onRollbackPatch = rollback, onRestartApp = restart)
        } } }
    }
    @Test fun hotfixUsesApplyActionWithoutInstallerPermission() {
        var count = 0
        show(AppUpdateUiState(phase = AppUpdatePhase.AVAILABLE,
            info = AppUpdateInfo("v2.3.0","2.3.0",patch.assetName,patch.downloadUrl,"","",patch)), { count++ })
        compose.onNodeWithText(context.getString(R.string.update_hotfix_download)).assertIsDisplayed().performClick()
        assertEquals(1,count)
        compose.onNodeWithText(context.getString(R.string.update_go_permission_settings)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.update_install)).assertDoesNotExist()
    }
    @Test fun preparedPatchOffersRollbackAndExplainsReopening() {
        var count = 0
        var restarts = 0
        show(AppUpdateUiState(phase=AppUpdatePhase.RESTART_REQUIRED), rollback={count++}, restart={restarts++})
        compose.onNodeWithText(context.getString(R.string.update_hotfix_ready)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.update_hotfix_restart)).performClick()
        assertEquals(1,restarts)
        compose.onNodeWithText(context.getString(R.string.update_hotfix_rollback)).performClick()
        assertEquals(1,count)
    }
    @Test fun mergeCannotBeCancelledOrReplacedThroughUi() {
        show(AppUpdateUiState(phase=AppUpdatePhase.APPLYING_PATCH,nativePatchRevision=1))
        compose.onNodeWithText(context.getString(R.string.update_hotfix_applying)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.update_download_cancel)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.update_hotfix_rollback)).assertDoesNotExist()
    }
}
