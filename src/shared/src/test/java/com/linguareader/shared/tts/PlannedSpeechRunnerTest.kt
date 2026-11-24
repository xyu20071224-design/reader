package com.linguareader.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlannedSpeechRunnerTest {
    private class Listener : TtsSynthesizerListener {
        val starts = mutableListOf<String>()
        val dones = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val ranges = mutableListOf<Triple<Int, Int, Int>>()
        override fun onReady() = Unit
        override fun onInitFailed(status: Int) = Unit
        override fun onStart(utteranceId: String) { starts += utteranceId }
        override fun onDone(utteranceId: String) { dones += utteranceId }
        override fun onError(utteranceId: String) { errors += utteranceId }
        override fun onPlannedRange(utteranceId: String, blockIndex: Int, offset: Int, length: Int) {
            ranges += Triple(blockIndex, offset, length)
        }
    }
    private data class Scheduled(val action: () -> Unit, var cancelled: Boolean = false)
    private class Harness {
        val listener = Listener()
        val played = mutableListOf<Pair<ProsodyPlanner.Unit, String>>()
        val scheduled = mutableListOf<Scheduled>()
        var stops = 0
        var throwOnPlay = false
        val runner = PlannedSpeechRunner(listener,
            play = { unit, id ->
                if (throwOnPlay) throw IllegalStateException("backend unavailable")
                played += unit to id
            },
            stopAudio = { stops++ },
            schedule = { _, action ->
                val task = Scheduled(action).also { scheduled += it }
                PlannedSpeechRunner.Cancellation { task.cancelled = true }
            }
        )
        fun fireAllEvenIfCancelled() {
            val pending = scheduled.toList()
            scheduled.clear()
            pending.forEach { it.action() }
        }
        val childId get() = played.last().second
    }
    private fun units() = listOf(
        ProsodyPlanner.Unit(TtsUtterance(2, 1, 3, "Gandalf", "First clause,", 4, 10), 0, 1f, 100),
        ProsodyPlanner.Unit(TtsUtterance(2, 1, 3, "Gandalf", "second clause.", 4, 24), 1, 0.96f, 0)
    )

    @Test fun childrenExposeOneParentLifecycleAndTheirActualRanges() {
        val h = Harness()
        h.runner.start("parent", units())
        val first = h.childId
        h.runner.onStart(first)
        h.runner.onStart(first)
        h.runner.onDone(first)
        h.runner.onDone(first)
        assertEquals(1, h.scheduled.size)
        h.fireAllEvenIfCancelled()
        val second = h.childId
        assertNotEquals(first, second)
        h.runner.onStart(second)
        h.runner.onDone(second)
        h.runner.onDone(second)
        h.runner.onError(second)
        assertEquals(listOf("parent"), h.listener.starts)
        assertEquals(listOf("parent"), h.listener.dones)
        assertTrue(h.listener.errors.isEmpty())
        assertEquals(listOf(Triple(4, 10, 13), Triple(4, 24, 14)), h.listener.ranges)
    }

    @Test fun stopCancelsPauseAndEvenAnAlreadyQueuedActionCannotReviveIt() {
        val h = Harness()
        h.runner.start("parent", units())
        h.runner.onDone(h.childId)
        h.runner.stop()
        assertTrue(h.scheduled.single().cancelled)
        h.fireAllEvenIfCancelled()
        assertEquals(1, h.played.size)
        assertTrue(h.listener.dones.isEmpty())
    }

    @Test fun lateCallbacksFromAnOldRequestCannotAffectTheReplacement() {
        val h = Harness()
        h.runner.start("old", units())
        val old = h.childId
        h.runner.onDone(old)
        h.runner.start("new", units())
        val current = h.childId
        h.fireAllEvenIfCancelled()
        h.runner.onStart(old)
        h.runner.onDone(old)
        h.runner.onError(old)
        assertEquals(2, h.played.size)
        assertTrue(h.listener.starts.isEmpty())
        assertTrue(h.listener.errors.isEmpty())
        h.runner.onStart(current)
        assertEquals(listOf("new"), h.listener.starts)
    }

    @Test fun aChildFailureIsTerminalAndOnlyReportedForTheParentOnce() {
        val h = Harness()
        h.runner.start("parent", units())
        val child = h.childId
        h.runner.onStart(child)
        h.runner.onError(child)
        h.runner.onError(child)
        h.runner.onDone(child)
        assertEquals(listOf("parent"), h.listener.errors)
        assertTrue(h.listener.dones.isEmpty())
        assertEquals(1, h.stops)
    }

    @Test fun synchronousBackendFailureDoesNotLeaveAResumablePlan() {
        val h = Harness()
        h.throwOnPlay = true
        h.runner.start("parent", units())
        h.fireAllEvenIfCancelled()
        assertEquals(listOf("parent"), h.listener.errors)
        assertTrue(h.played.isEmpty())
    }

    @Test fun stoppingBeforeStartSuppressesAllSubsequentChildCallbacks() {
        val h = Harness()
        h.runner.start("parent", units())
        val child = h.childId
        h.runner.stop()
        h.runner.onStart(child)
        h.runner.onDone(child)
        h.runner.onError(child)
        assertTrue(h.listener.starts.isEmpty())
        assertTrue(h.listener.dones.isEmpty())
        assertTrue(h.listener.errors.isEmpty())
    }

    @Test fun anEmptyPlanReportsOneErrorInsteadOfClaimingPlayback() {
        val h = Harness()
        h.runner.start("parent", emptyList())
        assertEquals(listOf("parent"), h.listener.errors)
        assertTrue(h.listener.starts.isEmpty())
    }
}
