package com.linguareader.app.reader

import com.linguareader.app.SettingsStatus
import com.linguareader.app.ai.SentenceTranslationResult
import com.linguareader.app.data.DictionaryLookupResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阅读页位置/弹层状态机的回归测试。
 *
 * 这些迁移过去写在 ReaderScreen 主 composable 里（十几个裸 var），单测覆盖不到，
 * 于是「回翻落到上一章开头」「进滑动模式丢进度」这类问题只能靠真机复现。抽成
 * [ReaderPosition] / [ReaderOverlays] 后，每条都能在 JVM 上钉住。
 */
class ReaderScreenStateTest {

    private val chapterCount = 10

    private fun opened(chapter: Int = 3, page: Int = 5) =
        ReaderPosition.forBook(chapter = chapter, page = page, chapterCount = chapterCount)

    // ── M1 第 1 刀：语义锚点 ──────────────────────────────────────────

    @Test
    fun locusFromTheLibraryIsCarriedIntoTheInitialPosition() {
        val position = ReaderPosition.forBook(
            chapter = 2, page = 7, chapterCount = chapterCount,
            locusBlock = 41, locusOffset = 128
        )

        assertEquals(41, position.locusBlock)
        assertEquals(128, position.locusOffset)
        assertEquals(ReaderPosition.ANCHOR_EXACT, position.locusAnchor)
        // 页码仍带着：老书没有锚点时靠它落位
        assertEquals(7, position.restorePage)
    }

    @Test
    fun aFreshLocusMarksThePositionDirtySoItGetsPersisted() {
        val position = opened().withLocus(41, 128)

        assertTrue(position.dirty)
        val save = position.saveRequest(chapterCount)
        assertEquals(41, save?.locusBlock)
        assertEquals(128, save?.locusOffset)
    }

    @Test
    fun anIdenticalLocusDoesNotDirtyThePosition() {
        // 锚点每 250ms 取一次，同一位置反复取到相同值不该反复触发写盘。
        val position = opened().withLocus(41, 128).markSaved()

        assertFalse(position.withLocus(41, 128).dirty)
        assertTrue(position.withLocus(41, 200).dirty)
    }

    @Test
    fun anUnknownLocusIsIgnoredInsteadOfErasingTheCurrentOne() {
        val position = opened().withLocus(41, 128).markSaved()

        val after = position.withLocus(-1, 0)

        assertEquals(41, after.locusBlock)
        assertFalse(after.dirty)
    }

    @Test
    fun turningBackAChapterAnchorsAtChapterEndInsteadOfAPageNumber() {
        // 旧实现把「末页」塞进页码字段（Int.MAX_VALUE），首次测量 pageCount 偏小
        // 时被 clamp 拍成 0，回翻就落到上一章开头。现在它是一等语义锚。
        val position = opened().changeChapter(-1, chapterCount)!!

        assertEquals(ReaderPosition.ANCHOR_CHAPTER_END, position.locusAnchor)
        assertEquals(ReaderPosition.NO_LOCUS, position.locusBlock)
        assertEquals(ReaderPosition.ANCHOR_CHAPTER_END, position.saveRequest(chapterCount)?.locusAnchor ?: run {
            // 切章当下是干净的（还没渲染），先置脏再取
            position.copy(dirty = true).saveRequest(chapterCount)?.locusAnchor
        })
    }

    @Test
    fun forwardChapterChangeAnchorsAtChapterStart() {
        val position = opened().changeChapter(1, chapterCount)!!

        assertEquals(ReaderPosition.ANCHOR_CHAPTER_START, position.locusAnchor)
        assertEquals(ReaderPosition.NO_LOCUS, position.locusBlock)
    }

    // ── 位置：落盘脏标记 ──────────────────────────────────────────────

    @Test
    fun freshlyOpenedBookHasNothingToSave() {
        val position = opened()
        assertFalse(position.dirty)
        assertNull(position.saveRequest(chapterCount))
    }

    @Test
    fun renderingAChapterMarksProgressDirty() {
        val position = opened().onReady(page = 2, count = 8)
        assertTrue(position.dirty)
        val save = position.saveRequest(chapterCount)
        assertEquals(3, save?.chapter)
        assertEquals(2, save?.page)
    }

    @Test
    fun savingIsIdempotentUntilThePositionChangesAgain() {
        val position = opened().onPageChanged(page = 4, count = 9)
        assertTrue(position.saveRequest(chapterCount) != null)
        val saved = position.markSaved()
        // 800ms 节流轮询会反复调用：干净状态必须不再产出写盘请求。
        assertNull(saved.saveRequest(chapterCount))
        assertTrue(saved.onPageChanged(page = 5, count = 9).saveRequest(chapterCount) != null)
    }

    @Test
    fun theSaveSnapshotIsIndependentOfTheLiveScrollPosition() {
        // savedPage/savedCount 与 page/pageCount 分开存在的理由：落盘走节流，
        // 不能读到「刚切章还没渲染」的中途值。
        val position = opened().onReady(page = 6, count = 12)
        val save = position.saveRequest(chapterCount)
        assertEquals(position.savedPage, save?.page)
        assertEquals(6, save?.page)
    }

    // ── 位置：切章 ────────────────────────────────────────────────────

    @Test
    fun flippingBackIntoTheChapterUsesTheLastPageSentinel() {
        // 回翻必须交出哨兵而不是某个大页码：JS 侧把它翻译成 restoreTarget = -1，
        // 每次重排都重新取末页。用普通大页码会被首次测量（pageCount 偏小）拍成 0，
        // 于是回翻停在上一章开头 —— 这正是修过的那个 bug。
        val position = opened().selectChapter(2, chapterCount, fromEnd = true)!!
        assertEquals(ReaderScripts.LAST_PAGE, position.restorePage)
        assertEquals(2, position.chapter)
        assertEquals(0, position.page)
    }

    @Test
    fun jumpingForwardStartsAtTheTopOfTheChapter() {
        val position = opened().selectChapter(7, chapterCount)!!
        assertEquals(0, position.restorePage)
        assertEquals(0, position.page)
        assertFalse(position.scrollMode)
    }

    @Test
    fun switchingChapterDoesNotSaveTheUnrenderedFirstPage() {
        // 切章瞬间置脏会把「第 0 页」当成用户进度写进书库。
        val position = opened().onReady(page = 4, count = 9).markSaved()
            .selectChapter(4, chapterCount)!!
        assertFalse(position.dirty)
        assertNull(position.saveRequest(chapterCount))
    }

    @Test
    fun outOfRangeChapterSelectionIsRejected() {
        // null = 「什么都别做」：调用方据此跳过落盘与通知 TTS，等价于旧代码的提前 return。
        val position = opened()
        assertNull(position.selectChapter(-1, chapterCount))
        assertNull(position.selectChapter(chapterCount, chapterCount))
        assertNull(opened(chapter = 0).changeChapter(-1, chapterCount))
        assertNull(opened(chapter = chapterCount - 1).changeChapter(1, chapterCount))
    }

    @Test
    fun previousChapterLandsOnItsLastPageAndNextChapterOnItsFirst() {
        val position = opened(chapter = 4)
        assertEquals(ReaderScripts.LAST_PAGE, position.changeChapter(-1, chapterCount)!!.restorePage)
        assertEquals(0, position.changeChapter(1, chapterCount)!!.restorePage)
    }

    // ── 位置：滚动模式 ────────────────────────────────────────────────

    @Test
    fun scrollModeSurvivesAChapterFlipOnlyWhenAsked() {
        val scrolling = opened().onScrollModeChanged(true)
        val kept = scrolling.selectChapter(2, chapterCount, fromEnd = true, keepScrollMode = true)!!
        assertTrue(kept.scrollMode)
        // 滚动模式里回翻：比例落在章末，否则用户被扔回章首。
        assertEquals(1f, kept.scrollRatio)

        val dropped = scrolling.selectChapter(2, chapterCount, fromEnd = true)!!
        assertFalse(dropped.scrollMode)
        assertEquals(0f, dropped.scrollRatio)
    }

    @Test
    fun scrollProgressNeverRewritesTheRestoreTarget() {
        // 滚动模式的还原走比例；把换算页码写进还原目标，切回分页会把位置带偏。
        val position = opened().selectChapter(1, chapterCount, fromEnd = true)!!
            .onScrollProgress(ratio = .5f, page = 3, count = 6)
        assertEquals(ReaderScripts.LAST_PAGE, position.restorePage)
        assertEquals(.5f, position.scrollRatio)
        assertEquals(6, position.scrollPageCount)
        assertTrue(position.dirty)
    }

    // ── 位置：进度 ────────────────────────────────────────────────────

    @Test
    fun singlePageChapterContributesNoInChapterProgress() {
        val position = ReaderPosition.forBook(chapter = 5, page = 0, chapterCount = chapterCount)
        assertEquals(0.5f, position.progress(chapterCount))
    }

    @Test
    fun theLastPageOfTheLastChapterIsAlmostTheWholeBook() {
        val position = ReaderPosition.forBook(chapter = 9, page = 0, chapterCount = chapterCount)
            .onReady(page = 4, count = 5)
        assertEquals(1f, position.progress(chapterCount))
    }

    @Test
    fun progressIsSafeForAnEmptyBook() {
        assertEquals(0f, ReaderPosition.forBook(0, 0, 0).progress(0))
    }

    // ── 弹层：返回键优先级 ────────────────────────────────────────────

    @Test
    fun backClosesTheLookupSheetBeforeAnythingElse() {
        val overlays = ReaderOverlays(settings = true, contents = true, pageJump = true)
        val result = overlays.onBack(lookupOpen = true)
        assertEquals(ReaderBackAction.DismissLookup, result.action)
        // 查词弹层的开关不在这里，弹层状态必须原样返回。
        assertEquals(overlays, result.overlays)
    }

    @Test
    fun backPeelsSheetsOneAtATimeInAFixedOrder() {
        val all = ReaderOverlays(settings = true, contents = true, pageJump = true)
        val afterSettings = all.onBack(lookupOpen = false)
        assertEquals(ReaderBackAction.CloseSheet, afterSettings.action)
        assertFalse(afterSettings.overlays.settings)
        assertTrue(afterSettings.overlays.contents)

        val afterContents = afterSettings.overlays.onBack(lookupOpen = false)
        assertFalse(afterContents.overlays.contents)
        assertTrue(afterContents.overlays.pageJump)

        val afterPageJump = afterContents.overlays.onBack(lookupOpen = false)
        assertFalse(afterPageJump.overlays.pageJump)

        assertEquals(
            ReaderBackAction.LeaveReader,
            afterPageJump.overlays.onBack(lookupOpen = false).action
        )
    }

    // ── 弹层：关闭前的到期词拦截 ──────────────────────────────────────

    @Test
    fun closingWithDueWordsShowsThePromptInsteadOfLeaving() {
        val decision = ReaderOverlays().requestClose(hasDueWords = true, pausePrompt = true)
        assertTrue(decision.promptReview)
        assertTrue(decision.overlays.reviewPrompt)
        assertTrue(decision.overlays.pendingClose)
    }

    @Test
    fun dismissingThePromptCompletesTheCloseItInterrupted() {
        val intercepted = ReaderOverlays()
            .requestClose(hasDueWords = true, pausePrompt = true).overlays
        val dismissal = intercepted.dismissReviewPrompt()
        assertTrue(dismissal.leaveReader)
        assertFalse(dismissal.overlays.reviewPrompt)
        assertFalse(dismissal.overlays.pendingClose)
    }

    @Test
    fun dismissingAPromptNobodyWaitedOnDoesNotCloseTheBook() {
        // 翻章触发的提示条（没有 pendingClose）被划掉时不能顺手退出阅读器。
        val banner = ReaderOverlays(reviewPrompt = true)
        assertFalse(banner.dismissReviewPrompt().leaveReader)
    }

    @Test
    fun theSecondCloseRequestIsNotInterceptedAgain() {
        val intercepted = ReaderOverlays()
            .requestClose(hasDueWords = true, pausePrompt = true).overlays
        val second = intercepted.requestClose(hasDueWords = true, pausePrompt = true)
        assertFalse(second.promptReview)
        assertFalse(second.overlays.reviewPrompt)
    }

    @Test
    fun closingWithoutDueWordsOrWithTheReminderOffLeavesImmediately() {
        assertFalse(
            ReaderOverlays().requestClose(hasDueWords = false, pausePrompt = true).promptReview
        )
        assertFalse(
            ReaderOverlays().requestClose(hasDueWords = true, pausePrompt = false).promptReview
        )
    }

    @Test
    fun startingReviewFromThePromptCancelsThePendingClose() {
        val intercepted = ReaderOverlays()
            .requestClose(hasDueWords = true, pausePrompt = true).overlays
        val reviewing = intercepted.startReviewFromPrompt()
        assertFalse(reviewing.pendingClose)
        assertFalse(reviewing.reviewPrompt)
    }

    @Test
    fun toolbarTogglesBothWays() {
        val hidden = ReaderOverlays().toggleToolbar()
        assertFalse(hidden.toolbarVisible)
        assertTrue(hidden.toggleToolbar().toolbarVisible)
    }
}

/**
 * 查词会话的回归测试。核心语义是「开一次新查词到底归零什么」与句翻缓存上限：
 * 这些过去靠 LaunchedEffect 里手写的 12 行赋值维持，漏清一个字段就会把上一次
 * 查词的 AI 结果/失败信号串到下一个词上。
 */
class ReaderLookupSessionTest {

    private val fakeEntry = DictionaryLookupResult(entry = null, relatedPhrase = null)

    private fun result(text: String) = SentenceTranslationResult(text = text, provider = "test")

    @Test
    fun startingANewLookupClearsThePreviousSession() {
        val dirty = ReaderLookupSession.empty()
            .copy(
                aiResult = null,
                aiFailed = true,
                aiRemoteFailed = true,
                translation = null,
                sentenceTranslationError = "boom",
                sentenceTranslationLoading = true,
                retranslateLoading = true,
                retranslateDoneTick = 7,
                status = SettingsStatus.info("已收藏"),
                showingRelatedPhrase = true
            )
        val begun = dirty.begin(
            dictionaryResult = fakeEntry,
            hasTranslation = false,
            sentence = "No such word in cache"
        )
        assertFalse(begun.aiFailed)
        assertFalse(begun.aiRemoteFailed)
        assertNull(begun.sentenceTranslation)
        assertNull(begun.sentenceTranslationError)
        assertFalse(begun.sentenceTranslationLoading)
        assertFalse(begun.retranslateLoading)
        // retranslateDoneTick 是单调递增信号，开新查词不归零（见 ReaderLookupSession.begin）。
        assertEquals(7, begun.retranslateDoneTick)
        assertNull(begun.status)
        assertFalse(begun.showingRelatedPhrase)
    }

    @Test
    fun startingANewLookupRestoresACachedSentenceTranslation() {
        val cache = SentenceTranslationCache()
        cache.put("He stayed.", result("他留下了。"))
        val session = ReaderLookupSession.empty(cache).begin(
            dictionaryResult = fakeEntry,
            hasTranslation = false,
            sentence = "  He stayed.  "
        )
        assertEquals("他留下了。", session.sentenceTranslation?.text)
        assertNull(session.sentenceTranslationError)
    }

    @Test
    fun aFailedAiLookupKeepsTheFailureVisibleButStopsTheSpinner() {
        val session = ReaderLookupSession.empty().begin(
            dictionaryResult = fakeEntry,
            hasTranslation = false,
            sentence = "x"
        ).copy(aiLoading = true)
        val failed = session.markAiFailed()
        assertTrue(failed.aiFailed)
        assertFalse(failed.aiLoading)
    }

    @Test
    fun theSentenceCacheEvictsEverythingOnceItHitsTheLimit() {
        val cache = SentenceTranslationCache(capacity = 2)
        cache.put("one", result("1"))
        cache.put("two", result("2"))
        assertEquals(2, cache.size)
        // 塞满后下一次 put 先整体清空，再放进新条目——与旧逻辑「>= 上限就 clear」一致。
        cache.put("three", result("3"))
        assertEquals(1, cache.size)
        assertNull(cache["one"])
        assertEquals("3", cache["three"]?.text)
    }

    @Test
    fun cachingASentenceSetsTheTranslationAndStopsLoading() {
        val session = ReaderLookupSession.empty()
            .begin(dictionaryResult = fakeEntry, hasTranslation = false, sentence = "s")
            .beginSentenceTranslation()
        assertTrue(session.sentenceTranslationLoading)

        val cached = session.cacheSentence("The dog runs.", result("狗在跑。"))
        assertFalse(cached.sentenceTranslationLoading)
        assertEquals("狗在跑。", cached.sentenceTranslation?.text)
        assertNull(cached.sentenceTranslationError)
    }
}
