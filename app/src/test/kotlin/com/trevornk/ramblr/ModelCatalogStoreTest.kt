package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the pure background-refresh gate of [ModelCatalogStore] (L6). The Context/network side
 *  needs a real Android runtime and isn't exercised by plain JVM tests. */
class ModelCatalogStoreTest {

    private val backoff = 5 * 60 * 1000L

    @Test fun `refreshes when stale, idle, and past the backoff`() {
        assertTrue(
            ModelCatalogStore.shouldStartBackgroundFetch(
                isStale = true, fetchInFlight = false, nowMs = 10 * backoff, lastAttemptAtMs = 0L, backoffMs = backoff,
            )
        )
    }

    @Test fun `never refreshes a fresh cache`() {
        assertFalse(
            ModelCatalogStore.shouldStartBackgroundFetch(
                isStale = false, fetchInFlight = false, nowMs = 10 * backoff, lastAttemptAtMs = 0L, backoffMs = backoff,
            )
        )
    }

    @Test fun `never launches a second refresh while one is already in flight`() {
        assertFalse(
            ModelCatalogStore.shouldStartBackgroundFetch(
                isStale = true, fetchInFlight = true, nowMs = 10 * backoff, lastAttemptAtMs = 0L, backoffMs = backoff,
            )
        )
    }

    @Test fun `does not respawn within the backoff window after a failed attempt`() {
        // A failed fetch leaves the cache stale; without backoff this would refetch on every call.
        assertFalse(
            ModelCatalogStore.shouldStartBackgroundFetch(
                isStale = true, fetchInFlight = false, nowMs = backoff - 1, lastAttemptAtMs = 0L, backoffMs = backoff,
            )
        )
    }

    @Test fun `retries once the backoff window elapses`() {
        assertTrue(
            ModelCatalogStore.shouldStartBackgroundFetch(
                isStale = true, fetchInFlight = false, nowMs = backoff, lastAttemptAtMs = 0L, backoffMs = backoff,
            )
        )
    }
}
