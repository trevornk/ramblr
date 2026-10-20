package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [LocalCleanupModelSlot]'s pure reload/idle/discard decisions in isolation (#74) --
 * [LocalCleanupModelHolder] itself can't run in a plain JVM test because it constructs
 * [LlamaCppInference], whose companion `init` loads the native library. Same split as
 * [CleanupWaterfallCursor] (tested here without its Android reset triggers) and
 * [LlamaCompletionAccumulator].
 */
class LocalCleanupModelSlotTest {

    @Test fun `an empty slot needs a load for any path`() {
        val slot = LocalCleanupModelSlot()
        assertTrue(slot.needsReload("/models/qwen.gguf"))
    }

    @Test fun `the held model is reused for the same path`() {
        val slot = LocalCleanupModelSlot()
        slot.recordLoaded("/models/qwen.gguf", nowMs = 1_000L)
        assertFalse(slot.needsReload("/models/qwen.gguf"))
    }

    @Test fun `a model-path change forces a reload`() {
        // The user switched cleanup models in Settings between dictations.
        val slot = LocalCleanupModelSlot()
        slot.recordLoaded("/models/qwen.gguf", nowMs = 1_000L)
        assertTrue(slot.needsReload("/models/smollm2.gguf"))
    }

    @Test fun `a released slot needs a fresh load even for the previous path`() {
        // Covers both explicit release (service destroy, onTrimMemory) and the
        // release-on-exception path: after a discard, nothing may be trusted as loaded.
        val slot = LocalCleanupModelSlot()
        slot.recordLoaded("/models/qwen.gguf", nowMs = 1_000L)
        slot.recordReleased()
        assertTrue(slot.needsReload("/models/qwen.gguf"))
    }

    @Test fun `idle expiry fires only once the full unload window has passed`() {
        val slot = LocalCleanupModelSlot(idleUnloadMs = 10_000L)
        slot.recordLoaded("/models/qwen.gguf", nowMs = 1_000L)
        assertFalse(slot.idleExpired(nowMs = 10_999L))
        assertTrue(slot.idleExpired(nowMs = 11_000L))
    }

    @Test fun `a use inside the window renews the idle clock`() {
        // Mirrors the holder's scheduled check re-verifying under the lock: a dictation that
        // lands after the timer was armed must keep the model loaded.
        val slot = LocalCleanupModelSlot(idleUnloadMs = 10_000L)
        slot.recordLoaded("/models/qwen.gguf", nowMs = 1_000L)
        slot.recordUsed(nowMs = 9_000L)
        assertFalse(slot.idleExpired(nowMs = 11_000L))
        assertTrue(slot.idleExpired(nowMs = 19_000L))
    }

    @Test fun `an empty slot never reports idle expiry`() {
        val slot = LocalCleanupModelSlot(idleUnloadMs = 10_000L)
        assertFalse(slot.idleExpired(nowMs = Long.MAX_VALUE))
        slot.recordLoaded("/models/qwen.gguf", nowMs = 1_000L)
        slot.recordReleased()
        assertFalse(slot.idleExpired(nowMs = Long.MAX_VALUE))
    }

    // ---- failure policy (#325): a slow chunk must not cost a reload ----------------------------

    @Test fun `a failed generation whose native state was rolled back keeps the model`() {
        val slot = LocalCleanupModelSlot()
        slot.recordLoaded("/models/qwen.gguf", nowMs = 1_000L)
        assertFalse("deadline abort / cancel / piece cap must not trigger a reload", slot.recordFailure(nativeResetOk = true))
        assertFalse(slot.needsReload("/models/qwen.gguf"))
    }

    @Test fun `a failed rollback drops the model`() {
        val slot = LocalCleanupModelSlot()
        slot.recordLoaded("/models/qwen.gguf", nowMs = 1_000L)
        assertTrue(slot.recordFailure(nativeResetOk = false))
    }

    @Test fun `a run of failures with no success in between eventually drops the model`() {
        val slot = LocalCleanupModelSlot()
        slot.recordLoaded("/models/qwen.gguf", nowMs = 1_000L)
        repeat(LocalCleanupModelSlot.MAX_CONSECUTIVE_FAILURES - 1) { assertFalse(slot.recordFailure(true)) }
        assertTrue(slot.recordFailure(true))
    }

    @Test fun `a success between failures resets the run`() {
        val slot = LocalCleanupModelSlot()
        slot.recordLoaded("/models/qwen.gguf", nowMs = 1_000L)
        repeat(LocalCleanupModelSlot.MAX_CONSECUTIVE_FAILURES - 1) { slot.recordFailure(true) }
        slot.recordSuccess(nowMs = 2_000L)
        repeat(LocalCleanupModelSlot.MAX_CONSECUTIVE_FAILURES - 1) { assertFalse(slot.recordFailure(true)) }
    }

    @Test fun `releasing the model clears the failure run`() {
        val slot = LocalCleanupModelSlot()
        slot.recordLoaded("/models/qwen.gguf", nowMs = 1_000L)
        repeat(LocalCleanupModelSlot.MAX_CONSECUTIVE_FAILURES - 1) { slot.recordFailure(true) }
        slot.recordReleased()
        slot.recordLoaded("/models/qwen.gguf", nowMs = 3_000L)
        assertFalse(slot.recordFailure(true))
    }

    @Test fun `a success renews the idle clock`() {
        val slot = LocalCleanupModelSlot(idleUnloadMs = 10_000L)
        slot.recordLoaded("/models/qwen.gguf", nowMs = 1_000L)
        slot.recordSuccess(nowMs = 9_000L)
        assertFalse(slot.idleExpired(nowMs = 11_000L))
    }
}
