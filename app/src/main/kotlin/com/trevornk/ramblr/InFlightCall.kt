package com.trevornk.ramblr

import okhttp3.Call
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the currently in-flight network [Call], if any, so a cancel gesture can abort the
 * actual request instead of just ignoring its eventual result. Set from the calling thread,
 * cancelled from the UI thread.
 *
 * Also carries a sticky [isCancelled] flag for work OkHttp can't abort (#83): a LOCAL_LLM
 * waterfall step is synchronous on-device inference with no [Call] to cancel, so it polls this
 * flag between generated pieces instead. The flag is cleared by [beginWork] at the start of each
 * waterfall run, scoping "cancelled" to one logical cleanup call the same way the [Call]
 * reference is.
 */
class InFlightCall {
    private val ref = AtomicReference<Call?>(null)

    @Volatile private var cancelled = false

    private val cancels = AtomicInteger(0)

    /** True once [cancel] has run for the current unit of work (see [beginWork]). */
    val isCancelled: Boolean get() = cancelled

    /** How many times [cancel] has run. Unlike the sticky [isCancelled] flag, which only
     *  [beginWork] clears, a caller that snapshots this can tell whether a cancel arrived since
     *  (a Gemini overload retry waiting out its backoff has no [Call] for [cancel] to abort). */
    val cancelCount: Int get() = cancels.get()

    /** Marks the start of a new logical unit of cancellable work, clearing any stale cancel. */
    fun beginWork() {
        cancelled = false
    }

    fun set(call: Call) {
        ref.set(call)
    }

    /** Clears the reference only if it still points at [call], so a stale completion can't drop a newer call. */
    fun clear(call: Call) {
        ref.compareAndSet(call, null)
    }

    /** Cancels the in-flight call, if any, and clears the reference. Safe to call with nothing in flight. */
    fun cancel() {
        cancelled = true
        cancels.incrementAndGet()
        ref.getAndSet(null)?.cancel()
    }
}
