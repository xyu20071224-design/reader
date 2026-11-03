package com.linguareader.app.uirender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import com.linguareader.app.ListeningSettingsBody
import com.linguareader.app.Paper
import com.linguareader.app.colorSchemeFor
import com.linguareader.app.skin.provideLinguaTheme
import com.linguareader.shared.ui.skin.SkinBuiltIns
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 听书设置弹层（[ListeningSettingsBody]）的 **before / after** 视觉回归夹具。
 *
 * ## 为什么这一屏要渲染「真内容体」而不是手工搭的骨架
 *
 * 本屏与前面几屏的弹层不一样：`ListeningSettingsBody` 自己就持有真实数据
 * （`CloudTtsSettings.load` / `SystemTtsVoices.load` / `TtsAudioCache.totalBytes`），
 * 而且内容随 `settings.mode` 走三条完全不同的分支。手工搭骨架只能证明「骨架长这样」，
 * 证明不了这一屏长这样。所以夹具直接渲染真内容体，再按对应的 mode 预置存储，
 * 把三条分支各出一张图。
 *
 * ## 留白归属（迁移的关键差异，夹具必须如实复刻）
 *
 * 迁移前：内容体自己写死 `padding(horizontal = 24.dp).padding(bottom = 30.dp)`。
 * 迁移后：留白**上移到外壳**（[com.linguareader.app.components.LinguaSheet] 的
 * spacing.l / spacing.xl），内容体只剩 `fillMaxWidth().verticalScroll()` ——
 * 因为同一个内容体还被 AI 抽屉复用，两边各写 24dp 会叠成 48dp。
 * 所以夹具在这里补上外壳那一层留白，与真机一致；**不补的话画面会顶到左右边缘**，
 * 那是夹具没复刻外壳，不是实现塌了。
 *
 * ## 深色皮肤（2026-11-04 补）
 *
 * `f90a98e` 之前 `LocalDesignTokens` 在生产代码里从未被下发，所有已迁屏在深色外壳下
 * 实际取到的是**日间 token** —— 也就是说此前出的深色图都是错的基线。
 * 修复后本夹具的深色那一组才是真值：`provideLinguaTheme(SkinBuiltIns.dark)`
 * 同时下发深色 token 与深色调色板，与 `MainActivity` 现在的接法一致。
 *
 * ## 画布尺寸
 *
 * 一律 [ComposeRenderHarness.renderDp]（xxhdpi 下 411×891dp = 1233×2673px）。
 * 直接传像素会把可用宽度算成 180dp，文字被裁成半截 —— 坑见 `SheetMigrationRenderTest`。
 *
 * 出图位置：app/build/ui-render-probe/listening-settings-*.png
 *
 * **不是真机替代品**：系统 TTS 引擎探测（异步回调，渲染台里恒为空）、试听播放、
 * 输入法、SAF 选音频、音频缓存清理的真实文件读写都测不到。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ListeningSettingsRenderTest {

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
     * 外壳夹具：纸色铺满 + 左右 24dp / 底部 30dp 留白。
     *
     * 留白数值写死在这里而不是从 token 取，是**故意的**：夹具要能在迁移前后两版上
     * 都出图、都好对比。这组数字正是 LinguaSheet 现在的 spacing.l(24) / spacing.xl(32)
     * 量级，也与迁移前内容体自带的那两个值一致。
     */
    private fun renderBody(
        name: String,
        dark: Boolean = false,
        content: @Composable () -> Unit
    ) = ComposeRenderHarness.renderDp(name, 411, 891) {
        // 皮肤与调色板必须同源下发（= MainActivity 现在的接法）。
        provideLinguaTheme(if (dark) SkinBuiltIns.dark else SkinBuiltIns.light) {
            MaterialTheme(
                colorScheme = colorSchemeFor(
                    if (dark) DarkLinguaPalette else LightLinguaPalette
                ),
                shapes = AppShapes
            ) {
                Box(Modifier.fillMaxSize().background(Paper)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 30.dp)
                    ) { content() }
                }
            }
        }
    }

    /** 把引擎模式写进 SharedPreferences，让内容体走对应分支。 */
    private fun seedMode(mode: com.linguareader.app.tts.TtsEngineMode) {
        val context: android.content.Context =
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        com.linguareader.app.tts.CloudTtsSettings.save(
            context,
            com.linguareader.app.tts.CloudTtsSettings.load(context).copy(mode = mode)
        )
    }

    /**
     * SYSTEM 分支（默认分支）：引擎三选一 + 系统音色区 + 多角色开关 + 音频缓存 + 按钮行。
     *
     * 渲染台里 `SystemTtsVoices.load` 的异步回调不会返回，所以这里只会画出「加载系统音色」
     * 那一态 —— **这是夹具的已知盲区**，真机上点一下按钮才会出现两个音色下拉。
     */
    @Test
    fun systemEngineBranchRenders() {
        seedMode(com.linguareader.app.tts.TtsEngineMode.SYSTEM)
        val bmp = renderBody("listening-settings-system") {
            ListeningSettingsBody(onDismiss = {}, onSaved = {})
        }
        assertTrue(
            "SYSTEM 分支必须画出内容",
            countNonPaper(bmp, LightLinguaPalette.paper.toArgb()) > 500
        )
        assertTrue("出图应当落盘", File("build/ui-render-probe/listening-settings-system.png").isFile)
    }

    /** 自建服务端分支：七个输入框（地址/模型/令牌/四个音色）+ 测试连接。 */
    @Test
    fun serverEngineBranchRenders() {
        seedMode(com.linguareader.app.tts.TtsEngineMode.OPENAI_COMPAT)
        val bmp = renderBody("listening-settings-server") {
            ListeningSettingsBody(onDismiss = {}, onSaved = {})
        }
        assertTrue(
            "自建服务端分支必须画出输入框",
            countNonPaper(bmp, LightLinguaPalette.paper.toArgb()) > 500
        )
    }

    /** MiMo 分支：Key + 两组预置音色 + 风格说明 + 测试连接。 */
    @Test
    fun mimoEngineBranchRenders() {
        seedMode(com.linguareader.app.tts.TtsEngineMode.MIMO)
        val bmp = renderBody("listening-settings-mimo") {
            ListeningSettingsBody(onDismiss = {}, onSaved = {})
        }
        assertTrue(
            "MiMo 分支必须画出内容",
            countNonPaper(bmp, LightLinguaPalette.paper.toArgb()) > 500
        )
    }

    /**
     * 「取消」按钮只在有宿主能关弹层时才画（`onDismiss != null`）。
     *
     * 这个分支是真的有语义：AI 抽屉里传的是 null（抽屉自己有关闭方式），
     * 弹层里传的是 onDismiss。迁移时如果把按钮换成 PrimaryButton 之类的原语而漏掉
     * 这个条件，抽屉里会多出一个点了没反应的「取消」。
     */
    @Test
    fun cancelButtonIsHiddenWhenThereIsNoDismissHandler() {
        seedMode(com.linguareader.app.tts.TtsEngineMode.SYSTEM)
        val paper = LightLinguaPalette.paper.toArgb()
        val withDismiss = renderBody("listening-settings-system-buttons") {
            ListeningSettingsBody(onDismiss = {}, onSaved = {})
        }
        val withoutDismiss = renderBody("listening-settings-system-no-dismiss") {
            ListeningSettingsBody(onDismiss = null, onSaved = {})
        }
        assertTrue(
            "有 onDismiss 时必须比没有时多画出「取消」按钮",
            countNonPaper(withDismiss, paper) > countNonPaper(withoutDismiss, paper)
        )
    }

    /**
     * **深色皮肤**（`f90a98e` 落地后的真值基线）。
     *
     * 断言只做「能画出内容」+「确实是深色底」两件事：
     *  - 底色取样必须是深色皮肤的 paper（不是日间米白）—— 这条正是修复前会失败的地方，
     *    当时 token 恒为日间，深色外壳下弹层会画成米白；
     *  - 内容像素数要够，避免「整块深色上面什么都没有」也算通过。
     */
    @Test
    fun darkSkinBranchRenders() {
        seedMode(com.linguareader.app.tts.TtsEngineMode.SYSTEM)
        val darkPaper = DarkLinguaPalette.paper.toArgb()
        val bmp = renderBody("listening-settings-system-dark", dark = true) {
            ListeningSettingsBody(onDismiss = {}, onSaved = {})
        }
        assertTrue(
            "深色皮肤下弹层底色必须是深色纸色，实际 " +
                ComposeRenderHarness.hexColor(bmp.getPixel(4, bmp.height - 4)),
            bmp.getPixel(4, bmp.height - 4) == darkPaper
        )
        assertTrue("深色皮肤下必须画出内容", countNonPaper(bmp, darkPaper) > 500)
        assertTrue(
            "出图应当落盘",
            File("build/ui-render-probe/listening-settings-system-dark.png").isFile
        )
    }
}
