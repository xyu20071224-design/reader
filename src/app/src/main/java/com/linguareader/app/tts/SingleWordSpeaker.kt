package com.linguareader.app.tts

/**
 * One-shot word/phrase speech. Calls and engine callbacks must be serialized by the owner.
 *
 * Engine generations protect against late initialization after disposal/retry; request IDs protect
 * against QUEUE_FLUSH callbacks from the word which the user has already replaced. Diagnostic
 * events deliberately contain no spoken text.
 */
internal class SingleWordSpeaker(
    private val engineFactory: () -> Engine,
    private val onFailure: (Failure) -> Unit,
    private val onEvent: (Event) -> Unit = {}
) {
    interface Engine {
        fun initialize(listener: Listener)
        /** Android TextToSpeech language availability: nonnegative means usable. */
        fun configureEnglish(): Int
        /** Android TextToSpeech queue result: zero means accepted. */
        fun speak(text: String, utteranceId: String): Int
        fun stop()
        fun shutdown()
    }

    interface Listener {
        fun onInitialized(status: Int)
        fun onStart(utteranceId: String)
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String, errorCode: Int? = null)
        fun onStop(utteranceId: String)
    }

    enum class Failure { ENGINE_UNAVAILABLE, LANGUAGE_UNAVAILABLE, REQUEST_REJECTED, PLAYBACK_FAILED }
    enum class Stage {
        INITIALIZING, INITIALIZED, LANGUAGE_CONFIGURED, INITIALIZATION_FAILED,
        DEFERRED, ENQUEUED, REQUEST_FAILED, STARTED, DONE, PLAYBACK_FAILED, STOPPED, CLOSED
    }
    data class Event(val stage: Stage, val generation: Long, val utteranceId: String? = null, val code: Int? = null)

    private val readiness = SpeechReadiness()
    private var engine: Engine? = null
    private var generation = 0L
    private var requestSequence = 0L
    private var initialized = false
    private var initializationFailure: Failure? = null
    private var hasPendingRequest = false
    private var activeRequestId: String? = null
    private var closed = false

    /** Optional eager initialization. Failures remain quiet until the user requests speech. */
    fun start() {
        if (!closed && engine == null && initializationFailure == null) initialize(null)
    }

    fun speak(text: String) {
        if (closed || text.isBlank()) return
        if (engine == null || initializationFailure != null) {
            // An explicit new click is also a retry after an unavailable engine/language.
            initialize(text)
            return
        }
        if (readiness.request(text)) {
            enqueue(text)
        } else {
            hasPendingRequest = true
            emit(Stage.DEFERRED)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        generation++
        activeRequestId = null
        hasPendingRequest = false
        readiness.reset()
        releaseEngine()
        emit(Stage.CLOSED)
    }

    private fun initialize(initialText: String?) {
        val epoch = ++generation
        activeRequestId = null
        initialized = false
        initializationFailure = null
        readiness.reset()
        hasPendingRequest = initialText != null
        initialText?.let { readiness.request(it) }
        releaseEngine()
        emit(Stage.INITIALIZING)
        val created = try {
            engineFactory()
        } catch (_: Exception) {
            failInitialization(epoch, Failure.ENGINE_UNAVAILABLE)
            return
        }
        engine = created
        try {
            created.initialize(object : Listener {
                override fun onInitialized(status: Int) = initialized(epoch, status)
                override fun onStart(utteranceId: String) = withActive(epoch, utteranceId) {
                    emit(Stage.STARTED, utteranceId)
                }
                override fun onDone(utteranceId: String) = withActive(epoch, utteranceId) {
                    activeRequestId = null
                    emit(Stage.DONE, utteranceId)
                }
                override fun onError(utteranceId: String, errorCode: Int?) = withActive(epoch, utteranceId) {
                    activeRequestId = null
                    emit(Stage.PLAYBACK_FAILED, utteranceId, errorCode)
                    onFailure(Failure.PLAYBACK_FAILED)
                }
                override fun onStop(utteranceId: String) = withActive(epoch, utteranceId) {
                    activeRequestId = null
                    emit(Stage.STOPPED, utteranceId)
                    onFailure(Failure.PLAYBACK_FAILED)
                }
            })
        } catch (_: Exception) {
            failInitialization(epoch, Failure.ENGINE_UNAVAILABLE)
        }
    }

    private fun initialized(epoch: Long, status: Int) {
        if (closed || epoch != generation || initialized) return
        initialized = true
        emit(Stage.INITIALIZED, code = status)
        if (status != 0) {
            failInitialization(epoch, Failure.ENGINE_UNAVAILABLE, status)
            return
        }
        val language = try {
            engine?.configureEnglish()
        } catch (_: Exception) {
            null
        }
        emit(Stage.LANGUAGE_CONFIGURED, code = language)
        if (language == null) {
            failInitialization(epoch, Failure.ENGINE_UNAVAILABLE)
            return
        }
        if (language < 0) {
            failInitialization(epoch, Failure.LANGUAGE_UNAVAILABLE, language)
            return
        }
        val queued = readiness.onReady()
        hasPendingRequest = false
        queued?.let(::enqueue)
    }

    private fun failInitialization(epoch: Long, reason: Failure, code: Int? = null) {
        if (closed || epoch != generation || initializationFailure != null) return
        initializationFailure = reason
        val requested = hasPendingRequest
        hasPendingRequest = false
        readiness.reset()
        emit(Stage.INITIALIZATION_FAILED, code = code)
        if (requested) onFailure(reason)
    }

    private fun enqueue(text: String) {
        val id = "lingua-reader-word-$generation-${++requestSequence}"
        activeRequestId = id
        val result = try {
            engine?.speak(text, id)
        } catch (_: Exception) {
            null
        }
        // A backend may synchronously report an error while enqueueing. Do not report it twice.
        if (result != 0 && activeRequestId == id) {
            activeRequestId = null
            emit(Stage.REQUEST_FAILED, id, result)
            onFailure(Failure.REQUEST_REJECTED)
        } else if (result == 0 && activeRequestId == id) {
            emit(Stage.ENQUEUED, id, result)
        }
    }

    private inline fun withActive(epoch: Long, id: String, action: () -> Unit) {
        if (!closed && epoch == generation && activeRequestId == id) action()
    }

    private fun releaseEngine() {
        val previous = engine
        engine = null
        // Disposal is best effort, but generation invalidation has already happened.
        runCatching { previous?.stop() }
        runCatching { previous?.shutdown() }
    }

    private fun emit(stage: Stage, id: String? = null, code: Int? = null) {
        onEvent(Event(stage, generation, id, code))
    }
}
