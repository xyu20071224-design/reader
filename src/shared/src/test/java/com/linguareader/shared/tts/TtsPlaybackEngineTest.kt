package com.linguareader.shared.tts

import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterizes the pure [TtsPlaybackEngine] state machine with scripted fake
 * synthesizers: the whole queue (play/pause/skip/chapter/fallback) is driven
 * without any Android runtime. Mirrors Readest's tts-fake-audio approach.
 */
class TtsPlaybackEngineTest {

    private data class Spoken(
        val text: String,
        val rate: Float,
        val utteranceId: String,
        val voice: String? = null
    )

    private class FakeTtsSynthesizer(
        val listener: TtsSynthesizerListener,
        override val isReady: Boolean = true
    ) : TtsSynthesizer {
        val spoken = mutableListOf<Spoken>()
        var stopped = 0
        var shutdown = 0
        override fun speak(text: String, rate: Float, utteranceId: String, voice: String?) {
            spoken += Spoken(text, rate, utteranceId, voice)
        }
        override fun stop() { stopped++ }
        override fun shutdown() { shutdown++ }
        fun emitStart(id: String) = listener.onStart(id)
        fun emitDone(id: String) = listener.onDone(id)
        fun emitError(id: String) = listener.onError(id)
        fun emitReady() = listener.onReady()
        fun emitInitFailed(status: Int) = listener.onInitFailed(status)
    }

    private class FakeCloudTtsSynthesizer(
        val listener: TtsSynthesizerListener,
        override val isReady: Boolean = true,
        var prepareResult: Boolean = true
    ) : TtsSynthesizer, ChapterTtsPreparer {
        val spoken = mutableListOf<Spoken>()
        var stopped = 0
        var shutdown = 0
        var prepareCalls = 0
        override fun speak(text: String, rate: Float, utteranceId: String, voice: String?) {
            spoken += Spoken(text, rate, utteranceId, voice)
        }
        override fun stop() { stopped++ }
        override fun shutdown() { shutdown++ }
        override fun prepareChapter(
            book: Book,
            chapter: TtsChapter,
            onProgress: (Int, Int) -> Unit,
            onComplete: (Boolean) -> Unit
        ) {
            prepareCalls++
            onProgress(chapter.sentenceCount, chapter.sentenceCount)
            onComplete(prepareResult)
        }
        fun emitStart(id: String) = listener.onStart(id)
        fun emitDone(id: String) = listener.onDone(id)
        fun emitError(id: String) = listener.onError(id)
    }

    private class Harness(
        dispatcher: TestDispatcher,
        factory: (TtsSynthesizerListener) -> TtsSynthesizer,
        isSystem: (TtsSynthesizer) -> Boolean,
        chapters: suspend (Book, Int) -> TtsChapter,
        readerLoaded: (String) -> Int? = { null },
        voiceForSpeaker: (String, String) -> String? = { _, _ -> null },
        fallbackFactory: ((TtsSynthesizerListener) -> TtsSynthesizer)? = null
    ) {
        val synthesizers = mutableListOf<TtsSynthesizer>()
        private val recordingFactory: (TtsSynthesizerListener) -> TtsSynthesizer = { l ->
            factory(l).also { synthesizers += it }
        }
        val fallbackSynthesizers = mutableListOf<TtsSynthesizer>()
        private val recordingFallbackFactory: ((TtsSynthesizerListener) -> TtsSynthesizer)? =
            fallbackFactory?.let { f ->
                { l -> f(l).also { fallbackSynthesizers += it } }
            }
        val chapterRequests = mutableListOf<Int>()
        val progressSaves = mutableListOf<Triple<String, Int, Int>>()
        val states = mutableListOf<TtsPlaybackState>()
        val engine = TtsPlaybackEngine(
            synthesizerFactory = recordingFactory,
            chapterLoader = chapters,
            isSystemEngine = isSystem,
            onChapterRequest = { chapterRequests += it },
            onBookSwitched = {},
            onProgressSave = { b, c, s -> progressSaves += Triple(b.id, c, s) },
            onState = { states += it },
            dispatcher = dispatcher,
            readerLoadedChapterFor = readerLoaded,
            fallbackSynthesizerFactory = recordingFallbackFactory,
            voiceForSpeaker = voiceForSpeaker
        )
        val state: TtsPlaybackState get() = states.last()
        val synthesizer: TtsSynthesizer get() = synthesizers.first()
    }

    private fun book(id: String = "b1", chapterCount: Int = 1): Book = Book(
        id = id,
        title = "Test",
        author = "",
        extractedDir = "/tmp",
        coverRelativePath = null,
        chapters = (0 until chapterCount).map { Chapter("Ch" + it, "ch" + it + ".html") },
        addedAt = 0L
    )

    private fun chapter(vararg sentences: String, chapterIndex: Int = 0): TtsChapter =
        TtsChapter(chapterIndex, "Ch" + chapterIndex, sentences.toList())

    private fun plainHarness(
        dispatcher: TestDispatcher,
        chapters: suspend (Book, Int) -> TtsChapter,
        readerLoaded: (String) -> Int? = { null }
    ): Harness = Harness(
        dispatcher = dispatcher,
        factory = { l -> FakeTtsSynthesizer(l) },
        isSystem = { it is FakeTtsSynthesizer },
        chapters = chapters,
        readerLoaded = readerLoaded
    )

    @Test
    fun playsFirstSentenceAndHighlights() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("Hello.", "World.") })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer

        assertEquals(1, fake.spoken.size)
        assertEquals("Hello.", fake.spoken[0].text)
        assertEquals(1f, fake.spoken[0].rate)
        assertEquals("b1:0:0:1", fake.spoken[0].utteranceId)
        assertEquals("Hello.", h.state.currentSentence)
        assertTrue(h.state.isPlaying)
        assertEquals(0, h.state.highlightBlockIndex)
        assertEquals(0, h.state.highlightOffset)
        assertEquals("Hello.".length, h.state.highlightLength)
        h.engine.shutdown()
    }

    @Test
    fun appliesRefinedSpeakerTagsMidPlayback() = runTest {
        // M2: the LLM tagger answers after playback started; the refined tags
        // are applied to the loaded chapter without touching the queue, so the
        // next sentence is spoken with its character voice.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loaded = TtsChapter(
            0, "Ch0",
            blocks = listOf("She nodded. \"Yes,\" she said."),
            speakers = listOf("narrator", "dialogue")
        )
        val h = Harness(
            dispatcher = dispatcher,
            factory = { l -> FakeTtsSynthesizer(l) },
            isSystem = { it is FakeTtsSynthesizer },
            chapters = { _, _ -> loaded },
            voiceForSpeaker = { speaker, _ ->
                when (speaker) {
                    "narrator" -> "af_maple"
                    "Gandalf" -> "am_onyx"
                    else -> "af_sol"
                }
            }
        )
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals("af_maple", fake.spoken[0].voice)

        h.engine.applySpeakerTags("b1", 0, listOf("narrator", "Gandalf"))
        testScheduler.advanceUntilIdle()
        fake.emitStart("b1:0:0:1")
        fake.emitDone("b1:0:0:1")
        testScheduler.advanceUntilIdle()

        assertEquals(2, fake.spoken.size)
        assertEquals("am_onyx", fake.spoken[1].voice)
        assertEquals("Gandalf", h.engine.currentChapter?.speakerAt(1))
        h.engine.shutdown()
    }

    @Test
    fun ignoresStaleOrMismatchedSpeakerTags() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loaded = TtsChapter(
            0, "Ch0",
            blocks = listOf("She nodded. \"Yes,\" she said."),
            speakers = listOf("narrator", "dialogue")
        )
        val h = plainHarness(dispatcher, { _, _ -> loaded })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()

        // Another book, another chapter, and a list of the wrong length: all
        // dropped, so voices can never shift onto the wrong sentences.
        h.engine.applySpeakerTags("other", 0, listOf("Gandalf", "Gandalf"))
        h.engine.applySpeakerTags("b1", 3, listOf("Gandalf", "Gandalf"))
        h.engine.applySpeakerTags("b1", 0, listOf("Gandalf"))
        testScheduler.advanceUntilIdle()

        assertEquals("dialogue", h.engine.currentChapter?.speakerAt(1))
        h.engine.shutdown()
    }

    @Test
    fun passesPerSpeakerVoiceToSynthesizer() = runTest {
        // M1 multi-voice: narrator sentences get the narrator voice, dialogue
        // sentences get the dialogue voice, both via the injected resolver.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tagged = TtsChapter(
            0, "Ch0",
            blocks = listOf("She nodded. \"Yes,\" she said."),
            speakers = listOf("narrator", "dialogue")
        )
        val h = Harness(
            dispatcher = dispatcher,
            factory = { l -> FakeTtsSynthesizer(l) },
            isSystem = { it is FakeTtsSynthesizer },
            chapters = { _, _ -> tagged },
            voiceForSpeaker = { speaker, _ ->
                if (speaker == "narrator") "af_maple" else "af_sol"
            }
        )
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer

        assertEquals(1, fake.spoken.size)
        assertEquals("af_maple", fake.spoken[0].voice)
        fake.emitStart("b1:0:0:1")
        fake.emitDone("b1:0:0:1")
        testScheduler.advanceUntilIdle()
        assertEquals(2, fake.spoken.size)
        assertEquals("af_sol", fake.spoken[1].voice)
        h.engine.shutdown()
    }

    @Test
    fun noVoicePassedWhenResolverDisabled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("Hello.", "World.") })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertNull(fake.spoken[0].voice)
        h.engine.shutdown()
    }

    @Test
    fun advancesSentencesUntilFinishAndSavesProgress() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("A.", "B.", "C.") })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer

        assertEquals("A.", fake.spoken.last().text)
        fake.emitDone(fake.spoken.last().utteranceId)
        testScheduler.advanceUntilIdle()
        assertEquals("B.", fake.spoken.last().text)

        fake.emitDone(fake.spoken.last().utteranceId)
        testScheduler.advanceUntilIdle()
        assertEquals("C.", fake.spoken.last().text)

        fake.emitDone(fake.spoken.last().utteranceId)
        testScheduler.advanceUntilIdle()
        assertEquals(3, fake.spoken.size)
        assertNull(h.state.bookId)
        assertEquals(Triple("b1", 0, 2), h.progressSaves.last())
        h.engine.shutdown()
    }

    @Test
    fun pauseStopsThenResumeRespeaksCurrent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("A.", "B.") })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals(1, fake.spoken.size)

        h.engine.pause()
        assertFalse(h.state.isPlaying)
        assertTrue(fake.stopped >= 1)

        h.engine.resume()
        testScheduler.advanceUntilIdle()
        assertEquals(2, fake.spoken.size)
        assertEquals("A.", fake.spoken.last().text)
        assertTrue(h.state.isPlaying)
        h.engine.shutdown()
    }

    @Test
    fun nextAndPreviousSkipSentences() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("A.", "B.", "C.") })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals("A.", fake.spoken.last().text)

        h.engine.next()
        testScheduler.advanceUntilIdle()
        assertEquals("B.", fake.spoken.last().text)

        h.engine.previous()
        testScheduler.advanceUntilIdle()
        assertEquals("A.", fake.spoken.last().text)
        h.engine.shutdown()
    }

    @Test
    fun previousAtChapterStartLoadsPreviousChapterLastSentence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val chapters: suspend (Book, Int) -> TtsChapter = { _, i ->
            if (i == 0) chapter("X.", "Y.", chapterIndex = 0)
            else chapter("Z.", chapterIndex = 1)
        }
        val h = plainHarness(dispatcher, chapters)
        h.engine.startPlayback(book(chapterCount = 2), 1, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals("Z.", fake.spoken.last().text)

        h.engine.previous()
        testScheduler.advanceUntilIdle()
        assertEquals("Y.", fake.spoken.last().text)
        h.engine.shutdown()
    }

    @Test
    fun autoAdvancesToNextChapter() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val chapters: suspend (Book, Int) -> TtsChapter = { _, i ->
            if (i == 0) chapter("X.", chapterIndex = 0) else chapter("Y.", chapterIndex = 1)
        }
        val h = plainHarness(dispatcher, chapters)
        h.engine.startPlayback(book(chapterCount = 2), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals("X.", fake.spoken.last().text)

        fake.emitDone(fake.spoken.last().utteranceId)
        testScheduler.advanceUntilIdle()
        assertEquals("Y.", fake.spoken.last().text)
        h.engine.shutdown()
    }

    @Test
    fun cloudPrepareSuccessDoesNotFallback() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val created = mutableListOf<TtsSynthesizer>()
        val factory: (TtsSynthesizerListener) -> TtsSynthesizer = { l ->
            FakeCloudTtsSynthesizer(l, prepareResult = true).also { created += it }
        }
        val h = Harness(
            dispatcher = dispatcher,
            factory = factory,
            isSystem = { it is FakeTtsSynthesizer },
            chapters = { _, _ -> chapter("A.", "B.") },
        )
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()

        assertEquals(1, created.size)
        val cloud = created[0] as FakeCloudTtsSynthesizer
        assertEquals(1, cloud.prepareCalls)
        assertEquals(1, cloud.spoken.size)
        assertEquals("A.", cloud.spoken[0].text)
        h.engine.shutdown()
    }

    @Test
    fun cloudPrepareFailureFallsBackToSystemEngine() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(
            dispatcher = dispatcher,
            factory = { l -> FakeCloudTtsSynthesizer(l, prepareResult = false) },
            isSystem = { it is FakeTtsSynthesizer },
            chapters = { _, _ -> chapter("A.") },
            fallbackFactory = { l -> FakeTtsSynthesizer(l) },
        )
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()

        // BUG-001: the settings factory must never be asked for a second cloud
        // engine — the fallback comes from fallbackSynthesizerFactory instead.
        // Rebuilding via the settings factory recreated the same broken cloud
        // engine (and billed for it) in an endless loop.
        assertEquals(1, h.synthesizers.size)
        assertEquals(1, h.fallbackSynthesizers.size)
        val cloud = h.synthesizers.first() as FakeCloudTtsSynthesizer
        val system = h.fallbackSynthesizers.first() as FakeTtsSynthesizer
        assertTrue(cloud.spoken.isEmpty())
        assertTrue(system.spoken.isNotEmpty())
        assertEquals("A.", system.spoken.first().text)
        h.engine.shutdown()
    }

    @Test
    fun alreadyLoadedChapterSpeaksWithoutHandshake() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(
            dispatcher,
            { _, _ -> chapter("A.") },
            readerLoaded = { id -> if (id == "b1") 0 else null },
        )
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer

        assertEquals(1, fake.spoken.size)
        assertTrue(h.chapterRequests.isEmpty())
        h.engine.shutdown()
    }

    @Test
    fun unconfirmedChapterSpeaksAfterGraceTimeout() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("A.") })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.runCurrent()

        assertEquals(listOf(0), h.chapterRequests)
        assertTrue((h.synthesizer as FakeTtsSynthesizer).spoken.isEmpty())

        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals(1, fake.spoken.size)
        assertEquals("A.", fake.spoken[0].text)
        h.engine.shutdown()
    }

    @Test
    fun errorAdvancesToNextSentence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("A.", "B.") })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals("A.", fake.spoken.last().text)

        fake.emitError(fake.spoken.last().utteranceId)
        testScheduler.advanceUntilIdle()
        assertEquals("B.", fake.spoken.last().text)
        assertTrue(h.state.isPlaying)
        h.engine.shutdown()
    }

    @Test
    fun repeatedErrorsPauseAfterThreshold() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sentences = (0 until 30).map { "S" + it + "." }.toTypedArray()
        val h = plainHarness(dispatcher, { _, _ -> chapter(*sentences) })
        h.engine.startPlayback(book(), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer

        repeat(25) {
            fake.emitError(fake.spoken.last().utteranceId)
            testScheduler.advanceUntilIdle()
        }
        assertFalse(h.state.isPlaying)
        h.engine.shutdown()
    }

    // ── 阅读器入口的特征化测试 ─────────────────────────────────────────
    // M0 安全网原本钉住三个入口的当时行为，其中
    // readerPositionChangePullsPlaybackToBlockFirstSentence_BUG034 是故意记录
    // 缺陷现状的。第 2 刀（2026-09-01，方案 §8.1 拍板 (b)）把「翻页拽动朗读」
    // 整条路径删除 —— 契约反转成「页面跟朗读」，那三条用例连同 API 一起作废。
    // 剩下的两个入口（章节加载握手 / 换章）仍在，覆盖在下面。

    /** 一个块两句，方便区分「块首句」与「块内其它句」。 */
    private fun twoBlockChapter(): TtsChapter = TtsChapter(
        chapterIndex = 0,
        title = "Ch0",
        blocks = listOf("A one. A two.", "B one. B two.")
    )

    @Test
    fun playbackKeepsItsSentenceWhenTheReaderMovesWithinTheSameChapter() = runTest {
        // BUG-034 的根除断言：阅读器在章内怎么翻都不该动朗读队列。引擎已经不再
        // 暴露任何「按阅读位置重定位」的入口，这里守的是「换章之外没有第二条
        // 能拽动 sentenceIndex 的路」——将来若有人再加回来，这条会先红。
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> twoBlockChapter() })
        h.engine.startPlayback(book(), 0, 1) // 正在念 "A two."（块 0 的第 2 句）
        testScheduler.advanceUntilIdle()
        assertEquals("A two.", h.state.currentSentence)

        // 章节加载握手（阅读器渲染完同一章）不得改变朗读位置
        h.engine.onReaderChapterLoaded("b1", 0)
        testScheduler.advanceUntilIdle()

        assertEquals(1, h.state.sentenceIndex)
        assertEquals("A two.", h.state.currentSentence)
        h.engine.shutdown()
    }

    @Test
    fun readerChapterSelectedResetsToChapterStartAndSavesProgress() = runTest {
        // TtsPlaybackEngine.kt:429-442：手动切章无条件把句索引清零并排期落盘。
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, index -> chapter("S0.", "S1.", chapterIndex = index) })
        h.engine.startPlayback(book(chapterCount = 3), 0, 1)
        testScheduler.advanceUntilIdle()
        assertEquals(1, h.state.sentenceIndex)

        h.engine.onReaderChapterSelected("b1", 2)
        testScheduler.advanceUntilIdle()

        assertEquals(2, h.state.chapterIndex)
        assertEquals(0, h.state.sentenceIndex)
        assertTrue(h.progressSaves.contains(Triple("b1", 2, 0)))
        h.engine.shutdown()
    }

    @Test
    fun readerChapterSelectedIsIgnoredForAnotherBook() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, index -> chapter("S0.", chapterIndex = index) })
        h.engine.startPlayback(book(chapterCount = 3), 0, 0)
        testScheduler.advanceUntilIdle()

        h.engine.onReaderChapterSelected("other-book", 2)
        testScheduler.advanceUntilIdle()

        assertEquals(0, h.state.chapterIndex)
        h.engine.shutdown()
    }

    @Test
    fun readerChapterLoadedCompletesTheHandshakeOnlyForTheAwaitedChapter() = runTest {
        // TtsPlaybackEngine.kt:411-419：只认「等的就是这一章」的回执。
        // 观察口径：握手完成前引擎停在旧章；回执到位后才继续念新章。
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, index -> chapter("C" + index + "-0.", "C" + index + "-1.", chapterIndex = index) })
        h.engine.startPlayback(book(chapterCount = 2), 0, 0)
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer

        // 念完第 0 章两句 → 引擎请求切到第 1 章并等待阅读器回执
        fake.emitStart(fake.spoken.last().utteranceId)
        fake.emitDone(fake.spoken.last().utteranceId)
        testScheduler.advanceUntilIdle()
        fake.emitStart(fake.spoken.last().utteranceId)
        fake.emitDone(fake.spoken.last().utteranceId)
        testScheduler.runCurrent()

        assertTrue(h.chapterRequests.contains(1))
        h.engine.onReaderChapterLoaded("b1", 0)   // 不是在等的那一章：应被忽略
        h.engine.onReaderChapterLoaded("other", 1) // 书不符：应被忽略
        h.engine.onReaderChapterLoaded("b1", 1)   // 正主
        testScheduler.advanceUntilIdle()

        assertEquals(1, h.state.chapterIndex)
        h.engine.shutdown()
    }

    @Test
    fun standbyDoesNotSpeakUntilResume() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = plainHarness(dispatcher, { _, _ -> chapter("A.") })
        h.engine.startStandby(book(), 0)
        testScheduler.advanceUntilIdle()

        assertFalse(h.state.isPlaying)
        assertTrue(h.synthesizers.isEmpty())

        h.engine.resume()
        testScheduler.advanceUntilIdle()
        val fake = h.synthesizer as FakeTtsSynthesizer
        assertEquals(1, fake.spoken.size)
        assertEquals("A.", fake.spoken[0].text)
        h.engine.shutdown()
    }
}

