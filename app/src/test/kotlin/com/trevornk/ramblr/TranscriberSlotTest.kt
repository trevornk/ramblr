package com.trevornk.ramblr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriberSlotTest {

    private class FakeResource {
        var released = false
            private set

        fun release() { released = true }
    }

    @Test fun `use runs against nothing when empty`() {
        val slot = TranscriberSlot<FakeResource> { it.release() }
        assertNull(slot.use { it })
    }

    @Test fun `replace releases the previous value`() {
        val slot = TranscriberSlot<FakeResource> { it.release() }
        val first = FakeResource()
        slot.replace(first)

        val second = FakeResource()
        slot.replace(second)

        assertTrue(first.released)
        assertFalse(second.released)
        assertEquals(second, slot.get())
    }

    @Test fun `replace with null releases the current value and leaves the slot empty`() {
        val slot = TranscriberSlot<FakeResource> { it.release() }
        val first = FakeResource()
        slot.replace(first)

        slot.replace(null)

        assertTrue(first.released)
        assertNull(slot.get())
    }

    @Test fun `an in-flight use blocks release until it completes`() {
        val slot = TranscriberSlot<FakeResource> { it.release() }
        val old = FakeResource()
        slot.replace(old)

        val useStarted = CountDownLatch(1)
        val releaseObservedDuringUse = AtomicInteger(-1)
        val letUseFinish = CountDownLatch(1)

        val useThread = Thread {
            slot.use {
                useStarted.countDown()
                letUseFinish.await(2, TimeUnit.SECONDS)
                releaseObservedDuringUse.set(if (it.released) 1 else 0)
            }
        }
        useThread.start()
        assertTrue(useStarted.await(2, TimeUnit.SECONDS))

        // Model reload swaps in a new transcriber while the old one is still transcribing.
        val replaceThread = Thread { slot.replace(FakeResource()) }
        replaceThread.start()

        // replace() must not finish (and must not release `old`) while use() still holds it.
        replaceThread.join(200)
        assertTrue(replaceThread.isAlive)
        assertFalse(old.released)

        letUseFinish.countDown()
        useThread.join(2000)
        replaceThread.join(2000)

        assertFalse(replaceThread.isAlive)
        assertEquals(0, releaseObservedDuringUse.get()) // not yet released while use() was running
        assertTrue(old.released) // released once use() completed
    }
}
