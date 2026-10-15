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

    /**
     * Conditionally publishes [next] under this slot's write lock. [guard] must invoke the supplied
     * publication exactly once to accept the value. This lets a lifecycle hold its brief monitor
     * across both its final generation check and the slot assignment, making shutdown and
     * publication truly linearizable. Release remains outside both locks.
     */
    internal fun replaceIf(next: T?, guard: ((() -> Unit) -> Boolean)): Boolean {
        var previous: T? = null
        val replaced = lock.writeLock().withLock {
            guard {
                previous = current
                current = next
            }
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
class TranscriberLifecycle<T>(
    private val slot: TranscriberSlot<T>,
    private val beforePublicationCheck: () -> Unit = {},
) {
    companion object {
        private const val SHUTDOWN = Long.MIN_VALUE
    }

    private val lifecycleLock = Any()
    /** Positive values are same-slot generations; [SHUTDOWN] permanently closes this lifecycle. */
    private var state = 0L

    fun beginInitialization(): Long? = synchronized(lifecycleLock) {
        if (state == SHUTDOWN) return@synchronized null
        ++state
    }

    fun install(initializationGeneration: Long, resource: T?): Boolean {
        val installed = slot.replaceIf(resource) { publish ->
            // Test seam and potentially blocking slot acquisition happen before the lifecycle lock,
            // so beginShutdown() stays prompt while an in-flight use holds the slot read lock.
            beforePublicationCheck()
            synchronized(lifecycleLock) {
                if (state != initializationGeneration) return@synchronized false
                publish()
                true
            }
        }
        if (!installed) resource?.let(slot::releaseRejected)
        return installed
    }

    fun beginShutdown() {
        synchronized(lifecycleLock) { state = SHUTDOWN }
    }

    fun releaseInstalled() = slot.replace(null)

    /**
     * #305: claims a release of whatever is installed *now*, returning a ticket for
     * [releaseIfCurrent] (null once shut down). Takes a generation like [beginInitialization] does,
     * so an initialization that was already in flight is rejected at [install] (its late result is
     * released instead of resurrecting what the release dropped) and any initialization that
     * begins later supersedes this ticket. Cheap and non-blocking: call it on the thread that
     * decides to release (main), then do the blocking [releaseIfCurrent] elsewhere.
     */
    fun beginRelease(): Long? = beginInitialization()

    /**
     * Drops the installed resource unless a newer initialization or release has begun since
     * [beginRelease] returned [releaseGeneration]. Blocks on in-flight use of the slot, so never
     * call from main. Returns false when superseded -- the newer initialization owns the slot
     * and will install over (and release) whatever is there, so a release that loses the race
     * must not null a model it did not see.
     */
    fun releaseIfCurrent(releaseGeneration: Long): Boolean = slot.replaceIf(null) { publish ->
        synchronized(lifecycleLock) {
            if (state != releaseGeneration) return@synchronized false
            publish()
            true
        }
    }
}
