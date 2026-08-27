package com.trevornk.ramblr

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

    /** Releases a value that was created asynchronously but rejected before publication. */
    internal fun releaseRejected(value: T) = release(value)
}

/**
 * Generation gate around asynchronous native-resource creation. Creation deliberately happens
 * outside this monitor; [install] is the single publication point. Once shutdown is invalidated
 * synchronously, a late resource is released directly and can never resurrect a dead runtime.
 */
class TranscriberLifecycle<T>(private val slot: TranscriberSlot<T>) {
    private var generation = 0L
    private var shutdown = false

    @Synchronized
    fun beginInitialization(): Long? = if (shutdown) null else ++generation

    fun install(initializationGeneration: Long, resource: T?): Boolean {
        synchronized(this) {
            if (!shutdown && initializationGeneration == generation) {
                slot.replace(resource)
                return true
            }
        }
        resource?.let(slot::releaseRejected)
        return false
    }

    @Synchronized
    fun beginShutdown() {
        shutdown = true
        generation++
    }

    fun releaseInstalled() = slot.replace(null)
}
