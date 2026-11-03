package com.linguareader.shared.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [OverlayStack]：连续 push/pop、重复 push 同一类型、空栈返回键。
 *
 * 这三条正是现状最缺的：8 个 `showXxx` 布尔（`BookshelfScreen.kt:236-248`）彼此
 * 不知道对方存在，所以能同时开两个 sheet；返回键只处理了生词本一个。
 */
class OverlayStackTest {

    @Test
    fun emptyStackHasNoTopAndPassesBackThrough() {
        val stack = OverlayStack.EMPTY
        assertTrue(stack.isEmpty)
        assertNull(stack.top)
        assertEquals(0, stack.depth)
        assertNull(stack.pop(), "空栈 pop 必须是 null，不能返回空对象假装成功")

        val back = stack.handleBack()
        assertIs<OverlayBackResult.PassThrough>(back, "空栈时返回键必须往下传，否则书架上返回键会彻底失灵")
    }

    @Test
    fun consecutivePushAndPopKeepsOrder() {
        var stack = OverlayStack.EMPTY.push(Overlay.Storage)
        stack = stack.push(Overlay.Packs)
        assertEquals(Overlay.Packs, stack.top)
        assertEquals(listOf(OverlayKind.STORAGE, OverlayKind.PACKS), stack.kinds)

        val firstPop = stack.pop()
        assertEquals(Overlay.Packs, firstPop?.overlay)
        assertEquals(Overlay.Storage, firstPop?.stack?.top)

        val secondPop = firstPop?.stack?.pop()
        assertEquals(Overlay.Storage, secondPop?.overlay)
        assertTrue(secondPop?.stack?.isEmpty == true)
    }

    @Test
    fun pushingSameKindAgainMovesItToTopInsteadOfStackingTwice() {
        val stack = OverlayStack.EMPTY
            .push(Overlay.ListeningSettings(bookId = "b1"))
            .push(Overlay.Storage)
            .push(Overlay.ListeningSettings(bookId = "b2"))

        assertEquals(2, stack.depth, "同一种弹层全栈只允许一份")
        assertEquals(Overlay.ListeningSettings(bookId = "b2"), stack.top, "新的一份在栈顶")
        assertEquals(listOf(OverlayKind.STORAGE, OverlayKind.LISTENING_SETTINGS), stack.kinds)
    }

    @Test
    fun pushingTheExactTopOverlayIsANoOp() {
        val stack = OverlayStack.EMPTY.push(Overlay.Update)
        assertSame(stack, stack.push(Overlay.Update), "重复点击同一个入口不该产生状态变化")
    }

    @Test
    fun dismissRemovesKindFromAnyDepth() {
        val stack = OverlayStack.EMPTY.push(Overlay.Storage).push(Overlay.Packs)
        val withoutStorage = stack.dismiss(OverlayKind.STORAGE)
        assertEquals(listOf(OverlayKind.PACKS), withoutStorage.kinds)
        assertSame(withoutStorage, withoutStorage.dismiss(OverlayKind.STORAGE), "没开着时不产生变化")
    }

    @Test
    fun dismissAllClearsEverything() {
        val stack = OverlayStack.of(Overlay.AiDrawer, Overlay.Storage, Overlay.Packs)
        assertTrue(stack.dismissAll().isEmpty)
        assertSame(OverlayStack.EMPTY.isEmpty, stack.dismissAll().isEmpty)
    }

    @Test
    fun replaceSwapsTopKeepingDepth() {
        val stack = OverlayStack.of(Overlay.AiDrawer).replace(Overlay.AiProvider)
        assertEquals(1, stack.depth, "AI 中心 → 服务商配置是原地换页，关掉要回 AI 中心")
        assertEquals(Overlay.AiProvider, stack.top)

        val fromEmpty = OverlayStack.EMPTY.replace(Overlay.Storage)
        assertEquals(Overlay.Storage, fromEmpty.top, "空栈 replace 等价于 push")
    }

    @Test
    fun backDismissesOnlyTopOverlay() {
        val stack = OverlayStack.of(Overlay.Storage, Overlay.Packs)
        val back = stack.handleBack()
        assertIs<OverlayBackResult.Dismiss>(back)
        assertEquals(Overlay.Packs, back.dismissed)
        assertEquals(Overlay.Storage, back.stack.top, "关掉上层后下层原样露出")

        val next = back.stack.handleBack()
        assertIs<OverlayBackResult.Dismiss>(next)
        assertEquals(Overlay.Storage, next.dismissed)
        assertIs<OverlayBackResult.PassThrough>(next.stack.handleBack(), "最后一层关完就往下传")
    }

    @Test
    fun isOpenReportsAnyDepth() {
        val stack = OverlayStack.of(Overlay.Storage, Overlay.Sync)
        assertTrue(stack.isOpen(OverlayKind.STORAGE))
        assertTrue(stack.isOpen(OverlayKind.SYNC))
        assertFalse(stack.isOpen(OverlayKind.UPDATE))
    }

    @Test
    fun everyOverlayDeclaresItsOwnKind() {
        val all = listOf(
            Overlay.AiDrawer, Overlay.Packs, Overlay.Storage, Overlay.Sync,
            Overlay.ShelfAppearance, Overlay.Update,
            Overlay.ListeningSettings(), Overlay.MultiVoice("b"), Overlay.Glossary("b"),
            Overlay.AiProvider, Overlay.LaunchNotice(false, 0, emptyList())
        )
        val kinds = all.map { it.kind }
        assertEquals(all.size, kinds.toSet().size, "每一种弹层必须有唯一 kind，否则去重会误伤")
    }
}
