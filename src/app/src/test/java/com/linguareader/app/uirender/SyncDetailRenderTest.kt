package com.linguareader.app.uirender

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linguareader.app.AppShapes
import com.linguareader.app.SyncDetailScreen
import com.linguareader.app.colorSchemeFor
import com.linguareader.app.skin.SkinRegistry
import com.linguareader.app.skin.paletteOf
import com.linguareader.app.skin.provideLinguaTheme
import com.linguareader.shared.sync.CloudBook
import com.linguareader.shared.sync.SyncBookItem
import com.linguareader.shared.sync.SyncDetail
import com.linguareader.shared.sync.SyncDetailSummary
import com.linguareader.shared.sync.SyncItemState
import com.linguareader.shared.sync.SyncSettings
import com.linguareader.shared.sync.SyncWordItem
import com.linguareader.shared.ui.skin.SkinBuiltIns
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **同步明细页的视觉基线**（P3 补迁：漏网屏幕）。
 *
 * 这一页是 F-160 的二级界面，原先整屏还在用旧调色板（`Ink`/`CardSurface`/`PaperDeep`）
 * + 裸 dp + `MaterialTheme.typography.*`，深色下与已迁移的屏幕观感不一致。
 *
 * 迁移是**纯样式**改动，所以判据就是「像素几乎不变」：
 * - 基线 PNG 用迁移前的实现生成，入库；
 * - 迁移后重新渲染并与基线做像素差（[VisualBaseline]，阈值 0.5%）；
 * - 若差异是有意的，设 `UPDATE_VISUAL_BASELINE=1` 重跑更新基线，并在提交信息里说明。
 *
 * 夹具注意：`provideLinguaTheme` **同时**下发 `LocalDesignTokens` 与 `LocalLinguaPalette`，
 * 所以同一份夹具对新旧两版实现都能渲染——这正是能做出可对照 before/after 的前提。
 *
 * **不是真机替代品**：滚动、点击、刷新/同步按钮的禁用态变化都覆盖不到。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SyncDetailRenderTest {

    private val lastSyncAt = 1_700_000_000_000L

    private val settings = SyncSettings(
        enabled = true,
        serverUrl = "https://sync.example.com:8443",
        username = "reader"
    )

    /** 一本书三种状态的组合：进度已同步 + 正文已同步 + 有大小。 */
    private val detail = SyncDetail(
        books = listOf(
            SyncBookItem(
                bookId = "book-1",
                title = "The Hobbit",
                progressState = SyncItemState.SYNCED,
                sourceState = SyncItemState.SYNCED,
                sourceSizeBytes = 3_500_000L
            ),
            SyncBookItem(
                bookId = "book-2",
                title = "Pride and Prejudice",
                progressState = SyncItemState.PENDING,
                sourceState = SyncItemState.LOCAL_ONLY
            )
        ),
        words = listOf(
            SyncWordItem(wordId = "w1", headword = "ephemeral", state = SyncItemState.SYNCED),
            SyncWordItem(wordId = "w2", headword = "resilient", state = SyncItemState.PENDING),
            SyncWordItem(wordId = "w3", headword = "serendipity", state = SyncItemState.LOCAL_ONLY)
        ),
        remoteOnlyBooks = listOf(
            CloudBook(bookId = "book-9", sizeBytes = 1_200_000L, title = "Cloud Only Book")
        ),
        summary = SyncDetailSummary(syncedCount = 3, pendingCount = 2, localOnlyCount = 3)
    )

    private fun snapshot(name: String, content: @Composable () -> Unit) {
        val light = SkinBuiltIns.light
        val tokens = SkinRegistry.toDesignTokens(light)
        val bmp = ComposeRenderHarness.renderDp(name, 411, 891) {
            provideLinguaTheme(light) {
                // **必须同时下发 MaterialTheme**：迁移前的实现用 `MaterialTheme.typography.*` 与
                // Material 的默认按钮配色，若夹具不给 colorScheme，旧代码会渲染出 Material 包里
                // 那套紫色主题——那是夹具伪造出来的差异，不是真实回归。
                // 生产环境在 MainActivity 里就是 `MaterialTheme(colorScheme = colorSchemeFor(palette))`。
                MaterialTheme(
                    colorScheme = colorSchemeFor(paletteOf(tokens)),
                    shapes = AppShapes
                ) {
                    Surface(
                        color = tokens.colors.paper,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
        val result = VisualBaseline.compare(name, bmp)
        val summary = VisualBaseline.describe(name, result)
        println(summary)
        assertTrue(summary, result.passed)
    }

    @Test
    fun populatedSyncDetail() = snapshot("sync-detail-populated") {
        SyncDetailScreen(
            detail = detail,
            loading = false,
            settings = settings,
            status = "同步完成：上传 3，下载 1，待推送 2",
            lastSyncAt = lastSyncAt,
            busy = false,
            onRefresh = {},
            onSyncNow = {},
            onDownloadCloudBook = {}
        )
    }

    @Test
    fun notLoggedInSyncDetail() = snapshot("sync-detail-not-logged-in") {
        // 未启用同步时概览卡改给引导文案——这条文案是用户反馈「登录后没有界面」的修复，
        // 迁移时最容易被顺手丢掉。
        SyncDetailScreen(
            detail = null,
            loading = false,
            settings = SyncSettings(enabled = false),
            status = null,
            lastSyncAt = 0L,
            busy = false,
            onRefresh = {},
            onSyncNow = {},
            onDownloadCloudBook = {}
        )
    }

    @Test
    fun emptySectionsRenderGuidance() = snapshot("sync-detail-empty") {
        // 三个段都为空：要看到三行「暂无」引导，而不是一片空白。
        SyncDetailScreen(
            detail = SyncDetail(
                books = emptyList(),
                words = emptyList(),
                remoteOnlyBooks = emptyList(),
                summary = SyncDetailSummary(0, 0, 0)
            ),
            loading = false,
            settings = settings,
            status = null,
            lastSyncAt = 0L,
            busy = true,
            onRefresh = {},
            onSyncNow = {},
            onDownloadCloudBook = {}
        )
    }
}
