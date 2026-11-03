package com.linguareader.app.nav

import androidx.compose.runtime.Composable
import com.linguareader.shared.ui.nav.Route
import com.linguareader.shared.ui.state.OverlayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * :app 侧的导航适配：路由键一一对应 + 屏幕/弹层注册表完整性。
 *
 * 这里不渲染（渲染在 NavRenderProbeTest），只测「纯逻辑 + 注册表」这一层：
 * 它们跑得很快，改坏时能立刻指出是哪一条对应关系断了。
 */
class NavHostLiteTest {

    @Test
    fun everyRouteMapsToAUniqueScreenKey() {
        val routes = listOf(
            Route.Bookshelf,
            Route.Vocabulary,
            Route.SyncDetail,
            Route.Reader("book-1")
        )
        val keys = routes.map { it.key }
        assertEquals("同一屏幕键不应被两个不同路由复用", routes.size, keys.toSet().size)
        assertEquals(RouteKey.BOOKSHELF, Route.Bookshelf.key)
        assertEquals(RouteKey.VOCABULARY, Route.Vocabulary.key)
        assertEquals(RouteKey.SYNC_DETAIL, Route.SyncDetail.key)
        assertEquals("阅读页的路由键与具体书 id 无关", RouteKey.READER, Route.Reader("x").key)
    }

    @Test
    fun registryReportsMissingScreensInsteadOfCrashing() {
        val registry = screenRegistry {
            screen(RouteKey.BOOKSHELF) { }
            screen(RouteKey.READER) { }
        }
        assertFalse(registry.isComplete())
        assertEquals(listOf(RouteKey.VOCABULARY, RouteKey.SYNC_DETAIL), registry.missing())
        assertNull("未注册的屏幕查表返回 null，由 NavHostLite 渲染占位", registry[RouteKey.VOCABULARY])
    }

    @Test
    fun registryKnowsWhenEverythingIsMigrated() {
        val registry = screenRegistry {
            screen(RouteKey.BOOKSHELF) { }
            screen(RouteKey.VOCABULARY) { }
            screen(RouteKey.SYNC_DETAIL) { }
            screen(RouteKey.READER) { }
        }
        assertTrue("P3 迁移完成后这条应当是 true", registry.isComplete())
        assertTrue(registry.missing().isEmpty())
    }

    @Test
    fun emptyRegistryIsEmpty() {
        assertFalse(ScreenRegistry.EMPTY.isComplete())
        assertEquals(RouteKey.entries.toSet(), ScreenRegistry.EMPTY.missing().toSet())
        assertTrue(ScreenRegistry.EMPTY.registered().isEmpty())
    }

    @Test
    fun overlayRegistryFlagsUnmigratedKinds() {
        val registry = overlayRegistry {
            overlay(OverlayKind.STORAGE) { }
        }
        assertEquals(listOf<OverlayKind>(OverlayKind.STORAGE), registry.registered().toList())
        assertEquals(
            listOf(OverlayKind.AI_DRAWER, OverlayKind.PACKS),
            registry.missing(listOf(OverlayKind.STORAGE, OverlayKind.AI_DRAWER, OverlayKind.PACKS))
        )
        assertNull("未注册的弹层查表返回 null，走 FallbackOverlay", registry[OverlayKind.PACKS])
    }
}
