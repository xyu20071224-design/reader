package com.linguareader.app.uirender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.linguareader.app.AppShapes
import com.linguareader.app.DarkLinguaPalette
import com.linguareader.app.LightLinguaPalette
import com.linguareader.app.MultiVoiceSection
import com.linguareader.app.Paper
import com.linguareader.app.colorSchemeFor
import com.linguareader.app.skin.provideLinguaTheme
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.TtsEngineMode
import com.linguareader.shared.ui.skin.SkinBuiltIns
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 多角色音色面板（[MultiVoiceSection]）的 **before / after** 视觉回归夹具。
 *
 * ## 这一屏为什么只渲染「区块外壳 + 状态行」，不渲染完整角色列表
 *
 * `MultiVoiceSection` 的角色表与音色库来自 `MultiVoiceSupport.characters()` /
 * `library()`（要读术语表、要刷新音色库、后者必要时会**联网**拉音色表）。
 * 渲染台里这些要么返回空、要么真的走网络 —— 前者证明不了列表长什么样，
 * 后者不是渲染测试该做的事。所以夹具覆盖的是**这一屏每次都会画出来的部分**：
 *
 *  - 顶部横向分割线 + 「多角色朗读」总开关行（含随引擎状态变化的三态说明文案）；
 *  - 不支持时的引导行 / 标注入口；
 *  - 在 SYSTEM 且未标注两个音色时走「不支持」分支 —— 这正是默认出厂状态，
 *    也是用户第一次打开这一屏看到的画面。
 *
 * 角色列表、音色选择弹层、7 个对话框由 `MultiVoiceRenderTestDialogs` 的思路
 * 用同一套做法覆盖成本过高（每个都要构造 VoiceLibrary/BookVoiceMap 且会触网），
 * 这里**如实记录该盲区**并交给真机逐条走查 —— 迁移只改了样式，对话框的文案与
 * 确认逻辑在 diff 里逐条可核。
 *
 * ## 深色皮肤
 *
 * `f90a98e` 之后 `LocalDesignTokens` 才在生产里真正被下发；此前深色图取到的都是
 * 日间 token。下面的深色用例是修复后的真值基线。
 *
 * ## 画布尺寸
 *
 * 一律 [ComposeRenderHarness.renderDp]（xxhdpi 下 411×891dp = 1233×2673px）。
 *
 * 出图位置：app/build/ui-render-probe/multivoice-*.png
 *
 * **不是真机替代品**：试听播放、音色库刷新、角色增删改落库、LLM 标注都测不到。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MultiVoiceRenderTest {

    private fun countNonPaper(bmp: android.graphics.Bitmap, paper: Int): Int {
        var n = 0
        for (x in 0 until bmp.width step 3) {
            for (y in 0 until bmp.height step 3) {
                if (bmp.getPixel(x, y) != paper) n++
            }
        }
        return n
    }

    /**
     * 外壳夹具：纸色铺满 + 左右 24dp（真机上由弹层外壳 / 抽屉提供）。
     *
     * `MultiVoiceSection` 是「嵌在听书设置里的一个区块」，本身不带横向留白 ——
     * 这一点与 ListeningSettingsBody 同一约定，留白归外壳。
     */
    private fun renderSection(
        name: String,
        dark: Boolean = false,
        content: @Composable () -> Unit
    ) = ComposeRenderHarness.renderDp(name, 411, 891) {
        provideLinguaTheme(if (dark) SkinBuiltIns.dark else SkinBuiltIns.light) {
            MaterialTheme(
                colorScheme = colorSchemeFor(if (dark) DarkLinguaPalette else LightLinguaPalette),
                shapes = AppShapes
            ) {
                Box(Modifier.fillMaxSize().background(Paper)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 20.dp)
                    ) { content() }
                }
            }
        }
    }

    /** 预置一份引擎设置：多角色面板按 `settings.mode` 决定「支持 / 不支持」分支。 */
    private fun seedMode(mode: TtsEngineMode, multiVoice: Boolean) {
        val context: android.content.Context =
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        CloudTtsSettings.save(
            context,
            CloudTtsSettings.load(context).copy(mode = mode, multiVoiceEnabled = multiVoice)
        )
    }

    private fun settingsFor(mode: TtsEngineMode): CloudTtsSettings {
        val context: android.content.Context =
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        return CloudTtsSettings.load(context).copy(mode = mode)
    }

    /**
     * SYSTEM 引擎 + 未标注音色 = **出厂默认态**：多角色不可用，面板只画开关行
     * （置灰）与「不支持」说明，并在该分支给出「标注系统音色」入口。
     *
     * 这是用户第一次打开这一屏看到的画面，也是迁移里最容易把「为什么不可用」
     * 那句说明丢掉的路径。
     */
    @Test
    fun systemEngineUnsupportedBranchRenders() {
        seedMode(TtsEngineMode.SYSTEM, multiVoice = false)
        val bmp = renderSection("multivoice-system-unsupported") {
            MultiVoiceSection(
                settings = settingsFor(TtsEngineMode.SYSTEM),
                onSettingsChange = {},
                books = emptyList(),
                preselectedBook = null
            )
        }
        assertTrue(
            "不支持分支必须画出开关行与说明",
            countNonPaper(bmp, LightLinguaPalette.paper.toArgb()) > 200
        )
        assertTrue(
            "出图应当落盘",
            File("build/ui-render-probe/multivoice-system-unsupported.png").isFile
        )
    }

    /**
     * 云引擎（自建服务端）+ 开关打开：此时会进入角色列表加载。
     *
     * 渲染台里 `reload()` 的角色表与音色库都返回空，所以画面停在「正在加载 / 空列表」
     * 那一态 —— 断言只要求画出区块标题与开关行，不假装覆盖了列表。
     */
    @Test
    fun serverEngineEnabledBranchRenders() {
        seedMode(TtsEngineMode.OPENAI_COMPAT, multiVoice = true)
        val bmp = renderSection("multivoice-server-enabled") {
            MultiVoiceSection(
                settings = settingsFor(TtsEngineMode.OPENAI_COMPAT),
                onSettingsChange = {},
                books = emptyList(),
                preselectedBook = null
            )
        }
        assertTrue(
            "云引擎分支必须画出内容",
            countNonPaper(bmp, LightLinguaPalette.paper.toArgb()) > 200
        )
    }

    /**
     * 深色皮肤基线（`f90a98e` 后的真值）。
     *
     * 断言底色必须是深色皮肤的 paper：修复前 token 恒为日间，这里会失败。
     */
    @Test
    fun darkSkinBranchRenders() {
        seedMode(TtsEngineMode.SYSTEM, multiVoice = false)
        val darkPaper = DarkLinguaPalette.paper.toArgb()
        val bmp = renderSection("multivoice-system-unsupported-dark", dark = true) {
            MultiVoiceSection(
                settings = settingsFor(TtsEngineMode.SYSTEM),
                onSettingsChange = {},
                books = emptyList(),
                preselectedBook = null
            )
        }
        assertTrue(
            "深色皮肤下底色必须是深色纸色，实际 " +
                ComposeRenderHarness.hexColor(bmp.getPixel(4, bmp.height - 4)),
            bmp.getPixel(4, bmp.height - 4) == darkPaper
        )
        assertTrue("深色皮肤下必须画出内容", countNonPaper(bmp, darkPaper) > 200)
    }
}
