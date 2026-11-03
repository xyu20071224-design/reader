package com.linguareader.app.nav

import com.linguareader.shared.ui.nav.Navigator
import com.linguareader.shared.ui.nav.Route
import com.linguareader.shared.ui.state.Overlay
import com.linguareader.shared.ui.state.OverlayKind
import com.linguareader.shared.ui.state.OverlayStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回键优先级链：弹层栈顶 → 导航栈上一页 → 交给系统。
 *
 * 这是现状最缺的一条：全项目只有 3 处 BackHandler、书架的 6 个 sheet 与 7 个对话框
 * 完全没有返回处理。把这条链写成纯逻辑后，优先级第一次可以被断言。
 *
 * 注意 org.junit 的断言是 **message-first**（与 kotlin.test 的 expected-first 不同），
 * 本仓库两个库并存，这里统一用 org.junit 的签名。
 */
class AppNavStateTest {

    private fun navState(
        root: Route = Route.Bookshelf,
        vararg overlays: Overlay
    ): AppNavState {
        // 用 of() 而不是直接 new：镜像状态必须挂上监听器才会跟着动（见 ComposeNavigator.of 的注释）。
        val composeNavigator = ComposeNavigator.of(Navigator(root))
        return AppNavState(composeNavigator, OverlayStack.of(*overlays))
    }

    @Test
    fun emptyEverythingMeansBackGoesToTheSystem() {
        val nav = navState()
        assertFalse("根页没有弹层时不该拦截返回键", nav.canGoBack)
        assertEquals(BackOutcome.Unhandled, nav.onBack())
    }

    @Test
    fun overlayClosesBeforeAnyRoutePops() {
        val nav = navState(Route.Bookshelf, Overlay.Storage)
        nav.navigator.navigate(Route.Vocabulary)

        val first = nav.onBack()
        assertTrue("第一下返回必须只关弹层，不能退页面", first is BackOutcome.DismissedOverlay)
        assertEquals(Overlay.Storage, (first as BackOutcome.DismissedOverlay).overlay)
        assertEquals("页面必须还在生词本", Route.Vocabulary, nav.navigator.current)

        val second = nav.onBack()
        assertTrue("弹层关完才退页面", second is BackOutcome.PoppedRoute)
        assertEquals(Route.Vocabulary, (second as BackOutcome.PoppedRoute).route)
        assertEquals(Route.Bookshelf, nav.navigator.current)

        assertEquals(BackOutcome.Unhandled, nav.onBack())
    }

    @Test
    fun backClosesOnlyTheTopOverlayAndRevealsTheOneBelow() {
        val nav = navState(Route.Bookshelf, Overlay.Storage, Overlay.Packs)
        val outcome = nav.onBack()
        assertTrue(outcome is BackOutcome.DismissedOverlay)
        assertEquals(Overlay.Packs, (outcome as BackOutcome.DismissedOverlay).overlay)
        assertEquals("下层（存储）必须原样露出", Overlay.Storage, nav.top)
        assertTrue("还有一层弹层，返回键仍然可用", nav.canGoBack)
    }

    @Test
    fun pushAndReplaceAndDismissAll() {
        val nav = navState()
        nav.pushOverlay(Overlay.AiDrawer)
        assertEquals(Overlay.AiDrawer, nav.top)
        nav.replaceOverlay(Overlay.AiProvider)
        assertEquals("原地换页不该加深栈", Overlay.AiProvider, nav.top)
        assertEquals(1, nav.overlays.depth)
        nav.dismissAllOverlays()
        assertNull(nav.top)
    }

    @Test
    fun dismissSpecificKindLeavesOthersOpen() {
        val nav = navState(Route.Bookshelf, Overlay.Storage, Overlay.Sync)
        nav.dismissOverlay(OverlayKind.STORAGE)
        assertFalse(nav.overlays.isOpen(OverlayKind.STORAGE))
        assertEquals(Overlay.Sync, nav.top)
    }

    @Test
    fun canGoBackTracksBothStacks() {
        val nav = navState()
        assertFalse(nav.canGoBack)
        nav.pushOverlay(Overlay.Update)
        assertTrue(nav.canGoBack)
        nav.dismissTop()
        assertFalse(nav.canGoBack)
        nav.navigator.navigate(Route.Reader("b1"))
        assertTrue(nav.canGoBack)
    }
}
