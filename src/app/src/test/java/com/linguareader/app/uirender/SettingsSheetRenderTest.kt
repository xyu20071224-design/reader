package com.linguareader.app.uirender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.linguareader.app.AiDrawerContent
import com.linguareader.app.AppShapes
import com.linguareader.app.LightLinguaPalette
import com.linguareader.app.LocalLinguaPalette
import com.linguareader.app.PacksContent
import com.linguareader.app.Paper
import com.linguareader.app.StorageContent
import com.linguareader.app.SyncSheetContent
import com.linguareader.app.SyncSheetForm
import com.linguareader.app.colorSchemeFor
import com.linguareader.app.packs.LoadWarning
import com.linguareader.app.packs.PackResidual
import com.linguareader.app.packs.PackUiItem
import com.linguareader.app.packs.PackUiState
import com.linguareader.app.packs.RemotePacksState
import com.linguareader.app.ai.AiProviderProfile
import com.linguareader.app.ai.AiSettings
import com.linguareader.shared.ai.AiProtocol
import com.linguareader.shared.data.StorageReport
import com.linguareader.shared.data.StoreUsage
import com.linguareader.shared.packs.PackType
import com.linguareader.shared.sync.CloudBook
import com.linguareader.shared.sync.SyncSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 四个设置类弹层（批次二）的 **before / after** 视觉回归夹具。
 *
 * ## 为什么只画内容体
 *
 * `ModalBottomSheet` 在 Robolectric 里画不进位图（它走 Dialog/Window 那条路），
 * 所以迁移前后只能对**内容体**做对比。内容体正是用户真正看到的部分；外壳的圆角、
 * 拖柄、遮罩由 `LinguaSheet` 统一，不参与逐屏对比。
 *
 * ## 夹具怎么做到对新旧两版都编译
 *
 * 本文件**只引用四个内容体函数与公开数据模型**（`StorageContent` / `SyncSheetContent` /
 * `AiDrawerContent` / `PacksContent`），不引用迁移过程中新增的 token 或组件。
 * 出图流程：
 * ```
 * <run SettingsSheetRenderTest>   # 产出 storage-content.png 等 = before
 * <迁移实现>
 * <run SettingsSheetRenderTest>   # 同名覆盖 = after，复制成 -after 并排看
 * ```
 *
 * ## 画布尺寸
 *
 * 一律 [ComposeRenderHarness.renderDp]（xxhdpi 下 411×891dp = 1233×2673px）。
 * 直接传像素会把可用宽度算成 180dp，文字被裁成半截 —— 那个坑见 `SheetMigrationRenderTest`。
 *
 * 出图位置：app/build/ui-render-probe/sheet-*.png
 *
 * **不是真机替代品**：拖拽关闭、SAF 选文件、输入法、滚动都测不到。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsSheetRenderTest {

    /**
     * 弹层内容体的宿主夹具：纸色铺满 + 水平 24dp / 底部 32dp 留白。
     *
     * 与四个弹层迁移前的 `Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)
     * .padding(bottom = 32.dp))` 逐个数值对齐 —— 迁移后这几个数字由 `LinguaSheet` 提供
     * （水平 spacing.l = 24、底部 spacing.xl = 32），所以同一个夹具对新旧两版都成立。
     */
    private fun renderSheetContent(name: String, content: @Composable () -> Unit) =
        ComposeRenderHarness.renderDp(name, 411, 891) {
            CompositionLocalProvider(LocalLinguaPalette provides LightLinguaPalette) {
                MaterialTheme(colorScheme = colorSchemeFor(LightLinguaPalette), shapes = AppShapes) {
                    Box(Modifier.fillMaxSize().background(Paper)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(top = 20.dp, bottom = 32.dp)
                        ) {
                            content()
                        }
                    }
                }
            }
        }

    private fun countNonPaper(bmp: android.graphics.Bitmap): Int {
        val paper = LightLinguaPalette.paper.toArgb()
        var n = 0
        for (x in 0 until bmp.width step 3) {
            for (y in 0 until bmp.height step 3) {
                if (bmp.getPixel(x, y) != paper) n++
            }
        }
        return n
    }

    // ── StorageSheet ─────────────────────────────────────────────

    private fun storageReport() = StorageReport(
        usages = listOf(
            StoreUsage("books", 1_284_000_000L),
            StoreUsage("packs", 214_000_000L),
            StoreUsage("tts_cache", 87_400_000L),
            StoreUsage("vocabulary", 1_240_000L),
            StoreUsage("ai/book-context", 640_000L),
            StoreUsage("translations", 0L)
        ),
        orphans = emptyList()
    )

    @Test
    fun storageSheetContentRenders() {
        val bmp = renderSheetContent("sheet-storage") {
            StorageContent(
                report = storageReport(),
                scanning = false,
                packCount = 3,
                packBytes = 214_000_000L,
                onRescan = {},
                onCleanOrphans = {},
                onManagePacks = {}
            )
        }
        val nonPaper = countNonPaper(bmp)
        assertTrue("存储页必须画出占用分项与资源包入口（实际 $nonPaper 个非纸色采样点）", nonPaper > 300)
        assertTrue("出图应当落盘", File("build/ui-render-probe/sheet-storage.png").isFile)
    }

    @Test
    fun storageSheetWithOrphansRenders() {
        // 孤儿分支是这一屏唯一的交互入口（清理按钮），必须单独出图守住它。
        val report = StorageReport(
            usages = storageReport().usages,
            orphans = listOf(
                com.linguareader.shared.data.BookDataOrphan("books", java.io.File("gone"), 12_000_000L),
                com.linguareader.shared.data.BookDataOrphan("vocabulary", java.io.File("gone2"), 220_000L)
            )
        )
        val bmp = renderSheetContent("sheet-storage-orphans") {
            StorageContent(
                report = report,
                scanning = false,
                packCount = 0,
                packBytes = 0L,
                onRescan = {},
                onCleanOrphans = {},
                onManagePacks = {}
            )
        }
        assertTrue("有孤儿时必须画出清理入口", countNonPaper(bmp) > 300)
    }

    // ── SyncSheet ────────────────────────────────────────────────

    @Test
    fun syncSheetSimpleFormRenders() {
        val bmp = renderSheetContent("sheet-sync") {
            SyncSheetContent(
                form = SyncSheetForm(username = "reader", password = "secret"),
                busy = false,
                status = null,
                cloudBooks = emptyList(),
                cloudBooksLoaded = false,
                cloudBooksLoading = false,
                onUsernameChange = {},
                onPasswordChange = {},
                onToggleAdvanced = {},
                onAddressChange = {},
                onFullUrlChange = {},
                onFingerprintChange = {},
                onSave = {},
                onLogin = {},
                onSyncNow = {},
                onLogout = {},
                onOpenDetail = {},
                onRefreshCloudBooks = {},
                onDownloadCloudBook = {}
            )
        }
        assertTrue("同步页必须画出两个输入框与按钮行", countNonPaper(bmp) > 400)
        assertTrue("出图应当落盘", File("build/ui-render-probe/sheet-sync.png").isFile)
    }

    @Test
    fun syncSheetAdvancedAndCloudBooksRender() {
        // 高级区（含证书指纹）+ 行内报错 + 云端书单三态同时出图，覆盖最长的滚动内容。
        val bmp = renderSheetContent("sheet-sync-advanced") {
            SyncSheetContent(
                form = SyncSheetForm(
                    username = "reader",
                    password = "secret",
                    address = "192.168.1.5",
                    fullUrl = "",
                    fingerprint = "AA:BB:CC:DD",
                    advanced = true,
                    needsManualAddress = true,
                    errorText = "请输入合法的 IPv4 地址，如 192.168.1.5"
                ),
                busy = false,
                status = "同步完成：上传 3，下载 1，待推送 0",
                cloudBooks = listOf(
                    CloudBook("book-0001", 8_400_000L, "The Hobbit"),
                    CloudBook("book-0002", 1_200_000L, "")
                ),
                cloudBooksLoaded = true,
                cloudBooksLoading = false,
                onUsernameChange = {},
                onPasswordChange = {},
                onToggleAdvanced = {},
                onAddressChange = {},
                onFullUrlChange = {},
                onFingerprintChange = {},
                onSave = {},
                onLogin = {},
                onSyncNow = {},
                onLogout = {},
                onOpenDetail = {},
                onRefreshCloudBooks = {},
                onDownloadCloudBook = {}
            )
        }
        assertTrue("高级区 + 云端书单必须画出内容", countNonPaper(bmp) > 600)
    }

    // ── AiDrawerSheet ────────────────────────────────────────────

    private fun aiSettings(power: Boolean, remote: Boolean) = AiSettings(
        enabled = remote,
        apiKey = if (remote) "sk-xxxx" else "",
        powerEnabled = power,
        providers = if (remote) {
            listOf(
                AiProviderProfile(
                    id = "p1",
                    name = "DeepSeek 官方",
                    baseUrl = "https://api.deepseek.com",
                    apiKey = "sk-xxxx",
                    protocol = AiProtocol.OPENAI_COMPAT,
                    model = "deepseek-v4-flash"
                )
            )
        } else {
            emptyList()
        },
        activeProviderId = if (remote) "p1" else ""
    )

    @Test
    fun aiDrawerTranslationTabRenders() {
        val bmp = renderSheetContent("sheet-ai-drawer") {
            AiDrawerContent(
                tab = 0,
                powerEnabled = true,
                onTabChange = {},
                onPowerChange = {},
                books = emptyList(),
                aiSettings = aiSettings(power = true, remote = true),
                onAiSettingsChange = {},
                onLoadGlossary = { com.linguareader.shared.ai.BookGlossary(bookId = it) },
                onAddGlossary = { bookId, _, _ -> com.linguareader.shared.ai.BookGlossary(bookId = bookId) },
                onUpdateGlossary = { bookId, _ -> com.linguareader.shared.ai.BookGlossary(bookId = bookId) },
                onRemoveGlossary = { bookId, _ -> com.linguareader.shared.ai.BookGlossary(bookId = bookId) }
            )
        }
        assertTrue("AI 抽屉必须画出总开关、三个 Tab 与服务商列表", countNonPaper(bmp) > 400)
        assertTrue("出图应当落盘", File("build/ui-render-probe/sheet-ai-drawer.png").isFile)
    }

    @Test
    fun aiDrawerWithRemoteOffRenders() {
        // 总开关开、联网翻译关：走「关闭时保持纯本地」的说明分支。
        val bmp = renderSheetContent("sheet-ai-drawer-local") {
            AiDrawerContent(
                tab = 0,
                powerEnabled = true,
                onTabChange = {},
                onPowerChange = {},
                books = emptyList(),
                aiSettings = aiSettings(power = true, remote = false),
                onAiSettingsChange = {},
                onLoadGlossary = { com.linguareader.shared.ai.BookGlossary(bookId = it) },
                onAddGlossary = { bookId, _, _ -> com.linguareader.shared.ai.BookGlossary(bookId = bookId) },
                onUpdateGlossary = { bookId, _ -> com.linguareader.shared.ai.BookGlossary(bookId = bookId) },
                onRemoveGlossary = { bookId, _ -> com.linguareader.shared.ai.BookGlossary(bookId = bookId) }
            )
        }
        assertTrue("联网关闭时也要画出说明文案", countNonPaper(bmp) > 300)
    }

    // ── PacksSheet ───────────────────────────────────────────────

    private fun packItem(
        id: String,
        type: PackType,
        nameZh: String,
        nameEn: String,
        active: Boolean = false,
        dictionaryWords: Int? = null,
        bundleMembers: Int? = null
    ) = PackUiItem(
        packId = id,
        type = type,
        nameZh = nameZh,
        nameEn = nameEn,
        version = "1.0.0",
        bytes = 58_000_000L,
        active = active,
        installedAt = 1_700_000_000_000L,
        dictionary = dictionaryWords?.let {
            PackUiItem.DictionaryInfo(wordCount = it, source = "ECDICT", license = "MIT")
        },
        bundleMembers = bundleMembers
    )

    /** 与迁移前同一份状态：四个分区都有包 + 残留 + 远端列表 + 加载降级提示。 */
    private fun packState() = PackUiState(
        items = listOf(
            packItem("dict-ecdict", PackType.DICTIONARY, "ECDICT 词典", "ECDICT", active = true, dictionaryWords = 770_000),
            packItem("audio-hobbit", PackType.AUDIO, "《霍比特人》音频", "The Hobbit audio"),
            packItem("voice-mimo", PackType.VOICE, "MiMo 音色", "MiMo voices"),
            packItem("bundle-starter", PackType.BUNDLE, "入门合集", "Starter bundle", bundleMembers = 3)
        ),
        totalBytes = 232_000_000L,
        residuals = listOf(PackResidual("packs/.tmp", PackResidual.Kind.TEMP, 4_200_000L)),
        installing = false,
        verifying = null,
        loadWarning = LoadWarning.REGISTRY_DAMAGED,
        remote = RemotePacksState(
            assets = listOf(
                GitHubAsset("awd-lrpack", 58_000_000L),
                GitHubAsset("tts-lrpack", 120_000_000L)
            ),
            fetched = true
        )
    )

    @Test
    fun packsSheetContentRenders() {
        val bmp = renderSheetContent("sheet-packs") {
            PacksContent(
                state = packState(),
                onInstallClick = {},
                onSetActiveDictionary = {},
                onVerify = {},
                onUninstallRequest = {},
                onCleanResiduals = {},
                onLoadRemotePacks = {},
                onInstallRemotePack = {}
            )
        }
        assertTrue("资源包页必须画出四个分区与远端列表", countNonPaper(bmp) > 800)
        assertTrue("出图应当落盘", File("build/ui-render-probe/sheet-packs.png").isFile)
    }

    @Test
    fun packsSheetEmptyStateRenders() {
        // 空库是最常走的第一次路径：引导文案 + 安装按钮必须都在。
        val bmp = renderSheetContent("sheet-packs-empty") {
            PacksContent(
                state = PackUiState(),
                onInstallClick = {},
                onSetActiveDictionary = {},
                onVerify = {},
                onUninstallRequest = {},
                onCleanResiduals = {},
                onLoadRemotePacks = {},
                onInstallRemotePack = {}
            )
        }
        assertTrue("空资源包页也要画出引导文案与安装按钮", countNonPaper(bmp) > 300)
    }

    @Test
    fun packsSheetBottomSectionsRender() {
        // 891dp 画布放不下四个分区（真机上靠滚动），所以底部两个分区单独出一张：
        // 只给音色包 + 集合包，确保它们不是「看不见所以没坏」。
        // 注：曾试过把画布加高到 1400dp，但渲染台的 decorView 高度受屏幕尺寸限制，
        // 内容会提前被裁、纸色也会中途断开 —— 那是夹具失真，不是实现问题，故改为分状态出图。
        val bmp = renderSheetContent("sheet-packs-bottom") {
            PacksContent(
                state = PackUiState(
                    items = listOf(
                        packItem("voice-mimo", PackType.VOICE, "MiMo 音色", "MiMo voices"),
                        packItem("bundle-starter", PackType.BUNDLE, "入门合集", "Starter bundle", bundleMembers = 3)
                    ),
                    totalBytes = 116_000_000L
                ),
                onInstallClick = {},
                onSetActiveDictionary = {},
                onVerify = {},
                onUninstallRequest = {},
                onCleanResiduals = {},
                onLoadRemotePacks = {},
                onInstallRemotePack = {}
            )
        }
        assertTrue("音色包与集合包分区必须画出内容", countNonPaper(bmp) > 400)
    }

    private companion object {
        /** `GitHubReleaseParser.PackAsset` 的构造在 :shared 里，夹具只取名字与大小。 */
        fun GitHubAsset(name: String, bytes: Long) = com.linguareader.shared.update.GitHubReleaseParser.PackAsset(
            name = name,
            downloadUrl = "https://example.invalid/" + name,
            bytes = bytes
        )
    }
}