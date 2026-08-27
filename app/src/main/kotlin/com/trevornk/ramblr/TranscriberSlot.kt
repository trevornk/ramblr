package com.trevornk.ramblr

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Holds a single swappable native-backed resource (e.g. [LocalTranscriber]). [use] and [replace]
 * share a read/write lock so a model reload blocks until any in-flight [use] on the current value
 * completes, then releases the superseded value exactly once. See #17.
 */
class TranscriberSlot<T>(private val release: (T) -> Unit) {
    private val lock = ReentrantReadWriteLock()
    @Volatile private var current: T? = null

    fun get(): T? = current

    /** Runs [block] with the current value, or returns null if none is loaded. */
    fun <R> use(block: (T) -> R): R? {
        lock.readLock().withLock {
            val value = current ?: return null
            return block(value)
        }
    }

    /** Swap in [next], then release the previous value once any in-flight [use] has finished. */
    fun replace(next: T?) {
        val previous = lock.writeLock().withLock {
            val old = current
            current = next
            old
        }
        previous?.let(release)
    }

    /**
     * Conditionally publishes [next] under this slot's write lock. The predicate and swap are one
     * linearization point; release stays outside the lock so slow native teardown cannot delay
     * lifecycle invalidation.
     */
    internal fun replaceIf(next: T?, predicate: () -> Boolean): Boolean {
        var previous: T? = null
        val replaced = lock.writeLock().withLock {
            if (!predicate()) return@withLock false
            previous = current
            current = next
            true
        }
        if (replaced) previous?.let(release)
        return replaced
    }

    /** Releases a value that was created asynchronously but rejected before publication. */
    internal fun releaseRejected(value: T) = release(value)
}

/**
 * Generation gate around asynchronous native-resource creation. Creation deliberately happens
 * outside this monitor; [install] is the single publication point. Once shutdown is invalidated
 * synchronously, a late resource is released directly and can never resurrect a dead runtime.
 */
class TranscriberLifecycle<T>(private val slot: TranscriberSlot<T>) {
    companion object {
        private const val SHUTDOWN = Long.MIN_VALUE
    }

    /** Positive values are same-slot generations; [SHUTDOWN] permanently closes this lifecycle. */
    private val state = AtomicLong(0L)

    fun beginInitialization(): Long? {
        while (true) {
            val observed = state.get()
            if (observed == SHUTDOWN) return null
            val next = observed + 1
            if (state.compareAndSet(observed, next)) return next
        }
    }

    fun install(initializationGeneration: Long, resource: T?): Boolean {
        val installed = slot.replaceIf(resource) { state.get() == initializationGeneration }
        if (!installed) resource?.let(slot::releaseRejected)
        return installed
    }

    fun beginShutdown() {
        state.set(SHUTDOWN)
    }

    fun releaseInstalled() = slot.replace(null)
}
