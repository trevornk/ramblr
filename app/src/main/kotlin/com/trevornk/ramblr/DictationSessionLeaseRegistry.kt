package com.trevornk.ramblr

import java.util.concurrent.atomic.AtomicLong

/** Opaque identity for one process-local dictation session. It never retains a host or Context. */
internal class DictationSessionLease internal constructor(internal val generation: Long)

/** Atomic process-local ownership seam; injectable so runtime tests never share global state. */
internal interface DictationSessionLeaseRegistry {
    fun tryAcquire(): DictationSessionLease?
    fun release(lease: DictationSessionLease): Boolean

    /** Runs process-start hygiene only when no session is owned, excluding acquisition until done. */
    fun runIfIdle(action: () -> Unit): Boolean
}

/**
 * Process-local lease registry. Ownership transitions share one short monitor; [runIfIdle] marks a
 * hygiene reservation then performs file I/O outside it, so recording acquisition fails fast
 * instead of blocking the main thread. [release] still compares the exact immutable lease.
 */
internal class InMemoryDictationSessionLeaseRegistry : DictationSessionLeaseRegistry {
    private val nextGeneration = AtomicLong(0)
    private var active: DictationSessionLease? = null
    private var hygieneRunning = false

    @Synchronized
    override fun tryAcquire(): DictationSessionLease? {
        if (active != null || hygieneRunning) return null
        return DictationSessionLease(nextGeneration.incrementAndGet()).also { active = it }
    }

    @Synchronized
    override fun release(lease: DictationSessionLease): Boolean {
        if (active !== lease) return false
        active = null
        return true
    }

    override fun runIfIdle(action: () -> Unit): Boolean {
        synchronized(this) {
            if (active != null || hygieneRunning) return false
            hygieneRunning = true
        }
        try {
            action()
        } finally {
            synchronized(this) { hygieneRunning = false }
        }
        return true
    }
}

/** The one registry shared by every production [DictationRuntime] in this app process. */
internal object ProcessDictationSessionLeaseRegistry : DictationSessionLeaseRegistry by
    InMemoryDictationSessionLeaseRegistry()
