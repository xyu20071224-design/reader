package com.linguareader.app.data

import com.linguareader.shared.data.SavedWord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * The repository already serializes file transactions. The old AppViewModel releases that lock
 * before dispatching a full snapshot to Main. Holding exactly that first publication reproduces
 * stale lookup/load snapshots deterministically, without sleeps or racing operating-system IO.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VocabularyStateTransactionsTest {
    private class PublicationDispatcher(scheduler: TestCoroutineScheduler, private var holdNext: Boolean) : CoroutineDispatcher() {
        private val delegate = StandardTestDispatcher(scheduler, "publication")
        private var held: Pair<CoroutineContext, Runnable>? = null
        private val marker = ThreadLocal<Boolean>()
        val hasHeldPublication get() = held != null
        val isPublishing get() = marker.get() == true
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (holdNext) { holdNext = false; held = context to block }
            else delegate.dispatch(context, wrapped(block))
        }
        private fun wrapped(block: Runnable) = Runnable {
            val old = marker.get(); marker.set(true)
            try { block.run() } finally { marker.set(old) }
        }
        fun release() {
            val (context, block) = requireNotNull(held) { "No pending publication" }
            held = null
            delegate.dispatch(context, wrapped(block))
        }
    }
    private data class UiState(val savedWords: List<SavedWord>)
    private fun word(id: String = "go") = SavedWord(id = id, headword = id, phonetic = "", meaning = "old meaning",
        sentence = "old context", bookId = "b", bookTitle = "B", chapterTitle = "C", addedAt = 1L,
        reviewCount = 7, reviewLevel = 4, nextReviewAt = 99_000L)

    private class Harness(scope: TestScope, words: List<SavedWord>, hold: Boolean = true) {
        val io = StandardTestDispatcher(scope.testScheduler, "io")
        val publication = PublicationDispatcher(scope.testScheduler, hold)
        val transactions = VocabularyStateTransactions(io, publication)
        var disk = words
        private val state = MutableStateFlow(UiState(words))
        val ui get() = state.value.savedWords
        suspend fun execute(transaction: suspend () -> List<SavedWord>): List<SavedWord> = transactions.withLock(
            transaction = transaction,
            publish = { snapshot -> state.update { it.copy(savedWords = snapshot) } }
        )
    }

    @Test fun lateLookupSnapshotCannotResurrectARemovedWord() = runTest {
        val h = Harness(this, listOf(word()))
        val lookup = launch { h.execute { h.disk.toList() } }
        testScheduler.runCurrent()
        assertTrue(h.publication.hasHeldPublication)
        val remove = launch { h.execute { h.disk = emptyList(); h.disk } }
        testScheduler.runCurrent()
        h.publication.release()
        testScheduler.advanceUntilIdle()
        lookup.join(); remove.join()
        assertTrue(h.disk.isEmpty())
        assertEquals("A late lookup publication resurrected the deleted word", h.disk, h.ui)
    }

    @Test fun lateLookupSnapshotCannotRollBackANewerReview() = runTest {
        val original = word()
        val h = Harness(this, listOf(original))
        val lookup = launch { h.execute { h.disk.toList() } }
        testScheduler.runCurrent()
        val review = launch { h.execute {
            h.disk = listOf(original.copy(reviewCount = 8, reviewLevel = 5, nextReviewAt = 199_000L)); h.disk
        } }
        testScheduler.runCurrent()
        h.publication.release()
        testScheduler.advanceUntilIdle()
        lookup.join(); review.join()
        assertEquals(8, h.disk.single().reviewCount)
        assertEquals("The UI lost the persisted review count and next date", h.disk, h.ui)
    }

    @Test fun lateRefreshLoadCannotDropANewlySavedWord() = runTest {
        val h = Harness(this, listOf(word()))
        val refresh = launch { h.execute { h.disk.toList() } }
        testScheduler.runCurrent()
        val save = launch { h.execute { h.disk = h.disk + word("study"); h.disk } }
        testScheduler.runCurrent()
        h.publication.release()
        testScheduler.advanceUntilIdle()
        refresh.join(); save.join()
        assertEquals(setOf("go", "study"), h.disk.map { it.id }.toSet())
        assertEquals("A refresh snapshot dropped the newly saved word", h.disk, h.ui)
    }

    @Test fun lateImportLoadCannotEraseAnObservedSurface() = runTest {
        val h = Harness(this, listOf(word()))
        val importLoad = launch { h.execute { h.disk.toList() } }
        testScheduler.runCurrent()
        val observe = launch { h.execute { h.disk = h.disk.map { it.copy(surfaceForms = listOf("went")) }; h.disk } }
        testScheduler.runCurrent()
        h.publication.release()
        testScheduler.advanceUntilIdle()
        importLoad.join(); observe.join()
        assertEquals(listOf("went"), h.disk.single().surfaceForms)
        assertEquals("The imported-library load discarded the observed surface in memory", h.disk, h.ui)
    }

    @Test fun ioFailureIsPropagatedWithoutPublishingAndDoesNotBlockTheNextTransaction() = runTest {
        val h = Harness(this, listOf(word()), hold = false)
        val failure = IOException("fixture write failure")
        val caught = runCatching { h.execute { throw failure } }.exceptionOrNull()
        // Coroutine stack recovery can copy an exception; type/message propagation is the contract.
        assertTrue(caught is IOException)
        assertEquals(failure.message, caught?.message)
        assertEquals(listOf(word()), h.ui)
        h.execute { emptyList() }
        assertTrue(h.ui.isEmpty())
    }

    @Test fun ioAndPublicationUseTheirDispatchersAndTheReturnFollowsPublication() = runTest {
        val h = Harness(this, emptyList(), hold = false)
        var published = false
        val result = h.transactions.withLock(transaction = {
            assertSame(h.io, kotlinx.coroutines.currentCoroutineContext()[ContinuationInterceptor])
            listOf(word())
        }, publish = {
            assertTrue("Publication did not run on the configured Main dispatcher", h.publication.isPublishing)
            published = true
        })
        assertTrue(published)
        assertEquals(listOf(word()), result)
    }

    @Test fun cancellingAQueuedTransactionDoesNotRunItsIoOrPublish() = runTest {
        val h = Harness(this, emptyList(), hold = false)
        val release = CompletableDeferred<Unit>()
        val first = launch { h.execute { release.await(); emptyList() } }
        testScheduler.runCurrent()
        var queuedIo = 0
        var queuedPublish = 0
        val queued = launch { h.transactions.withLock({ queuedIo++; emptyList<SavedWord>() }, { queuedPublish++ }) }
        testScheduler.runCurrent()
        queued.cancelAndJoin()
        release.complete(Unit)
        testScheduler.advanceUntilIdle(); first.join()
        assertTrue(queued.isCancelled)
        assertEquals(0, queuedIo)
        assertEquals(0, queuedPublish)
    }

    @Test fun cancellingDuringIoPropagatesAndReleasesTheTransaction() = runTest {
        val h = Harness(this, emptyList(), hold = false)
        val release = CompletableDeferred<Unit>()
        var published = false
        val cancelled = launch { h.transactions.withLock({ release.await() }, { published = true }) }
        testScheduler.runCurrent()
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        assertFalse(published)
        h.execute { listOf(word()) }
        assertEquals(listOf(word()), h.ui)
    }

    @Test fun cancellationAfterCommittedIoDoesNotPublishAnOldResultOrDeadlockTheNextRead() = runTest {
        val h = Harness(this, emptyList())
        val cancelled = launch { h.execute { h.disk = listOf(word()); h.disk } }
        testScheduler.runCurrent()
        assertTrue(h.publication.hasHeldPublication)
        cancelled.cancel()
        h.publication.release()
        testScheduler.advanceUntilIdle(); cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertTrue(h.ui.isEmpty())
        h.execute { h.disk.toList() }
        assertEquals(h.disk, h.ui)
    }
}
