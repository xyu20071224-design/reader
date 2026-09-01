package com.linguareader.app.reader

import com.linguareader.app.data.ReaderPreferences
import com.linguareader.app.data.ReaderTheme
import com.linguareader.app.tts.TtsTextExtractor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReaderScriptsTest {

    /**
     * M0 安全网：块选择器的**双实现漂移守卫**。
     *
     * 块序号（highlightBlockIndex / 未来的 ReadingLocus.blockIndex）的语义由两套
     * 独立实现共同定义：JS 的 `TTS_BLOCK_SELECTOR`（ReaderScripts.kt）与 Kotlin 的
     * [TtsTextExtractor.BLOCK_SELECTOR]。两边只要有一处改了，块序就会静默错位，
     * 而错位表现为「高亮画在别的段落」——历史上正是这么翻的车（Jsoup select 含自身）。
     * 两处源码注释互相声明了这条约束，但此前**没有任何测试兜住**它。
     *
     * JS 侧写成两段字面量拼接，所以这里把引号内的片段抠出来再拼，逐字比对。
     */
    @Test
    fun ttsBlockSelectorStaysIdenticalOnBothSides() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())
        val declaration = Regex("const TTS_BLOCK_SELECTOR\\s*=\\s*((?:'[^']*'\\s*\\+?\\s*)+);")
            .find(script)
        assertNotNull(declaration, "bootstrap 里找不到 TTS_BLOCK_SELECTOR 声明——JS 侧被改名或改写了")
        val jsSelector = Regex("'([^']*)'")
            .findAll(declaration.groupValues[1])
            .joinToString("") { it.groupValues[1] }

        assertEquals(
            TtsTextExtractor.BLOCK_SELECTOR,
            jsSelector,
            "JS 与 Kotlin 的块选择器已漂移：块序号会静默错位，改一侧必须同时改另一侧"
        )
    }
    @Test
    fun bootstrapContainsWordAndSentenceExtraction() {
        val script = ReaderScripts.bootstrap(4, ReaderPreferences())

        assertContains(script, "caretRangeFromPoint")
        assertContains(script, "onWord")
        assertContains(script, "Intl.Segmenter")
        assertContains(script, "sentenceOffset")
        assertContains(script, "word.endsWith('.')")
        assertContains(script, "Math.max(0, 4)")
    }

    @Test
    fun wordLookupUsesOneNormalizedCoordinateSpaceForSentenceOffset() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // Sentence segmentation and the clicked-word offset must both run on
        // the trimmed, whitespace-normalized paragraph. Mixing raw and
        // normalized offsets drifts the tapped word when the HTML has
        // indentation or line breaks (and repeated words get mislocated).
        assertContains(script, "const segments = sentenceSegments(paragraph);")
        assertContains(script, "inBlock - leadingWhitespace")
        // A word at the exact start of the next sentence belongs to that
        // sentence, not to the previous one.
        assertContains(script, "inBlock < segmentEnd")
        // The displayed context must always contain the tapped word; if the
        // segmented sentence ever misses it, fall back to the paragraph.
        assertContains(script, "sentence.toLowerCase().indexOf(word.toLowerCase())")
        // The TTS tap offset shares the same trimmed paragraph coordinates.
        assertContains(script, "block: paragraph, blockOffset: inBlock")
    }

    @Test
    fun ttsHighlightSkipsLeadingWhitespaceWhenMappingRanges() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // block.text is trimmed before sentence matching, so the DOM range
        // walker must skip leading whitespace instead of shifting offsets.
        assertContains(script, "Leading whitespace is trimmed from block.text")
        assertContains(script, "if (!sawContent)")
    }

    // ── M1 第 1 刀：位置语义锚点（ReadingLocus）的 JS 侧 ──────────────────

    @Test
    fun bootstrapExposesLocusReadAndRestore() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        assertContains(script, "window.lrLocusHere = function()")
        assertContains(script, "window.lrScrollToLocus = function(blockIndex, charOffset, anchor)")
        // 锚点必须是「块下标 + 块内偏移」，不能退回块文本（重复段落会撞车）
        assertContains(script, "blockIndex: index")
        assertContains(script, "charOffset: blockCharOffsetAtViewStart(blocks[index])")
    }

    @Test
    fun locusRestoreHandlesChapterStartAndEndAsSemanticAnchors() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // 末页/章首是语义锚，不再是挤进页码字段的哨兵值
        assertContains(script, "if (mode === 'chapter-start')")
        assertContains(script, "if (mode === 'chapter-end')")
        assertContains(script, "page = Math.max(0, pageCount - 1); applyPage();")
        // 精确锚点走的是既有的 Range 定位器，而不是另起一套几何推算
        assertContains(script, "rangeFromNormalizedOffset(target.el, offset, 1)")
    }

    @Test
    fun locusCharOffsetUsesBinarySearchAgainstContainerRelativeGeometry() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // 长段落横跨多页时不能把位置粗化成整段：二分找第一个仍在本页的字符
        assertContains(script, "function blockCharOffsetAtViewStart(block)")
        assertContains(script, "const mid = (lo + hi) >> 1;")
        // 几何换算必须减去容器 rect（followRangeIntoView 的写法），
        // 直接 rect.top + scrollTop 会漏掉容器自身偏移
        assertContains(script, "scroller.scrollTop + (box.top - sr.top)")
        assertContains(script, "scroller.scrollLeft + (box.left - sr.left)")
    }

    @Test
    fun firstVisibleBlockStillReturnsTextForTheLegacyTtsPath() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // 旧链路（TTS 位置回报）暂时仍吃块文本，改造放在第 2 刀
        assertContains(script, "window.lrFirstVisibleBlock = function()")
        assertContains(script, "return index >= 0 && blocks[index] ? blocks[index].text : null;")
        assertContains(script, "function firstVisibleBlockIndex(blocks)")
    }

    @Test
    fun tapToStartUsesSameBlockSelectorAsTtsExtractor() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // The tapped paragraph must resolve to the same leaf block that
        // ttsBlocks()/TtsTextExtractor use; otherwise a tap on a word inside
        // section/article/pre/h5/h6 falls back to sentence 0 of the chapter.
        assertContains(script, "element.closest(TTS_BLOCK_SELECTOR)")
    }

    @Test
    fun preferencesAreEncodedIntoCssVariables() {
        val script = ReaderScripts.preferenceScript(
            ReaderPreferences(fontPercent = 125, theme = ReaderTheme.DARK)
        )

        assertContains(script, "#171717")
        assertContains(script, "125%")
        // Preference changes from Kotlin still re-sync the current page.
        assertContains(script, "window.lrSyncPage")
    }

    @Test
    fun markColorsFollowReaderThemeVariant() {
        val dark = ReaderScripts.preferenceScript(ReaderPreferences(theme = ReaderTheme.DARK))
        val paper = ReaderScripts.preferenceScript(ReaderPreferences(theme = ReaderTheme.PAPER))

        // 夜间主题注入提亮变体（与外壳 DarkLinguaPalette.accent 同源），
        // 浅色主题维持历史值不动。
        assertContains(dark, "'--lr-mark', \"#C98A5E\"")
        assertContains(dark, "'--lr-link', \"#D7A072\"")
        assertContains(paper, "'--lr-mark', \"#8D5535\"")
        assertContains(paper, "'--lr-link', \"#9b6b43\"")
    }

    @Test
    fun injectedCssReferencesMarkVariablesNotHardcodedColors() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // 生词下划线/链接/选区/TTS 高亮必须走 CSS 变量，主题切换才能生效；
        // 写死的浅色值在夜间底上对比不足（2026-08-24 真机确认的缺陷）。
        // 注意：bootstrap 里仍会出现 PAPER 默认主题的十六进制值（那是注入的
        // 变量值本身），要防的是 CSS 规则里写死颜色。
        assertContains(script, "text-decoration-color: var(--lr-mark)")
        assertContains(script, "text-decoration-color: var(--lr-link)")
        assertContains(script, "::selection { background: var(--lr-selection); }")
        assertContains(script, "'background:var(--lr-highlight);border-radius:3px;'")
        assertFalse(script.contains("text-decoration-color: #"))
        assertFalse(script.contains("background:rgba(184,132,83,.32);border-radius"))
    }

    @Test
    fun markColorsKeepAtLeastThreeToOneContrastOnTheirBackground() {
        // WCAG 相对亮度对比度：夜间正文底上 2.97:1 的旧值就是缺陷根源，
        // 所有主题的标记/链接色对自己的正文底必须 ≥3:1。
        fun luminance(hex: String): Double {
            val value = hex.removePrefix("#").toLong(16)
            fun channel(shift: Int): Double {
                val c = ((value shr shift) and 0xFF).toDouble() / 255.0
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        fun contrast(a: String, b: String): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }
        ReaderTheme.entries.forEach { theme ->
            assertTrue(
                contrast(theme.markColor, theme.background) >= 3.0,
                "markColor of ${theme.name} must contrast >= 3:1 (was ${contrast(theme.markColor, theme.background)})"
            )
            assertTrue(
                contrast(theme.linkColor, theme.background) >= 3.0,
                "linkColor of ${theme.name} must contrast >= 3:1 (was ${contrast(theme.linkColor, theme.background)})"
            )
        }
    }

    @Test
    fun injectedCssForcesTypographyOverEpubStyles() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences(fontPercent = 130, lineHeight = 1.9f))

        // Body-level typography is marked !important so the reader settings win
        // even when the EPUB stylesheet lands after the injected <style>.
        assertContains(script, "font-size: var(--lr-size) !important")
        assertContains(script, "line-height: var(--lr-line) !important")
        // Every content element is normalized: EPUB font-size/line-height rules
        // on p/div/etc. can no longer override the inherited reader settings.
        assertContains(script, "#lingua-reader-content *")
        assertContains(script, "font-size: 1em !important")
        assertContains(script, "line-height: inherit !important")
        // The chosen reader font wins over EPUB font-family rules as well.
        assertContains(script, "font-family: var(--lr-font) !important")
        assertContains(script, "font-family: inherit !important")
        // Heading hierarchy is re-applied relative to the normalized body size.
        assertContains(script, "#lingua-reader-content h1 { font-size: 1.6em !important; }")
        assertContains(script, "#lingua-reader-content h4 { font-size: 1.12em !important; }")
    }

    @Test
    fun bootstrapContainsSwipeToTurnPages() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // Native panning is disabled so a drag never fights the JS pagination.
        assertContains(script, "touch-action: none")
        // A quick horizontal drag turns the page (left = next, right = previous).
        assertContains(script, "window.lrTurn(rawDx < 0 ? 1 : -1)")
        assertContains(script, "dx >= 45")
    }

    @Test
    fun bootstrapContainsVerticalSwipeToTurnPages() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // A quick vertical drag also turns the page (up = next, down = previous)
        // with the same dominance and speed thresholds as the horizontal swipe.
        assertContains(script, "window.lrTurn(rawDy < 0 ? 1 : -1)")
        assertContains(script, "dy >= 45")
        assertContains(script, "dy > dx * 1.5")
    }

    @Test
    fun bootstrapContainsScrollModeLayoutAndBridge() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // Slow drags switch the horizontal pager to a vertical scroll layout and
        // report mode/progress back to Kotlin through the bridge.
        assertContains(script, "lrEnterScrollMode")
        assertContains(script, "lrExitScrollMode")
        assertContains(script, "ReaderBridge.onScrollModeChanged")
        assertContains(script, "ReaderBridge.onScrollProgress")
        assertContains(script, "overflowY = 'auto'")
        assertContains(script, "columnCount = '1'")
        assertContains(script, "scrollRatio * max")
    }

    @Test
    fun bootstrapContainsSlowDragScrollDetection() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // A slow vertical drag (longer than 450ms or slower than 0.12px/ms) is
        // claimed while moving; fast swipes are still resolved on pointerup.
        assertContains(script, "pointermove")
        assertContains(script, "dragScrollActive")
        assertContains(script, "dy >= 24")
        assertContains(script, "elapsed > 450")
        assertContains(script, "dy / Math.max(1, elapsed) < 0.12")
    }

    @Test
    fun bootstrapRestoresScrollModeWhenRequested() {
        val script = ReaderScripts.bootstrap(
            initialPage = 0,
            preferences = ReaderPreferences(),
            initialScrollMode = true,
            initialScrollRatio = 0.42f,
            initialScrollPageCount = 8
        )

        assertContains(script, "let scrollMode = true")
        assertContains(script, "pageCount = scrollPageCount")
        assertContains(script, "applyScrollLayout()")
        assertContains(script, "ReaderBridge.onScrollModeChanged(true)")
    }

    @Test
    fun scrollModeIsStickyUntilExitButton() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // Once slow-scroll mode is entered it stays active: lrTurn never exits
        // it, vertical drags always scroll, and only lrExitScrollMode (the
        // "分页" button) restores pagination.
        assertContains(script, "Sticky scroll mode")
        assertContains(script, "chapter-start/end transitions")
        assertContains(script, "every vertical-dominant drag scrolls")
        assertContains(script, "only the \"分页\" button")
        // A fast flick at the chapter edge still continues to the next chapter.
        assertContains(script, "A fast flick at the chapter edge")
        // Page jumps inside scroll mode map to a ratio instead of exiting.
        assertContains(script, "scrollRatio = last > 0 ? clamp(target / last, 0, 1) : 0")
        // A fresh chapter estimates its scroll page count from the flow height
        // so sticky scroll mode keeps ratio -> page mapping meaningful.
        assertContains(script, "Math.ceil(scroller.scrollHeight / Math.max(1, columnHeight))")
    }

    @Test
    fun paginationScrollsThroughWrappedScroller() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // Pagination runs inside a dedicated horizontal scroller instead of the
        // window, so the last partial column can never be clamped short of its
        // 28px left margin (the last-page offset bug).
        assertContains(script, "#lr-scroller")
        assertContains(script, "scroller.scrollLeft = page * window.innerWidth")
        // A full-height trailing spacer column keeps scrollWidth deterministic:
        // the page count subtracts it, and every real page stays reachable.
        assertContains(script, "lr-spacer")
        assertContains(script, "break-before: column")
        assertContains(script, "Math.ceil(scroller.scrollWidth / window.innerWidth) - 1")
        assertContains(script, "window.lrGetPage")
        // The restored page is remembered separately from the measured count so
        // a premature first measurement cannot push the restore target to 0.
        assertContains(script, "restoreTarget")
        // Bootstrap must NOT re-sync the page: at install time the scroller has
        // not scrolled yet, so syncing would read page 0 and clobber the
        // restored reading position (the "always opens chapter page 1" bug).
        // The lrSyncPage definition is expected; only the call site must be absent.
        assertFalse(script.contains("if (window.lrSyncPage) window.lrSyncPage();"))
        // The old viewport-scroll approach would reintroduce the last-page offset.
        assertFalse(script.contains("window.scrollTo(page * window.innerWidth, 0);"))
    }

    @Test
    fun bootstrapContainsSavedWordHighlighting() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        assertContains(script, "lrRefreshSavedWords")
        assertContains(script, "lr-saved-word")
        assertContains(script, "createTreeWalker")
        assertContains(script, "unwrapSavedMarks")
    }

    @Test
    fun savedWordHighlightMatchesWholeWordsNotSubstrings() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // 旧实现用 indexOf 做子串匹配，一次犯三个错：app 画进 apple（多画）、
        // study 画不上 studied（漏画）、run 只画出 running 的前三个字母（半画）。
        // 现在必须是 \b 词边界 + 形态展开。
        assertContains(script, "escapeForRegExp")
        assertContains(script, "savedWordVariants")
        assertContains(script, "buildSavedPattern")
        // 高亮要保留正文原样文本，不能拿存储的拼写覆盖大小写与变形。
        assertContains(script, "span.textContent = match[0];")
        // 子串匹配的老路径必须消失。
        assertFalse(script.contains("lower.indexOf(word.toLowerCase())"))
    }

    @Test
    fun savedWordsScriptEncodesListAndCallsRefresh() {
        val script = ReaderScripts.savedWordsScript(listOf("carry", "look forward to", "carry"))

        assertContains(script, "lrRefreshSavedWords")
        assertContains(script, "\"carry\"")
        assertContains(script, "\"look forward to\"")
        assertFalse(script.contains("carry,carry"))
    }

    @Test
    fun bootstrapContainsTtsHighlightAndListenModeBridge() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        assertContains(script, "lrHighlightSentence")
        assertContains(script, "lrClearHighlight")
        assertContains(script, "lrFirstVisibleBlock")
        assertContains(script, "lrSetChoosingStart")
        assertContains(script, "ReaderBridge.onSentenceTapped")
        assertContains(script, "TTS_BLOCK_SELECTOR")
        // The choose-start flag is consumed by the first tap, so normal
        // playback taps never restart the queue.
        assertContains(script, "window.__lrChoosingStart = false")
        // The sentence highlight repositions in place; it must never force
        // the reader to scroll to the spoken sentence's page.
        assertFalse(script.contains("ReaderBridge.onTtsPage"))
        assertFalse(script.contains("targetPage * window.innerWidth"))
    }

    @Test
    fun ttsHighlightMeasuresItsOwnOriginInsteadOfAssumingTheScrollerBox() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // 真机（分页多列布局）实测：滚动容器 style.top=104 但 rect.top=136，覆盖层
        // 的绝对定位原点又在 200，按「rect - scrollerRect + scrollTop」推算会让高亮
        // 框整体低 64px（≈2.7 行），看起来像高亮了后面几行的句子。
        // 所以必须用零尺寸探针实测原点，而不是假设包含块等于滚动容器的 padding box。
        assertContains(script, "const originRect = probe.getBoundingClientRect();")
        assertContains(script, "'left:' + (rect.left - originRect.left) + 'px;' +")
        assertContains(script, "'top:' + (rect.top - originRect.top) + 'px;' +")
        // 旧的推算方式不能再出现，否则偏移会回归。
        assertFalse(script.contains("rect.top - scrollerRect.top"))
        assertFalse(script.contains("rect.left - scrollerRect.left"))
        // 探针只用于测量，必须立刻移除，不留在 DOM 里。
        assertContains(script, "probe.remove();")
        // 覆盖层仍然挂在滚动容器内（随内容滚动），不是 position:fixed。
        assertContains(script, "(scroller || document.body).appendChild(overlay);")
        assertFalse(script.contains("position:fixed;left:' + rect.left"))
    }

    @Test
    fun highlightDiagnosticsAreNotShippedInTheBootstrap() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // 定位偏移时加过临时诊断日志，别再回到仓库里。
        assertFalse(script.contains("lrHighlightDebug"))
        assertFalse(script.contains("[lr-hl]"))
    }

    @Test
    fun chromeInsetsDefaultToLegacyGeometry() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // 未注入实测值时必须与旧几何完全一致（顶 104 / 底 70），首帧行为不变。
        assertContains(script, "let chromeTop = Math.max(0, 104)")
        assertContains(script, "let chromeBottom = Math.max(0, 70)")
        assertContains(script, "--lr-chrome-top: 104px")
        assertContains(script, "--lr-chrome-bottom: 70px")
    }

    @Test
    fun bootstrapEmbedsMeasuredChromeInsetsInsteadOfHardcodedGeometry() {
        val script = ReaderScripts.bootstrap(
            0,
            ReaderPreferences(),
            chromeTopPx = 96,
            chromeBottomPx = 88
        )

        // targetSdk 35 强制 edge-to-edge 后底栏含导航栏 inset，写死的预留量
        // 会遮住正文最后一行；分页盒必须由 Kotlin 实测值驱动。
        assertContains(script, "let chromeTop = Math.max(0, 96)")
        assertContains(script, "let chromeBottom = Math.max(0, 88)")
        assertContains(script, "--lr-chrome-top: 96px")
        assertContains(script, "--lr-chrome-bottom: 88px")
        // 旧的写死几何不能再出现，否则回归时无法察觉。
        assertFalse(script.contains("window.innerHeight - 174"))
        assertFalse(script.contains("top: 104px"))
        assertFalse(script.contains("top = '104px'"))
    }

    @Test
    fun chromeInsetUpdatesRemeasureWithoutReloading() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // 运行时的 inset 变化（旋转/切换导航模式）走 lrSetChromeInsets：
        // 值没变直接返回，变了才重排，且 updateMetrics 自带保页逻辑。
        assertContains(script, "window.lrSetChromeInsets")
        assertContains(script, "if (t === chromeTop && b === chromeBottom) return;")
        assertContains(script, "setTimeout(updateMetrics, 30)")
    }

    @Test
    fun paginationBoxSelfCalibratesAgainstScrollerDisplacement() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        // 真机实测：绝对定位 scroller 的渲染位置比 style.top 整体下移约 32px
        // （known-pitfalls #8），正文实际底缘因此低于底栏上沿，最后一行被
        // 半透明底栏盖住。必须实测 rect 并把超出部分从高度里扣掉。
        assertContains(script, "__lrRect.bottom - (window.innerHeight - chromeBottom)")
        assertContains(script, "columnHeight - __lrOvershoot")
    }

    @Test
    fun enteringScrollModeReadsThePagedRatioBeforeFlippingTheMode() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        val enter = script.indexOf("function enterScrollMode(")
        val exit = script.indexOf("function exitScrollMode(")
        assertTrue(enter >= 0 && exit > enter)
        val body = script.substring(enter, exit)
        // 先置位再取值时，currentRatio() 会走 currentScrollRatio() 分支，而此刻
        // 还是分页布局（overflow-y:hidden，scrollTop 恒 0）→ 进度被抹成 0 →
        // syncScroll() 把视图拉回章首（从第 1 页进入看不出来，从第 10 页必现）。
        // exitScrollMode 一直是「先取值、后置位」，两个函数必须对称。
        assertTrue(
            body.indexOf("currentRatio()") < body.indexOf("scrollMode = true"),
            "enterScrollMode must capture the paged ratio before setting scrollMode"
        )
        assertFalse(body.contains("scrollRatio = ratio == null ? currentRatio()"))
    }

    @Test
    fun lastPageSentinelIsNeverClampedLikeAnOrdinaryPageIndex() {
        val script = ReaderScripts.bootstrap(ReaderScripts.LAST_PAGE, ReaderPreferences())

        // 章节回翻（fromEnd）用 LAST_PAGE 哨兵表示「本章最后一页」。旧实现把它
        // 当普通页码：第一次测量常跑在字体/图片就绪之前，pageCount 偏小甚至为 1，
        // clamp 把页码拍成 0；而救援分支判据 restoreTarget <= pageCount - 1 对
        // Int.MAX_VALUE 永远不成立 → 页码永久停在 0 → 回翻落到上一章开头。
        assertContains(script, "const LR_LAST_PAGE = 2147483647;")
        assertContains(
            script,
            "let restoreTarget = 2147483647 >= LR_LAST_PAGE ? -1 : Math.max(0, 2147483647);"
        )
        // 哨兵分支必须先于 clamp，且每次重排都重新取末页。
        val metrics = script.substring(script.indexOf("function updateMetrics("))
        val sentinelBranch = metrics.indexOf("if (restoreTarget < 0) {")
        val clampBranch = metrics.indexOf("page = clamp(page, 0, pageCount - 1);")
        assertTrue(sentinelBranch >= 0 && clampBranch > sentinelBranch)
        assertContains(script, "page = pageCount - 1;")
        // 重复注入走 lrSetPage(initialPage)，同一套哨兵约定必须也认。
        assertContains(
            script,
            "restoreTarget = requested >= LR_LAST_PAGE ? -1 : Math.max(0, requested);"
        )
        // 旧写法（哨兵直接当页码播种 restoreTarget）不能回来。
        assertFalse(script.contains("let restoreTarget = page;"))
    }

    @Test
    fun preferenceSyncDoesNotOverwriteAPendingRestoreTarget() {
        val script = ReaderScripts.bootstrap(ReaderScripts.LAST_PAGE, ReaderPreferences())

        // 改字号/行距/主题会走 lrSyncPage，它原本无条件按「当前渲染页」重新
        // 播种 restoreTarget。若还原尚未落地（末页哨兵，或救援目标还没追上），
        // 那一下就把还原目标抹成当前的临时页，进度永久丢失。
        assertContains(script, "} else if (restoreTarget < 0 || restoreTarget > page) {")
    }

    @Test
    fun ordinaryRestoredPageKeepsTheClampAndRescuePath() {
        val script = ReaderScripts.bootstrap(7, ReaderPreferences())

        // 普通页码的还原路径不受哨兵改动影响：仍然 clamp，仍然在测量变准后救回。
        assertContains(script, "let restoreTarget = 7 >= LR_LAST_PAGE ? -1 : Math.max(0, 7);")
        assertContains(
            script,
            "if (restoreTarget <= pageCount - 1) page = Math.max(page, restoreTarget);"
        )
    }

    @Test
    fun scrollHintsAreInjectedAsEscapedJsLiterals() {
        // 提示文案来自本地化资源，英文里 "chapter's end" 这类撇号极常见。旧写法把它
        // 裸拼进单引号 JS 字面量，一条带撇号的翻译就会让整段 bootstrap 变成语法错误：
        // 注入失败即正文白屏、翻页全废。必须按 JS 字面量转义后再拼。
        val script = ReaderScripts.bootstrap(
            0,
            ReaderPreferences(),
            scrollEndHint = """chapter's end " \ next""",
            scrollStartHint = "chapter's start"
        )

        assertContains(script, """hint.textContent = "chapter's end \" \\ next";""")
        assertContains(script, """hint.textContent = "chapter's start";""")
        // 旧的裸单引号拼接不能回来。
        assertFalse(script.contains("hint.textContent = 'chapter"))
    }
}
