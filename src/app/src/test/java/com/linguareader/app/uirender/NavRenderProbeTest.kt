package com.linguareader.app.uirender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.linguareader.app.Accent
import com.linguareader.app.AppShapes
import com.linguareader.app.CardShape
import com.linguareader.app.Gold
import com.linguareader.app.Ink
import com.linguareader.app.LightLinguaPalette
import com.linguareader.app.LocalLinguaPalette
import com.linguareader.app.Paper
import com.linguareader.app.colorSchemeFor
import com.linguareader.app.nav.NavHostLite
import com.linguareader.app.nav.OverlayHost
import com.linguareader.app.nav.OverlayRegistry
import com.linguareader.app.nav.RouteKey
import com.linguareader.app.nav.ScreenRegistry
import com.linguareader.app.nav.overlayRegistry
import com.linguareader.app.nav.rememberNavigator
import com.linguareader.app.nav.screenRegistry
import com.linguareader.shared.ui.nav.Route
import com.linguareader.shared.ui.state.Overlay
import com.linguareader.shared.ui.state.OverlayKind
import com.linguareader.shared.ui.state.OverlayStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 导航 / 弹层的渲染验证：把 [NavHostLite] 与 [OverlayHost] 真的画出来并断言像素。
 *
 * 本机没有硬件虚拟化（方案 §7），真机也常不在线，所以 P2 的「能渲染」只能靠
 * 这条纯 JVM 通道证明：Robolectric NATIVE 图形 + 真 Activity 挂 Compose + PNG 落盘。
 * **它不是真机的替代品**——手势、返回键的真实分发、转场动画都测不到，那些仍需真机。
 *
 * 出图位置：app/build/ui-render-probe/nav-*.png
 *
 * **这些图是管线验证夹具，不是书架的真实外观。** P2 只验证「导航模型 + 弹层栈能渲染、
 * 且只渲染栈顶」，屏幕本身要等 P3 逐屏迁移。所以 [FakeBookshelf] 只是一块标题 +
 * 一个强调色方块——它的作用是「证明这一屏真的画了、颜色真的落到像素上」，
 * 任何拿这几张图当设计稿或视觉基线的用法都是误解。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class NavRenderProbeTest {

    @Composable
    private fun NavScene(
        registry: ScreenRegistry,
        initial: Route,
        overlays: OverlayStack = OverlayStack.EMPTY,
        overlayRegistry: OverlayRegistry = OverlayRegistry.EMPTY
    ) {
        CompositionLocalProvider(LocalLinguaPalette provides LightLinguaPalette) {
            MaterialTheme(colorScheme = colorSchemeFor(LightLinguaPalette), shapes = AppShapes) {
                Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
                    val nav = rememberNavigator(initial)
                    NavHostLite(registry = registry, host = nav)
                    OverlayHost(stack = overlays, registry = overlayRegistry)
                }
            }
        }
    }

    /**
     * 管线夹具（**不是真实书架**）：只画标题 + 一块强调色，用来断言「这一屏真的渲染了」。
     * 真实书架外观在 P3 迁移后才由 BookshelfScreen 提供。
     */
    @Composable
    private fun FakeBookshelf(route: Route) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("书架", color = Ink, style = MaterialTheme.typography.titleLarge)
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Accent, CardShape)
            )
        }
    }

    private val bookshelfOnly = screenRegistry {
        screen(RouteKey.BOOKSHELF) { route -> FakeBookshelf(route) }
    }

    @Composable
    private fun GoldOverlay() {
        Box(Modifier.fillMaxSize().background(Gold))
    }

    @Composable
    private fun AccentOverlay() {
        Box(Modifier.fillMaxSize().background(Accent))
    }

    /** 数采样点里有多少个像素等于给定颜色（与 ComposeRenderHarness 同口径）。 */
    private fun countPixels(bmp: android.graphics.Bitmap, color: Int): Int {
        var n = 0
        for (x in 0 until bmp.width step 3) {
            for (y in 0 until bmp.height step 3) {
                if (bmp.getPixel(x, y) == color) n++
            }
        }
        return n
    }

    @Test
    fun bookshelfRouteRendersTheRegisteredScreen() {
        val bmp = ComposeRenderHarness.render("nav-bookshelf", 540, 960) {
            NavScene(bookshelfOnly, Route.Bookshelf)
        }
        val accentPixels = countPixels(bmp, LightLinguaPalette.accent.toArgb())
        assertTrue(
            "书架屏幕里的强调色卡片必须真的落到像素上（实际 $accentPixels 个采样点）",
            accentPixels > 100
        )
    }

    @Test
    fun unmigratedScreenRendersDiagnosticPlaceholderOnPaper() {
        // 只注册书架：切到生词本应看到诊断占位（纸色铺满），而不是崩溃或纯黑。
        val bmp = ComposeRenderHarness.render("nav-missing-screen", 540, 960) {
            NavScene(bookshelfOnly, Route.Vocabulary)
        }
        assertEquals(
            "占位页必须铺满主题纸色",
            LightLinguaPalette.paper.toArgb(),
            bmp.getPixel(4, 4)
        )
    }

    @Test
    fun overlayHostRendersOnlyTheTopOverlay() {
        // 栈里有存储(金) + 资源包(强调色)两层，只应渲染栈顶的资源包那一层。
        val stack = OverlayStack.of(Overlay.Storage, Overlay.Packs)
        val registry = overlayRegistry {
            overlay(OverlayKind.STORAGE) { GoldOverlay() }
            overlay(OverlayKind.PACKS) { AccentOverlay() }
        }
        val bmp = ComposeRenderHarness.render("nav-overlay-stack", 540, 960) {
            NavScene(bookshelfOnly, Route.Bookshelf, overlays = stack, overlayRegistry = registry)
        }
        val accentPixels = countPixels(bmp, LightLinguaPalette.accent.toArgb())
        val goldPixels = countPixels(bmp, LightLinguaPalette.gold.toArgb())
        assertTrue("栈顶（资源包）必须渲染：$accentPixels 个采样点", accentPixels > 500)
        assertEquals("下层（存储）不能被渲染：OverlayHost 只画栈顶", 0, goldPixels)
    }
}
