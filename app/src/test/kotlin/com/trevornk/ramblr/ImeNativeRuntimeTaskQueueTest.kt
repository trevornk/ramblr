package com.trevornk.ramblr

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeNativeRuntimeTaskQueueTest {
    @Test fun `obsolete queued initialization is skipped before latest runtime starts`() {
        val queue = ImeNativeRuntimeTaskQueue()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val blockerStarted = CountDownLatch(1)
        val allowBlocker = CountDownLatch(1)
        try {
            val blocker = queue.enqueueInitialization(
                local = { blockerStarted.countDown(); allowBlocker.await(2, TimeUnit.SECONDS) },
                streaming = { allowBlocker.await(2, TimeUnit.SECONDS) },
            )
            assertTrue(blockerStarted.await(2, TimeUnit.SECONDS))
            val obsolete = queue.enqueueInitialization(
                local = { events += "obsolete-local" },
                streaming = { events += "obsolete-streaming" },
            )
            queue.enqueueTeardown(obsolete) { events += "obsolete-teardown" }
            queue.enqueueInitialization(
                local = { events += "latest-local" },
                streaming = { events += "latest-streaming" },
            )
            allowBlocker.countDown()
            assertTrue(queue.awaitIdle(2, TimeUnit.SECONDS))

            assertFalse(events.contains("obsolete-local"))
            assertFalse(events.contains("obsolete-streaming"))
            assertTrue(events.contains("latest-local"))
            assertTrue(events.contains("latest-streaming"))
        } finally {
            allowBlocker.countDown()
            queue.close()
        }
    }

    @Test fun `download completion reloads an already active runtime without overlapping initialization`() {
        val queue = ImeNativeRuntimeTaskQueue()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val initialLocalStarted = CountDownLatch(1)
        val allowInitialLocal = CountDownLatch(1)
        try {
            val active = queue.enqueueInitialization(
                local = {
                    events += "initial-local-start"
                    initialLocalStarted.countDown()
                    allowInitialLocal.await(2, TimeUnit.SECONDS)
                    events += "initial-local-end"
                },
                streaming = { events += "initial-streaming" },
            )
            assertTrue(initialLocalStarted.await(2, TimeUnit.SECONDS))

            queue.enqueueReload(active) { events += "download-reload" }
            assertFalse(events.contains("download-reload"))

            allowInitialLocal.countDown()
            assertTrue(queue.awaitIdle(2, TimeUnit.SECONDS))
            assertTrue(events.indexOf("download-reload") > events.indexOf("initial-local-end"))
        } finally {
            allowInitialLocal.countDown()
            queue.close()
        }
    }

    @Test fun `download reload queued for runtime lost before execution is rejected`() {
        val queue = ImeNativeRuntimeTaskQueue()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val initializationStarted = CountDownLatch(1)
        val allowInitialization = CountDownLatch(1)
        try {
            val stale = queue.enqueueInitialization(
                local = {
                    initializationStarted.countDown()
                    allowInitialization.await(2, TimeUnit.SECONDS)
                },
                streaming = { allowInitialization.await(2, TimeUnit.SECONDS) },
            )
            assertTrue(initializationStarted.await(2, TimeUnit.SECONDS))

            queue.enqueueReload(stale) { events += "stale-download-reload" }
            queue.enqueueTeardown(stale) { events += "teardown" }
            allowInitialization.countDown()
            assertTrue(queue.awaitIdle(2, TimeUnit.SECONDS))

            assertFalse(events.contains("stale-download-reload"))
            assertEquals(listOf("teardown"), events)
        } finally {
            allowInitialization.countDown()
            queue.close()
        }
    }

    @Test fun `task failures are reported`() {
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val queue = ImeNativeRuntimeTaskQueue { task, _ -> failures += task }
        try {
            queue.enqueueInitialization(
                local = { error("local failed") },
                streaming = { error("streaming failed") },
            )
            assertTrue(queue.awaitIdle(2, TimeUnit.SECONDS))
            assertEquals(setOf("local initialization", "streaming initialization"), failures.toSet())
        } finally {
            queue.close()
        }
    }

    @Test fun `rapid teardown and reinitialize releases old runtime before loading the new one`() {
        val queue = ImeNativeRuntimeTaskQueue()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val firstLocalStarted = CountDownLatch(1)
        val firstStreamingStarted = CountDownLatch(1)
        val allowFirstInitialization = CountDownLatch(1)
        try {
            val first = queue.enqueueInitialization(
                local = {
                    events += "first-local"
                    firstLocalStarted.countDown()
                    allowFirstInitialization.await(2, TimeUnit.SECONDS)
                },
                streaming = {
                    events += "first-streaming"
                    firstStreamingStarted.countDown()
                    allowFirstInitialization.await(2, TimeUnit.SECONDS)
                },
            )
            assertTrue(firstLocalStarted.await(2, TimeUnit.SECONDS))
            assertTrue(firstStreamingStarted.await(2, TimeUnit.SECONDS))

            queue.enqueueTeardown(first) { events += "first-teardown" }
            queue.enqueueInitialization(
                local = { events += "second-local" },
                streaming = { events += "second-streaming" },
            )
            allowFirstInitialization.countDown()
            assertTrue(queue.awaitIdle(2, TimeUnit.SECONDS))

            val teardown = events.indexOf("first-teardown")
            assertTrue(teardown > events.indexOf("first-local"))
            assertTrue(teardown > events.indexOf("first-streaming"))
            assertTrue(teardown < events.indexOf("second-local"))
            assertTrue(teardown < events.indexOf("second-streaming"))
        } finally {
            allowFirstInitialization.countDown()
            queue.close()
        }
    }
}
