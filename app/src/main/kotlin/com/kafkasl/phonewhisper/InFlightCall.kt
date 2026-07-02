package com.kafkasl.phonewhisper

import okhttp3.Call
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the currently in-flight network [Call], if any, so a cancel gesture can abort the
 * actual request instead of just ignoring its eventual result. Set from the calling thread,
 * cancelled from the UI thread.
 */
class InFlightCall {
    private val ref = AtomicReference<Call?>(null)

    fun set(call: Call) {
        ref.set(call)
    }

    /** Clears the reference only if it still points at [call], so a stale completion can't drop a newer call. */
    fun clear(call: Call) {
        ref.compareAndSet(call, null)
    }

    /** Cancels the in-flight call, if any, and clears the reference. Safe to call with nothing in flight. */
    fun cancel() {
        ref.getAndSet(null)?.cancel()
    }
}
