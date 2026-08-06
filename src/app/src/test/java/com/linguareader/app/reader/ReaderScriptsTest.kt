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
}