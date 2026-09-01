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
        // 还原路径落位时不能重取锚点（applyPage(false)）：锚点刚被显式写进去，
        // 重取会立刻把它向下取整到页首块，还原当场就退化。
        assertContains(script, "page = Math.max(0, pageCount - 1); applyPage(false); }")
        // 精确锚点走的是既有的 Range 定位器，而不是另起一套几何推算
        assertContains(script, "rangeFromNormalizedOffset(target.el, offset, 1)")
    }

    /**
     * 锚点只能由「用户自己动了」这个因果重取，重排/还原一律不许重取。
     *
     * 2026-09-01 真机回归：applyPage() 里无条件 refreshAnchorLocus()，于是每次
     * 重排都把锚点向下取整到当前页的第一个块，转两次屏就从块 5 退到 3，再转就
     * 钉死在章首块 0——「位置真相」被派生量反向覆写。裸 applyPage() 调用一旦
     * 复活，这条就红。
     */
    @Test
    fun anchorIsRefreshedOnlyByUserInitiatedMovement() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        assertContains(script, "function applyPage(reanchor)")
        assertContains(script, "if (reanchor) refreshAnchorLocus();")
        // 用户翻页 = 真因果，必须重取
        assertContains(script, "applyPage(true);")
        // 不允许任何「不声明因果」的调用点
        assertFalse(
            script.contains("applyPage();"),
            "applyPage() 必须显式声明因果：用户动作传 true，重排/还原传 false"
        )
    }

    /**
     * 分页模式下回报锚点必须回报「权威锚点」本身，而不是现在视口起点是谁。
     * 后者是取整后的派生量，落盘一次就把退化值变成新真相。
     */
    @Test
    fun locusReadbackReturnsTheAuthoritativeAnchorInPagedMode() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        assertContains(script, "if (!anchorLocus || anchorLocus.anchor !== 'exact') refreshAnchorLocus();")
        assertContains(script, "blockIndex: anchorLocus.blockIndex")
        // 用户跳页要作废锚点，否则跳页会被旧锚点拉回去
        assertContains(script, "window.lrSetPage = function(value, keepAnchor)")
        assertContains(script, "if (!keepAnchor) anchorLocus = null;")
        // 改字号是重排不是跳页：锚点要留着
        assertContains(script, "window.lrSetPage(window.lrGetPage(), true);")
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
    fun anchorLocusDrivesPagedRelayoutInsteadOfRawPageIndex() {
        val script = ReaderScripts.bootstrap(
            initialPage = 3,
            preferences = ReaderPreferences(),
            initialLocusBlock = 41,
            initialLocusOffset = 128
        )

        // 锚点被注入，且重排时锚点优先于页码
        assertContains(script, "let anchorLocus = { blockIndex: 41, charOffset: 128, anchor: \"exact\" };")
        assertContains(script, "const anchoredPage = anchorLocus ? pagedPageForLocus(anchorLocus) : null;")
        assertContains(script, "if (anchoredPage !== null) {")
        // 用户主动翻页后要重新取锚点，否则下一次重排又会按旧锚点弹回去。
        // 只钉顺序不钉相邻：取锚点必须发生在回报之前（回报会触发 Kotlin 侧落盘），
        // 中间允许插入别的「用户因果」处理（如用户接管窗口的置位）。
        val applyPageBody = Regex("function applyPage\\(reanchor\\) \\{([\\s\\S]*?)\\n          \\}")
            .find(script)?.groupValues?.get(1)
        assertNotNull(applyPageBody, "找不到 applyPage 函数体——它被改名或改写了")
        assertTrue(
            applyPageBody.indexOf("refreshAnchorLocus();") <
                applyPageBody.indexOf("ReaderBridge.onPageChanged(page, pageCount);"),
            "锚点必须在回报之前重取，否则落盘的是上一次的锚点"
        )
    }

    @Test
    fun withoutALocusTheLegacyPageRestorePathStaysIntact() {
        // 迁移期：老书还没有锚点，必须原样走 restoreTarget 那条路
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        assertContains(script, "let anchorLocus = null;")
        assertContains(script, "if (restoreTarget <= pageCount - 1) page = Math.max(page, restoreTarget);")
    }

    @Test
    fun chapterEndAnchorIsResolvedAtEveryRelayout() {
        val script = ReaderScripts.bootstrap(
            initialPage = 0,
            preferences = ReaderPreferences(),
            initialLocusAnchor = ReaderScripts.ANCHOR_CHAPTER_END
        )

        assertContains(script, "anchor: \"chapter-end\"")
        // 末页锚点每次重排重新取「当前的最后一页」，而不是记死一个页码
        assertContains(script, "if (locus.anchor === 'chapter-end') return Math.max(0, pageCount - 1);")
    }

    @Test
    fun scrollModeUsesTheAnchorOnlyForTheFirstLanding() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences(), initialLocusBlock = 5)

        // scroll 事件密度极高，落位后清空锚点，之后按 scrollRatio 维持
        assertContains(script, "const anchoredRatio = anchorLocus ? scrollRatioForLocus(anchorLocus) : null;")
        assertContains(script, "anchorLocus = null;")
    }

    /**
     * 听书自动跟随的「用户接管窗口」。
     *
     * 跟随本身是好事（朗读到哪看到哪），但用户想往后翻两页确认个人名时，
     * 下一句一念就把视口拽回去，等于翻不动页。所以用户自己动过之后要让位
     * 一段时间：窗口内不跟随，到期或按「回到朗读处」立刻恢复。
     *
     * 关键约束是**只有用户因果才置位**：程序化移动（重排、还原、跟随自己
     * 造成的滚动）若也置位，跟随会把自己永久锁死。
     */
    @Test
    fun followYieldsToTheUserTakeoverWindow() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        assertContains(script, "const LR_FOLLOW_TAKEOVER_MS = ${ReaderScripts.FOLLOW_TAKEOVER_MS};")
        assertContains(script, "function noteUserTakeover()")
        assertContains(script, "userTakeoverUntil = Date.now() + LR_FOLLOW_TAKEOVER_MS;")
        // 跟随让位
        assertContains(script, "if (Date.now() < userTakeoverUntil) return;")
        // 用户翻页：与 refreshAnchorLocus 共用 reanchor 这个因果判据
        assertContains(script, "if (reanchor) noteUserTakeover();")

        // 置位点只允许两处：用户翻页（applyPage 的 reanchor）与拖动滚动。
        // 多出来的一处几乎必然是程序化路径，会把跟随锁死。
        assertEquals(
            2,
            Regex("noteUserTakeover\\(\\);").findAll(script).count(),
            "用户接管窗口的置位点只能是「用户自己动」的两条路径"
        )
    }

    /**
     * 「回到朗读处」：用户主动要求跟回朗读位置，因此立刻结束接管窗口，
     * 并复用 lrScrollToLocus 那套已验证的锚点落位机器（锚点跟着走，
     * 落盘的阅读进度就是朗读处本身）。
     */
    @Test
    fun backToSpeakingEndsTheTakeoverWindowAndReusesLocusLanding() {
        val script = ReaderScripts.bootstrap(0, ReaderPreferences())

        assertContains(script, "window.lrBackToSpeaking = function(blockIndex, charOffset)")
        assertContains(script, "userTakeoverUntil = 0;")
        assertContains(script, "return window.lrScrollToLocus(blockIndex, charOffset, 'exact');")
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

    /**
     * 「本章最后一页」曾经是挤在页码字段里的 Int.MAX_VALUE 哨兵（JS 内部译成
     * restoreTarget = -1），一个字段身兼两种含义，还得在 clamp、救援分支、
     * lrSetPage、lrSyncPage 四处各写一次特判。现在它由语义锚
     * anchor='chapter-end' 表达，页码字段回归单一含义。
     *
     * 这条守卫盯着「哨兵别回来」：任何一处重新引入 restoreTarget < 0 的编码，
     * 都意味着页码字段又开始表达两件事。
     */
    @Test
    fun lastPageIsExpressedAsAChapterEndAnchorInsteadOfAPageSentinel() {
        val script = ReaderScripts.bootstrap(
            initialPage = 0,
            preferences = ReaderPreferences(),
            initialLocusAnchor = ReaderScripts.ANCHOR_CHAPTER_END
        )

        // 哨兵的所有痕迹都不许在
        assertFalse(script.contains("LR_LAST_PAGE"), "页码哨兵常量不该回来")
        assertFalse(script.contains("restoreTarget < 0"), "restoreTarget 不该再用负数表达语义")
        assertFalse(script.contains("page = pageCount - 1;"), "末页不该再由页码分支硬取")
        // 还原目标只剩普通页码一种含义
        assertContains(script, "let restoreTarget = Math.max(0, 0);")
        assertContains(script, "restoreTarget = Math.max(0, requested);")
        // 末页语义走锚点，并且每次重排都重新取（重新分页后仍停章末）
        assertContains(script, "anchor: \"chapter-end\"")
        assertContains(script, "if (locus.anchor === 'chapter-end') return Math.max(0, pageCount - 1);")
        val metrics = script.substring(script.indexOf("function updateMetrics("))
        assertTrue(
            metrics.indexOf("const anchoredPage = anchorLocus ? pagedPageForLocus(anchorLocus) : null;") <
                metrics.indexOf("page = clamp(page, 0, pageCount - 1);"),
            "锚点必须先于 clamp 决定页码"
        )
    }

    @Test
    fun preferenceSyncDoesNotOverwriteAPendingRestoreTarget() {
        val script = ReaderScripts.bootstrap(7, ReaderPreferences())

        // 改字号/行距/主题会走 lrSyncPage，它原本无条件按「当前渲染页」重新
        // 播种 restoreTarget。若还原尚未落地（测量变准前救援目标还没追上），
        // 那一下就把还原目标抹成当前的临时页，进度永久丢失。
        assertContains(script, "} else if (restoreTarget > page) {")
    }

    @Test
    fun ordinaryRestoredPageKeepsTheClampAndRescuePath() {
        val script = ReaderScripts.bootstrap(7, ReaderPreferences())

        // 普通页码的还原路径不受哨兵删除影响：仍然 clamp，仍然在测量变准后救回。
        assertContains(script, "let restoreTarget = Math.max(0, 7);")
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
