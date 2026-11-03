package com.linguareader.shared.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [NavStack] 纯迁移：压/弹/替换/回退到某页、栈深上限、根路由不可弹出。
 *
 * 这些断言的价值在于：`MainActivity.kt:155` 现在的二态切换没有任何单测能覆盖，
 * 改成栈之后「返回键该退到哪一页」第一次变成可断言的。
 */
class NavStackTest {

    private val root = NavStack.of(Route.Bookshelf)

    @Test
    fun startsWithRootAndCannotGoBack() {
        assertEquals(Route.Bookshelf, root.current)
        assertEquals(Route.Bookshelf, root.root)
        assertEquals(1, root.depth)
        assertFalse(root.canGoBack)
        assertNull(root.pop(), "根路由不能被弹出")
    }

    @Test
    fun emptyStackIsRejectedAtConstruction() {
        var thrown: Throwable? = null
        try {
            NavStack(emptyList())
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertTrue(thrown is IllegalArgumentException, "空栈应当直接被拒绝，避免 UI 层到处判空")
    }

    @Test
    fun pushThenPopRestoresPreviousRoute() {
        val reading = root.push(Route.Reader("book-1"))
        assertEquals(Route.Reader("book-1"), reading.current)
        assertTrue(reading.canGoBack)

        val popped = reading.pop()
        assertEquals(Route.Reader("book-1"), popped?.route)
        assertEquals(root, popped?.stack)
    }

    @Test
    fun pushingCurrentRouteIsANoOp() {
        val once = root.push(Route.Vocabulary)
        assertSame(once, once.push(Route.Vocabulary), "重复压当前页必须无变化（返回同一实例）")
    }

    @Test
    fun pushStopsAtMaxDepthInsteadOfGrowingForever() {
        var stack = root
        repeat(3) { i -> stack = stack.push(Route.Reader("b$i"), maxDepth = 3) }
        assertEquals(3, stack.depth)

        val again = stack.push(Route.Reader("overflow"), maxDepth = 3)
        assertSame(stack, again, "触顶后必须无变化，杜绝回调环把栈顶爆")
    }

    @Test
    fun replaceKeepsDepth() {
        val stack = root.push(Route.Vocabulary)
        val replaced = stack.replace(Route.SyncDetail)
        assertEquals(2, replaced.depth)
        assertEquals(Route.SyncDetail, replaced.current)
        assertEquals(Route.Bookshelf, replaced.root)
    }

    @Test
    fun popToRootCollapsesEverythingAbove() {
        val stack = root
            .push(Route.Vocabulary)
            .push(Route.Reader("book-1"))
            .push(Route.SyncDetail)
        val collapsed = stack.popToRoot()
        assertEquals(root, collapsed)
        // 已经在根上 → 无操作 → 返回同一实例（幂等，不是每次新建对象）。
        assertSame(collapsed, collapsed.popToRoot())
    }

    @Test
    fun popToUnknownRouteReturnsNullInsteadOfFallingBackToRoot() {
        val stack = root.push(Route.Reader("book-1"))
        assertNull(stack.popTo(Route.SyncDetail), "找不到目标时不许静默把用户丢回书架")
        assertNull(stack.popTo(Route.Vocabulary), "不在栈里的路由同样不许兜底成根")
    }

    @Test
    fun popToExistingRouteTrimsAbove() {
        val stack = root
            .push(Route.Vocabulary)
            .push(Route.Reader("book-1"))
        val trimmed = stack.popTo(Route.Vocabulary)
        assertEquals(listOf<Route>(Route.Bookshelf, Route.Vocabulary), trimmed?.entries)
    }

    @Test
    fun readerRouteCarriesBookIdAsValue() {
        assertEquals(Route.Reader("a"), Route.Reader("a"))
        assertFalse(Route.Reader("a") == Route.Reader("b"))
        assertTrue(root.push(Route.Reader("a")).contains(Route.Reader("a")))
    }
}
