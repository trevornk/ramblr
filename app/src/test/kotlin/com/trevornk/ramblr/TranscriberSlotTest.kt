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
        private val releases = AtomicInteger(0)
        val released: Boolean get() = releases.get() > 0
        val releaseCount: Int get() = releases.get()

        fun release() { releases.incrementAndGet() }
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

    @Test fun `initializer completing after shutdown releases resource without installing it`() {
        val slot = TranscriberSlot<FakeResource> { it.release() }
        val lifecycle = TranscriberLifecycle(slot)
        val generation = requireNotNull(lifecycle.beginInitialization())
        val late = FakeResource()

        lifecycle.beginShutdown()
        assertFalse(lifecycle.install(generation, late))

        assertTrue(late.released)
        assertNull(slot.get())
    }

    @Test fun `local and streaming candidates install concurrently without superseding each other`() {
        val localSlot = TranscriberSlot<FakeResource> { it.release() }
        val streamingSlot = TranscriberSlot<FakeResource> { it.release() }
        val local = TranscriberLifecycle(localSlot)
        val streaming = TranscriberLifecycle(streamingSlot)
        val localCandidate = FakeResource()
        val streamingCandidate = FakeResource()
        val candidatesReady = CountDownLatch(2)
        val installCandidates = CountDownLatch(1)
        val installed = AtomicInteger(0)

        val localThread = Thread {
            val generation = requireNotNull(local.beginInitialization())
            candidatesReady.countDown()
            installCandidates.await(2, TimeUnit.SECONDS)
            if (local.install(generation, localCandidate)) installed.incrementAndGet()
        }
        val streamingThread = Thread {
            val generation = requireNotNull(streaming.beginInitialization())
            candidatesReady.countDown()
            installCandidates.await(2, TimeUnit.SECONDS)
            if (streaming.install(generation, streamingCandidate)) installed.incrementAndGet()
        }
        localThread.start()
        streamingThread.start()
        assertTrue(candidatesReady.await(2, TimeUnit.SECONDS))
        installCandidates.countDown()
        localThread.join(2000)
        streamingThread.join(2000)

        assertFalse(localThread.isAlive)
        assertFalse(streamingThread.isAlive)
        assertEquals(2, installed.get())
        assertEquals(localCandidate, localSlot.get())
        assertEquals(streamingCandidate, streamingSlot.get())
        assertEquals(0, localCandidate.releaseCount)
        assertEquals(0, streamingCandidate.releaseCount)
    }

    @Test fun `shutdown invalidates both late candidates and releases each exactly once`() {
        val localSlot = TranscriberSlot<FakeResource> { it.release() }
        val streamingSlot = TranscriberSlot<FakeResource> { it.release() }
        val local = TranscriberLifecycle(localSlot)
        val streaming = TranscriberLifecycle(streamingSlot)
        val localGeneration = requireNotNull(local.beginInitialization())
        val streamingGeneration = requireNotNull(streaming.beginInitialization())
        val lateLocal = FakeResource()
        val lateStreaming = FakeResource()

        local.beginShutdown()
        streaming.beginShutdown()
        local.releaseInstalled()
        streaming.releaseInstalled()

        assertFalse(local.install(localGeneration, lateLocal))
        assertFalse(streaming.install(streamingGeneration, lateStreaming))
        assertEquals(1, lateLocal.releaseCount)
        assertEquals(1, lateStreaming.releaseCount)
        assertNull(localSlot.get())
        assertNull(streamingSlot.get())
    }

    @Test fun `newer same-slot initialization rejects and releases older candidate exactly once`() {
        val slot = TranscriberSlot<FakeResource> { it.release() }
        val lifecycle = TranscriberLifecycle(slot)
        val oldGeneration = requireNotNull(lifecycle.beginInitialization())
        val newGeneration = requireNotNull(lifecycle.beginInitialization())
        val old = FakeResource()
        val newest = FakeResource()

        assertTrue(lifecycle.install(newGeneration, newest))
        assertFalse(lifecycle.install(oldGeneration, old))

        assertEquals(1, old.releaseCount)
        assertEquals(0, newest.releaseCount)
        assertEquals(newest, slot.get())
    }

    @Test fun `blocked publication cannot delay shutdown or install its late candidate`() {
        val slot = TranscriberSlot<FakeResource> { it.release() }
        val lifecycle = TranscriberLifecycle(slot)
        val installed = FakeResource()
        assertTrue(lifecycle.install(requireNotNull(lifecycle.beginInitialization()), installed))

        val useStarted = CountDownLatch(1)
        val allowUseToFinish = CountDownLatch(1)
        val useThread = Thread {
            slot.use {
                useStarted.countDown()
                allowUseToFinish.await(2, TimeUnit.SECONDS)
            }
        }
        useThread.start()
        assertTrue(useStarted.await(2, TimeUnit.SECONDS))

        val candidate = FakeResource()
        val candidateGeneration = requireNotNull(lifecycle.beginInitialization())
        val installAttempted = CountDownLatch(1)
        val installResult = AtomicInteger(-1)
        val installThread = Thread {
            installAttempted.countDown()
            installResult.set(if (lifecycle.install(candidateGeneration, candidate)) 1 else 0)
        }
        installThread.start()
        assertTrue(installAttempted.await(2, TimeUnit.SECONDS))

        val shutdownReturned = CountDownLatch(1)
        val shutdownThread = Thread {
            lifecycle.beginShutdown()
            shutdownReturned.countDown()
        }
        shutdownThread.start()
        val invalidatedWhilePublicationBlocked = shutdownReturned.await(500, TimeUnit.MILLISECONDS)
        allowUseToFinish.countDown()
        useThread.join(2000)
        installThread.join(2000)
        shutdownThread.join(2000)
        lifecycle.releaseInstalled()

        assertTrue("shutdown invalidation waited for slot publication", invalidatedWhilePublicationBlocked)
        assertEquals(0, installResult.get())
        assertNull(slot.get())
        assertEquals(1, candidate.releaseCount)
        assertEquals(1, installed.releaseCount)
    }

    @Test fun `shutdown at check publication seam rejects candidate deterministically`() {
        val slot = TranscriberSlot<FakeResource> { it.release() }
        val publicationReached = CountDownLatch(1)
        val allowPublicationCheck = CountDownLatch(1)
        val lifecycle = TranscriberLifecycle(slot) {
            publicationReached.countDown()
            allowPublicationCheck.await(2, TimeUnit.SECONDS)
        }
        val generation = requireNotNull(lifecycle.beginInitialization())
        val candidate = FakeResource()
        val result = AtomicInteger(-1)
        val installThread = Thread {
            result.set(if (lifecycle.install(generation, candidate)) 1 else 0)
        }

        installThread.start()
        assertTrue(publicationReached.await(2, TimeUnit.SECONDS))
        lifecycle.beginShutdown()
        allowPublicationCheck.countDown()
        installThread.join(2000)

        assertEquals(0, result.get())
        assertNull(slot.get())
        assertEquals(1, candidate.releaseCount)
    }

    @Test fun `shutdown invalidation does not wait for accepted replacement release`() {
        val releaseStarted = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        lateinit var blockedResource: FakeResource
        val slot = TranscriberSlot<FakeResource> {
            if (it === blockedResource) {
                releaseStarted.countDown()
                allowRelease.await(2, TimeUnit.SECONDS)
            }
            it.release()
        }
        val lifecycle = TranscriberLifecycle(slot)
        blockedResource = FakeResource()
        assertTrue(lifecycle.install(requireNotNull(lifecycle.beginInitialization()), blockedResource))
        val replacement = FakeResource()
        val replacementGeneration = requireNotNull(lifecycle.beginInitialization())
        val installThread = Thread { lifecycle.install(replacementGeneration, replacement) }
        installThread.start()
        assertTrue(releaseStarted.await(2, TimeUnit.SECONDS))

        val shutdownReturned = CountDownLatch(1)
        val shutdownThread = Thread {
            lifecycle.beginShutdown()
            shutdownReturned.countDown()
        }
        shutdownThread.start()
        val invalidatedWhileReleaseBlocked = shutdownReturned.await(500, TimeUnit.MILLISECONDS)
        allowRelease.countDown()
        installThread.join(2000)
        shutdownThread.join(2000)
        lifecycle.releaseInstalled()

        assertTrue("shutdown invalidation waited for native release", invalidatedWhileReleaseBlocked)
        assertEquals(1, blockedResource.releaseCount)
        assertEquals(1, replacement.releaseCount)
    }
}
