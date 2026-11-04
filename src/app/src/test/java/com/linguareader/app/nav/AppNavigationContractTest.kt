package com.linguareader.app.nav

import com.linguareader.shared.ui.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **导航接线等价性**（P13）。
 *
 * ## 守的是什么
 *
 * 2026-11-04 发现：P2 建好的整套导航层（`Route` / `NavStack` / `OverlayStack` / `AppNavHost`，
 * 1000+ 行、20+ 单测）**在生产代码里零调用** —— `MainActivity` 用的是 `if (currentBook == null)`
 * 一个隐式布尔。这跟 P9 的图片换肤是同一类缺陷：能力建好了但没接线，用户与「是否成立」都无从验证。
 *
 * 接线时的**最大风险是改行为**：屏幕切换的时机、返回键的优先级、换书时栈怎么变。
 * 所以这里先把那些约定钉死，再动人。
 *
 * ## 钉死的三条约定
 *
 * 1. **书架 ↔ 阅读页**：打开书 = 压栈；关闭书 = 退栈；换书 = 原地换页（不叠栈）。
 * 2. **根页不可退**：书架是根，在书架按返回键应当交给系统（不得被壳吞掉）。
 * 3. **返回键优先级**：弹层 → 导航栈 → 系统，三档顺序不能乱。
 */
class AppNavigationContractTest {

    /** 打开书：书架 → 阅读页是**压栈**，返回能回到书架。 */
    @Test
    fun openingBookPushesReaderOntoStack() {
        val nav = AppNavState(ComposeNavigator.of())
        assertEquals(Route.Bookshelf, nav.navigator.current)

        nav.navigator.navigate(Route.Reader("book-1"))
        assertEquals(Route.Reader("book-1"), nav.navigator.current)
        assertTrue("打开书后应当能退回书架", nav.canGoBack)

        nav.onBack()
        assertEquals("返回后应当回到书架", Route.Bookshelf, nav.navigator.current)
    }

    /** 换书：Reader→Reader 是**原地换页**，不叠栈（否则连按两本书会积一层）。 */
    @Test
    fun switchingBookReplacesInsteadOfPushing() {
        val nav = AppNavState(ComposeNavigator.of())
        nav.navigator.navigate(Route.Reader("book-1"))
        nav.navigator.replaceTop(Route.Reader("book-2"))

        assertEquals(Route.Reader("book-2"), nav.navigator.current)
        assertEquals("换书不该加深栈", 2, nav.navigator.stack.depth)
    }

    /** 关闭书：reset 回书架根页，此时不能退（返回键交给系统）。 */
    @Test
    fun closingBookResetsToRootAndCannotGoBack() {
        val nav = AppNavState(ComposeNavigator.of())
        nav.navigator.navigate(Route.Reader("book-1"))
        nav.navigator.reset(Route.Bookshelf)

        assertEquals(Route.Bookshelf, nav.navigator.current)
        assertTrue("书架是根页，返回键应交给系统", !nav.canGoBack)
    }

    /** 返回键优先级：有弹层时先关弹层，弹层空了才退导航栈。 */
    @Test
    fun backPrefersOverlayOverRoute() {
        val nav = AppNavState(ComposeNavigator.of())
        nav.navigator.navigate(Route.Reader("book-1"))
        nav.pushOverlay(com.linguareader.shared.ui.state.Overlay.Glossary("book-1"))

        val first = nav.onBack()
        assertTrue("第一次返回应当关弹层", first is BackOutcome.DismissedOverlay)
        assertEquals("关弹层不该动导航栈", Route.Reader("book-1"), nav.navigator.current)

        val second = nav.onBack()
        assertTrue("第二次返回才退导航栈", second is BackOutcome.PoppedRoute)
    }
}