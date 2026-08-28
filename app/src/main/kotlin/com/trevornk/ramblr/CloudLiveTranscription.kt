package com.trevornk.ramblr

import java.util.concurrent.Executor

/** Provider-neutral lifecycle for one push-to-talk cloud transcription attempt.
 *
 * Implementations connect asynchronously. [startActivity], [sendPcm], and [endActivity] may be
 * called before setup completes and must preserve their wire order. [sendPcm] must copy the valid
 * bytes before returning because RecordingEngine reuses its input buffer. Callbacks are serialized
 * on the factory's documented callback executor. Exactly one [onTerminal] callback is emitted;
 * interim callbacks stop before it. Cancellation and close are idempotent terminal operations.
 */
interface CloudLiveTranscriptionSession {
    fun connect()
    fun startActivity(): Boolean
    fun sendPcm(buffer: ByteArray, length: Int): Boolean
    fun endActivity(): Boolean
    fun cancel()
    fun close()
}

fun interface CloudLiveTranscriptionSessionFactory {
    fun create(listener: CloudLiveTranscriptionListener): CloudLiveTranscriptionSession
}

interface CloudLiveTranscriptionListener {
    fun onInterim(text: String, timing: CloudLiveTiming)
    fun onTerminal(result: CloudLiveTerminal)
}

data class CloudLiveTiming(
    val connectStartedAtMs: Long,
    val socketOpenedAtMs: Long? = null,
    val setupCompletedAtMs: Long? = null,
    val firstInterimAtMs: Long? = null,
    val activityEndedAtMs: Long? = null,
    val finalAtMs: Long? = null,
)

enum class CloudLiveFailureReason {
    INVALID_REQUEST,
    SETUP_TIMEOUT,
    FINAL_TIMEOUT,
    BUFFER_LIMIT,
    SEND_FAILED,
    PROTOCOL_ERROR,
    NETWORK_ERROR,
    CANCELLED,
}

sealed class CloudLiveTerminal {
    abstract val timing: CloudLiveTiming

    data class Success(val text: String, override val timing: CloudLiveTiming) : CloudLiveTerminal()
    data class Failure(
        val reason: CloudLiveFailureReason,
        val message: String,
        override val timing: CloudLiveTiming,
    ) : CloudLiveTerminal()
}

/**
 * How one cloud-live attempt actually ended, from the runtime's arbitration point of view
 * (#233 Phase 1 item 10). Deliberately finer-grained than [CloudLiveTerminal]'s two cases,
 * because the two ways a *successful* live session still loses the recording to batch are
 * exactly the ones a device trial has to be able to tell apart afterwards.
 */
enum class CloudLiveOutcome {
    /** Live text was authoritative and went down the normal delivery path. */
    LIVE_DELIVERED,
    /** The session reported [CloudLiveTerminal.Success], but the final was unusable (blank, or
     *  the preserved recording was below the minimum-duration floor), so batch served it. */
    FALLBACK_UNUSABLE_FINAL,
    /** The session reported [CloudLiveTerminal.Failure]; see [CloudLiveStage.failureReason]. */
    FALLBACK_FAILED,
    /** No terminal callback arrived at all before the runtime's bounded final wait expired --
     *  the mid-stream-drop / wedged-socket case, which produces no [CloudLiveFailureReason]
     *  because the session never got far enough to report one. */
    FALLBACK_NO_TERMINAL,
}

/**
 * Derives the benchmark record for one live attempt: durations only, no transcript content.
 *
 * Pure and free of Android/[android.content.Context] so the whole "what does a device trial
 * actually see" question is unit-testable without a device. [timing] is the best marks known to
 * the runtime -- a terminal's own timing when there is one, otherwise the last interim's, which
 * is what makes [CloudLiveOutcome.FALLBACK_NO_TERMINAL] still able to report that setup landed
 * and interims flowed before the session went quiet.
 *
 * Durations are simple subtractions of the marks the session already recorded; a mark that was
 * never set yields null rather than a fabricated zero, because "never happened" and "happened
 * instantly" are different findings.
 */
fun cloudLiveBenchmarkStage(
    outcome: CloudLiveOutcome,
    terminal: CloudLiveTerminal?,
    timing: CloudLiveTiming?,
): CloudLiveStage {
    val marks = terminal?.timing ?: timing
    val failure = terminal as? CloudLiveTerminal.Failure
    return CloudLiveStage(
        outcome = outcome.name,
        fellBackToBatch = outcome != CloudLiveOutcome.LIVE_DELIVERED,
        failureReason = failure?.reason?.name,
        setupMs = marks?.let { m -> m.setupCompletedAtMs?.minus(m.connectStartedAtMs) },
        firstInterimMs = marks?.let { m -> m.firstInterimAtMs?.minus(m.connectStartedAtMs) },
        endOfAudioToFinalMs = marks?.let { m ->
            val ended = m.activityEndedAtMs ?: return@let null
            m.finalAtMs?.minus(ended)
        },
        // Provider prose can echo request content and can carry a URL; sanitizeError bounds it
        // and the client has already redacted the API key out of the message before this point.
        error = sanitizeError(failure?.message),
    )
}

/**
 * Serializes callbacks even when the supplied executor is a pool.
 *
 * One instance is shared factory-wide by every session, so a single throwing listener callback
 * must never be able to take the executor down with it. [drain] therefore keeps its bookkeeping
 * consistent no matter what a task does: it always leaves via [nextTaskOrFinish], which clears
 * `running` in the same critical section as the emptiness check, so a later [execute] is
 * guaranteed either to be picked up by the in-flight drain or to start a fresh one. Before this,
 * a throwing task unwound `drain` with `running == true` and a non-empty queue, permanently
 * wedging cloud-live for the whole process.
 *
 * Failures are not swallowed: they are rethrown from [drain] once the queue is empty, on the
 * delegate's own thread, so its uncaught-exception handler still reports them. Additional
 * failures in the same drain ride along as suppressed exceptions.
 */
internal class SerialCallbackExecutor(private val delegate: Executor) : Executor {
    private val tasks = ArrayDeque<Runnable>()
    private var running = false

    override fun execute(command: Runnable) {
        val shouldStart = synchronized(tasks) {
            tasks.addLast(command)
            if (running) false else { running = true; true }
        }
        if (shouldStart) delegate.execute(::drain)
    }

    private fun drain() {
        var failure: Throwable? = null
        while (true) {
            val task = nextTaskOrFinish() ?: break
            try {
                task.run()
            } catch (t: Throwable) {
                if (failure == null) failure = t else failure.addSuppressed(t)
            }
        }
        failure?.let { throw it }
    }

    /** The next queued task, or null after marking this drain finished. Clearing `running` shares
     *  the emptiness check's critical section so no [execute] can be stranded un-drained. */
    private fun nextTaskOrFinish(): Runnable? = synchronized(tasks) {
        if (tasks.isEmpty()) {
            running = false
            null
        } else {
            tasks.removeFirst()
        }
    }
}
