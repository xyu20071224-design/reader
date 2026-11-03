package com.linguareader.shared.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [Navigator] 可变外壳：状态迁移 + 监听器（Compose 侧靠它把状态镜像进重组状态）。 */
class NavigatorTest {

    @Test
    fun defaultsToBookshelfRoot() {
        val nav = Navigator()
        assertEquals(Route.Bookshelf, nav.current)
        assertFalse(nav.canGoBack)
        assertNull(nav.pop(), "根路由上 pop 返回 null，调用方据此把返回键交给系统")
    }

    @Test
    fun listenersSeeEveryChangeAndNothingElse() {
        val nav = Navigator()
        val seen = mutableListOf<NavStack>()
        nav.addListener { seen += it }

        assertTrue(nav.push(Route.Vocabulary))
        assertFalse(nav.push(Route.Vocabulary), "重复压当前页不产生变化，也就不该通知")
        assertEquals(1, seen.size)
        assertEquals(Route.Vocabulary, seen.single().current)

        assertEquals(Route.Vocabulary, nav.pop())
        assertEquals(2, seen.size)
        assertEquals(Route.Bookshelf, seen.last().current)
    }

    @Test
    fun removedListenerStopsReceiving() {
        val nav = Navigator()
        var calls = 0
        val listener: (NavStack) -> Unit = { calls++ }
        nav.addListener(listener)
        nav.push(Route.Vocabulary)
        nav.removeListener(listener)
        nav.pop()
        assertEquals(1, calls)
    }

    @Test
    fun resetReplacesWholeStack() {
        val nav = Navigator()
        nav.push(Route.Vocabulary)
        nav.reset(Route.Reader("book-1"))
        assertEquals(Route.Reader("book-1"), nav.current)
        assertFalse(nav.canGoBack, "reset 之后是单层栈")
    }

    @Test
    fun popToUnknownRouteLeavesStateUntouched() {
        val nav = Navigator()
        nav.push(Route.Vocabulary)
        val before = nav.stack
        assertFalse(nav.popTo(Route.SyncDetail))
        assertEquals(before, nav.stack)
    }

    @Test
    fun setStackAppliesPrecomputedStack() {
        val nav = Navigator()
        val target = NavStack.of(Route.Bookshelf).push(Route.Reader("x"))
        nav.setStack(target)
        assertEquals(target, nav.stack)
        assertEquals(Route.Reader("x"), nav.current)
    }
}
