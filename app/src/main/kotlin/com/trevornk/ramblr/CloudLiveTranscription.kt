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
