package com.linguareader.shared.tts

/**
 * Serialized, cancellable child playback. Backends echo child IDs here and expose only parent
 * lifecycle callbacks to the book engine. Callbacks after stop/new request cannot revive a plan.
 */
class PlannedSpeechRunner(
    private val listener: TtsSynthesizerListener,
    private val play: (ProsodyPlanner.Unit, String) -> Unit,
    private val stopAudio: () -> Unit,
    private val schedule: (Long, () -> Unit) -> Cancellation
) {
    fun interface Cancellation { fun cancel() }
    private var generation = 0L
    private var parent: String? = null
    private var units = emptyList<ProsodyPlanner.Unit>()
    private var index = 0
    private var parentStarted = false
    private var childStarted = false
    private var childCompleted = false
    private var delayed: Cancellation? = null

    fun start(parentId: String, plan: List<ProsodyPlanner.Unit>) {
        stop()
        parent = parentId
        units = plan
        index = 0
        parentStarted = false
        if (plan.isEmpty()) {
            parent = null
            listener.onError(parentId)
        } else playCurrent()
    }

    fun stop() {
        generation++
        delayed?.cancel()
        delayed = null
        val wasActive = parent != null
        parent = null
        units = emptyList()
        if (wasActive) stopAudio()
    }

    fun isActive(childId: String): Boolean = parent != null && childId == childId()

    fun onStart(childId: String) {
        if (!isActive(childId) || childCompleted || childStarted) return
        childStarted = true
        val parentId = parent ?: return
        if (!parentStarted) {
            parentStarted = true
            listener.onStart(parentId)
        }
        val utterance = units[index].utterance
        listener.onPlannedRange(parentId, utterance.blockIndex, utterance.offset, utterance.length)
    }

    fun onDone(childId: String) {
        if (!isActive(childId) || childCompleted) return
        childCompleted = true
        val parentId = parent ?: return
        if (index == units.lastIndex) {
            parent = null
            units = emptyList()
            listener.onDone(parentId)
            return
        }
        val epoch = generation
        val pause = units[index].pauseAfterMillis
        val advance = {
            if (generation == epoch && parent == parentId && isActive(childId)) {
                delayed = null
                index++
                playCurrent()
            }
        }
        if (pause > 0) delayed = schedule(pause, advance) else advance()
    }

    fun onError(childId: String) {
        if (!isActive(childId) || childCompleted) return
        val parentId = parent ?: return
        stop()
        listener.onError(parentId)
    }

    private fun playCurrent() {
        if (parent == null || index !in units.indices) return
        childStarted = false
        childCompleted = false
        val id = childId()
        try { play(units[index], id) } catch (_: Exception) { onError(id) }
    }

    private fun childId(): String = "$PREFIX$generation-$index"

    companion object {
        private const val PREFIX = "lingua-reader-plan-"
        fun isChildId(id: String): Boolean = id.startsWith(PREFIX)
    }
}
