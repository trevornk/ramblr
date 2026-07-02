package com.kafkasl.phonewhisper

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
}
