package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for what's left of [LocalCleanupProvider] after #84 removed the dead `run` helper
 * (zero production callers; the executor's LOCAL_LLM branch owns result translation and trimming
 * now -- see CleanupWaterfallExecutorTest's local-step tests). [LocalCleanupProvider.selectedModel]
 * needs a Context and is covered indirectly via [ModelDownloader.resolveActiveModel]'s own tests.
 */
class LocalCleanupProviderTest {

    @Test fun `MODEL is the one curated local-cleanup catalog entry`() {
        assertEquals(LOCAL_CLEANUP_MODEL, LocalCleanupProvider.MODEL)
        assertTrue(LocalCleanupProvider.MODEL.isLocalCleanup)
    }
}
