package com.linguareader.app.uirender

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.linguareader.app.ProviderSettingsBody
import com.linguareader.app.ai.AiProtocol
import com.linguareader.app.ai.AiProviderProfile
import com.linguareader.app.skin.provideLinguaTheme
import com.linguareader.shared.ui.skin.SkinBuiltIns
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AI 服务商设置的**视觉基线**（P3 遗漏区补迁：AiProviderSettings.kt）。
 *
 * ## 只画 ProviderSettingsBody，不画编辑器
 *
 * 同一文件里的 [com.linguareader.app.ProviderEditorDialog] 是 AlertDialog —— 它走
 * Dialog/Window 那条路，Robolectric 里**画不进位图**（与 ModalBottomSheet 同一个坑）。
 * 因此这里只覆盖列表主体：服务商行、单选态、官方预设入口、「添加服务商」。
 * 编辑器的视觉效果只能真机看 —— 本文件不假装覆盖了它。
 *
 * ## 夹具为什么套 provideLinguaTheme 而不是只给 LocalDesignTokens
 *
 * 出「迁移前」图时这个文件用的是旧调色板（`Ink`/`InkSoft` → `LocalLinguaPalette`），
 * 迁移后才改成 `LocalDesignTokens`。`provideLinguaTheme` **同时下发两者**，
 * 于是同一份夹具对新旧两版实现都能编译、都能出图 —— 这正是 before/after 可并排的前提。
 *
 * 底色用 `SkinBuiltIns.light.colors.paper` 而不是 token/调色板 API：那个值是常量，
 * 新旧两版都拿得到，夹具因此不随实现变化。
 *
 * ## 覆盖不到什么
 *
 * 滚动、点击、RadioButton 的真实交互反馈、编辑器弹层、协议切换后的 FlowRow 换行
 * （那只在编辑器里）—— 全部仍须真机。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AiProviderSettingsRenderTest {

    private fun profile(
        id: String,
        name: String,
        baseUrl: String,
        model: String,
        protocol: String = AiProtocol.OPENAI_COMPAT
    ) = AiProviderProfile(
        id = id,
        name = name,
        baseUrl = baseUrl,
        apiKey = "sk-test",
        protocol = protocol,
        model = model
    )

    /** 渲染 + 基线比对。首次跑会建基线；之后差异超阈值即红。 */
    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val spec = if (dark) SkinBuiltIns.dark else SkinBuiltIns.light
        val bmp = ComposeRenderHarness.renderDp(name, 411, 891) {
            provideLinguaTheme(spec) {
                // 真实 App 的根就是 Surface(color = Paper)；缺了它深色下的浅色文字
                // 会浮在渲染台的白画布上（DesignSystemVisualRegressionTest 记过这个坑）。
                Surface(modifier = Modifier.fillMaxSize(), color = Color(spec.colors.paper)) {
                    Column(Modifier.padding(ProviderFixturePadding)) { content() }
                }
            }
        }
        val result = VisualBaseline.compare(name, bmp)
        val summary = VisualBaseline.describe(name, result)
        println(summary)
        assertTrue(summary, result.passed)
    }

    @Test
    fun providerListEmptyLight() = snapshot("provider-settings-empty-light", dark = false) {
        ProviderSettingsBody(
            providers = emptyList(),
            activeId = "",
            masterEnabled = true,
            onSelectActive = {},
            onEdit = {},
            onAdd = {},
            onAddPreset = {}
        )
    }

    @Test
    fun providerListPopulatedLight() = snapshot("provider-settings-list-light", dark = false) {
        // DeepSeek 用官方端点 → 「官方预设」入口不再出现（已被 consumed）；
        // 第二行用自定义端点，保证「编辑」按钮与多行排布都被画到。
        ProviderSettingsBody(
            providers = listOf(
                profile("p1", "DeepSeek 官方", "https://api.deepseek.com", "deepseek-v4-flash"),
                profile("p2", "自建网关", "http://192.168.1.9:8080", "qwen3-32b")
            ),
            activeId = "p1",
            masterEnabled = true,
            onSelectActive = {},
            onEdit = {},
            onAdd = {},
            onAddPreset = {}
        )
    }

    @Test
    fun providerListDisabledDark() = snapshot("provider-settings-list-dark", dark = true) {
        // 总开关关闭：整块必须仍可读（这是隐私边界上最常被看到的状态）。
        ProviderSettingsBody(
            providers = listOf(
                profile("p1", "DeepSeek 官方", "https://api.deepseek.com", "deepseek-v4-flash")
            ),
            activeId = "p1",
            masterEnabled = false,
            onSelectActive = {},
            onEdit = {},
            onAdd = {},
            onAddPreset = {}
        )
    }

    private companion object {
        /** 夹具自身的内边距：与 AiDrawerSheet 里调用本组件时的水平留白一致。 */
        val ProviderFixturePadding = 20.dp
    }
}
