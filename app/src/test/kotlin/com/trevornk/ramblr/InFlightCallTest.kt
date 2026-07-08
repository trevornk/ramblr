package com.trevornk.ramblr

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightCallTest {

    private class FakeCall : Call {
        var cancelled = false
            private set

        override fun request(): Request = throw UnsupportedOperationException()
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun enqueue(responseCallback: Callback) {}
        override fun cancel() { cancelled = true }
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = this
    }

    @Test fun `cancel aborts the held call`() {
        val holder = InFlightCall()
        val call = FakeCall()
        holder.set(call)

        holder.cancel()

        assertTrue(call.cancelled)
    }

    @Test fun `cancel with nothing in flight is a no-op`() {
        val holder = InFlightCall()
        holder.cancel() // must not throw
    }

    @Test fun `cancel sets the sticky isCancelled flag`() {
        val holder = InFlightCall()
        assertFalse(holder.isCancelled)

        holder.cancel()

        assertTrue(holder.isCancelled)
    }

    @Test fun `cancel with nothing in flight still sets isCancelled so a between-step cancel is not lost`() {
        // A cancel landing in the gap between HTTP steps (after clear, before the next set) has no
        // Call to abort, but must still be observable by the next step's pre-check.
        val holder = InFlightCall()

        holder.cancel()

        assertTrue(holder.isCancelled)
    }

    @Test fun `beginWork clears a prior cancel so isCancelled scopes to one logical unit of work`() {
        val holder = InFlightCall()
        holder.cancel()
        assertTrue(holder.isCancelled)

        holder.beginWork()

        assertFalse(holder.isCancelled)
    }

    @Test fun `clear only drops the reference if it still matches the given call`() {
        val holder = InFlightCall()
        val first = FakeCall()
        val second = FakeCall()
        holder.set(first)
        holder.set(second) // a new transcription started while `first`'s callback was in flight

        holder.clear(first) // `first`'s stale completion must not evict the newer `second`
        holder.cancel()

        assertFalse(first.cancelled)
        assertTrue(second.cancelled)
    }
}
