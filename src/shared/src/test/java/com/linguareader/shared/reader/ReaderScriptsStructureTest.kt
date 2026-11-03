package com.linguareader.shared.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页注入脚本的**结构护栏**（P4 拆分前先建的安全网，见 `方案-P4阅读页JS重构.md`）。
 *
 * 为什么需要它：`bootstrap()` 内嵌 69KB 的 HTML/CSS/JS 字符串。拆分这个巨型字符串时，
 * 任何一处多/少一个反引号、少一个分号，都会让整页白屏——而 WebView 在 Robolectric 里
 * 不执行 JS，单测抓不到。唯一的可靠办法是**锁住它当前的样子**：
 *
 * - 16 个 `window.lrXxx` 公开 API 一个都不能少（EpubPage 与桌面 JCEF 都依赖这份协议）；
 * - 12 个 CSS 变量一个都不能少（皮肤系统与阅读主题通过它们注入颜色）；
 * - 关键结构锚点（分页容器、内容容器、样式安装）必须存在。
 *
 * 拆分重构后，这些断言全部保留；**新增断言**用来证明拆出来的各段拼回去与原串一致。
 */
class ReaderScriptsStructureTest {

    /** 16 个公开 API：Kotlin 侧 ReaderController 的 evaluateJavascript 与桌面 JCEF 都按名字调。 */
    private val requiredApis = listOf(
        "lrTurn", "lrSetPage", "lrGetPage", "lrSyncPage",
        "lrEnterScrollMode", "lrExitScrollMode",
        "lrLookupAt", "lrHighlightBlock", "lrHighlightSentence", "lrClearHighlight",
        "lrRefreshSavedWords", "lrSetChromeInsets", "lrSetChoosingStart",
        "lrLocusHere", "lrScrollToLocus", "lrBackToSpeaking"
    )

    /** 12 个 CSS 变量。改名字等于同时改 Kotlin 侧 preferenceScript 与所有主题。 */
    private val requiredCssVars = listOf(
        "--lr-bg", "--lr-fg", "--lr-font", "--lr-size", "--lr-line",
        "--lr-mark", "--lr-link", "--lr-due", "--lr-selection", "--lr-highlight",
        "--lr-chrome-top", "--lr-chrome-bottom"
    )

    private val defaultPrefs = com.linguareader.shared.data.ReaderPreferences()

    private val bootstrap: String by lazy { ReaderScripts.bootstrap(0, defaultPrefs) }

    @Test
    fun everyPublicApiIsPresent() {
        val missing = requiredApis.filterNot { bootstrap.contains("window." + it) }
        assertTrue("以下 window.* API 缺失，会直接打断阅读器与桌面端：" + missing, missing.isEmpty())
    }

    @Test
    fun everyCssVariableIsPresent() {
        val missing = requiredCssVars.filterNot { bootstrap.contains(it) }
        assertTrue("以下 CSS 变量缺失，皮肤/主题注入会失效：" + missing, missing.isEmpty())
    }

    @Test
    fun structuralAnchorsExist() {
        // 分页骨架：外层滚动容器 + 内容容器 + 换列占位
        for (anchor in listOf("lr-scroller", "lingua-reader-content", "lr-spacer")) {
            assertTrue("缺少分页骨架节点 #" + anchor, bootstrap.contains(anchor))
        }
        // 样式安装入口
        assertTrue("缺少 installStyle 样式安装函数", bootstrap.contains("installStyle"))
        // 桥接对象名（Kotlin 侧 addJavascriptInterface(..., "ReaderBridge")）
        assertTrue("缺少 ReaderBridge 桥接调用", bootstrap.contains("ReaderBridge"))
    }

    @Test
    fun bootstrapIsStableAcrossCalls() {
        // 纯函数性：两次调用必须完全一致（否则拆分时引入的随机/时间因素会逃过测试）
        assertEquals(
            "bootstrap() 必须是纯函数",
            ReaderScripts.bootstrap(0, defaultPrefs),
            ReaderScripts.bootstrap(0, defaultPrefs)
        )
    }

    @Test
    fun savedWordsScriptInjectsGivenWords() {
        val script = ReaderScripts.savedWordsScript(listOf("apple", "banana"), listOf("apple"))
        assertTrue(script.contains("apple"))
        assertTrue(script.contains("banana"))
        // 已到期的词要单独标记（高亮成「待复习」色）
        assertTrue(script.contains("lrRefreshSavedWords") || script.contains("due"))
    }

    @Test
    fun preferenceScriptWritesAllLayoutVariables() {
        val script = ReaderScripts.preferenceScript(defaultPrefs, syncCurrentPage = false)
        for (v in listOf("--lr-bg", "--lr-fg", "--lr-font", "--lr-size", "--lr-line")) {
            assertTrue("preferenceScript 必须写 " + v, script.contains(v))
        }
    }
}
