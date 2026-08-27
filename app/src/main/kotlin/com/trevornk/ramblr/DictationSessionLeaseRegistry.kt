package com.trevornk.ramblr

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Opaque identity for one process-local dictation session. It never retains a host or Context. */
internal class DictationSessionLease internal constructor(internal val generation: Long)

/** Atomic process-local ownership seam; injectable so runtime tests never share global state. */
internal interface DictationSessionLeaseRegistry {
    fun tryAcquire(): DictationSessionLease?
    fun release(lease: DictationSessionLease): Boolean
}

/**
 * Lock-free lease registry. [release] is compare-and-release against the exact immutable lease,
 * so a late callback carrying an older generation cannot clear a newer session's ownership.
 */
internal class InMemoryDictationSessionLeaseRegistry : DictationSessionLeaseRegistry {
    private val nextGeneration = AtomicLong(0)
    private val active = AtomicReference<DictationSessionLease?>(null)

    override fun tryAcquire(): DictationSessionLease? {
        val lease = DictationSessionLease(nextGeneration.incrementAndGet())
        return if (active.compareAndSet(null, lease)) lease else null
    }

    override fun release(lease: DictationSessionLease): Boolean =
        active.compareAndSet(lease, null)
}

/** The one registry shared by every production [DictationRuntime] in this app process. */
internal object ProcessDictationSessionLeaseRegistry : DictationSessionLeaseRegistry by
    InMemoryDictationSessionLeaseRegistry()
