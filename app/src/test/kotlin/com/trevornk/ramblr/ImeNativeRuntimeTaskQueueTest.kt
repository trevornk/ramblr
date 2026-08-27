package com.trevornk.ramblr

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeNativeRuntimeTaskQueueTest {
    @Test fun `rapid teardown and reinitialize releases old runtime before loading the new one`() {
        val queue = ImeNativeRuntimeTaskQueue()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val firstLocalStarted = CountDownLatch(1)
        val firstStreamingStarted = CountDownLatch(1)
        val allowFirstInitialization = CountDownLatch(1)
        try {
            queue.enqueueInitialization(
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

            queue.enqueueTeardown { events += "first-teardown" }
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
