package com.linguareader.app.reader

import com.linguareader.app.data.ReaderPreferences
import com.linguareader.app.data.ReaderTheme
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ReaderScriptsTest {
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
}
