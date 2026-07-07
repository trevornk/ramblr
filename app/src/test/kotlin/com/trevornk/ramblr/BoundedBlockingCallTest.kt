package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real-thread tests for [BoundedBlockingCall] (#92) -- no Android dependency, so plain
 * ExecutorService/Future/CountDownLatch usage is exercised directly, same style as
 * [TranscriberSlotTest]'s concurrency coverage.
 */
class BoundedBlockingCallTest {

    @Test fun `a call that finishes before the deadline returns its result normally`() {
        val result = BoundedBlockingCall.runWithDeadline(System.currentTimeMillis() + 5_000L) {
            "done"
        }
        assertEquals("done", result)
    }

    @Test fun `a call that outlives the deadline returns null instead of blocking the caller`() {
        // BoundedBlockingCall's executor is a shared singleton (by design, matching production),
        // so this latch MUST be released before the test ends -- otherwise the abandoned task
        // keeps running.await() forever and permanently wedges every other test that shares the
        // same single background thread, since JUnit doesn't guarantee execution order.
        val blockForever = CountDownLatch(1)
        try {
            val start = System.currentTimeMillis()

            val result = BoundedBlockingCall.runWithDeadline(System.currentTimeMillis() + 200L) {
                blockForever.await() // would hang indefinitely without the deadline
                "should never get here"
            }

            val elapsed = System.currentTimeMillis() - start
            assertNull(result)
            // The caller must not wait meaningfully longer than the requested deadline, regardless
            // of how long the abandoned background call actually takes.
            assertTrue("expected to return quickly after the deadline, took ${elapsed}ms", elapsed < 2_000L)
        } finally {
            blockForever.countDown()
        }
    }

    @Test fun `an exception thrown before the deadline propagates to the caller`() {
        try {
            BoundedBlockingCall.runWithDeadline(System.currentTimeMillis() + 5_000L) {
                throw IllegalStateException("boom")
            }
            fail("expected IllegalStateException to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    @Test fun `a deadline already in the past still attempts the call once`() {
        // Matches a fast/cached path completing before the check matters, rather than refusing
        // to even try when the caller is already over budget by the time it gets here.
        val result = BoundedBlockingCall.runWithDeadline(System.currentTimeMillis() - 1_000L) {
            "still ran"
        }
        assertEquals("still ran", result)
    }

    @Test fun `an abandoned call does not throw or corrupt a later call on the same executor`() {
        // BoundedBlockingCall's executor is a single dedicated thread (by design, matching
        // production serialization) shared across the whole test class -- so blockForever MUST
        // be released (in a finally, so even a failed assertion above it doesn't skip this)
        // before waiting on a second submission below, or the second call queues forever behind
        // the still-blocked first one and this test (and every test after it, since the shared
        // executor stays wedged) deadlocks/times out.
        val blockForever = CountDownLatch(1)
        val timedOut = try {
            BoundedBlockingCall.runWithDeadline(System.currentTimeMillis() + 100L) {
                blockForever.await()
                "abandoned"
            }
        } finally {
            blockForever.countDown()
        }
        assertNull(timedOut)

        // A second call submitted shortly after: the abandoned task above should finish quickly
        // now that its latch is released, freeing the single executor thread for this one.
        val second = BoundedBlockingCall.runWithDeadline(System.currentTimeMillis() + 3_000L) {
            "second call result"
        }
        assertEquals("second call result", second)
    }
}
