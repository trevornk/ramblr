package com.kafkasl.phonewhisper

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
        assertFalse(slot.isLoaded)
        assertTrue(slot.needsReload("/models/qwen.gguf"))
    }

    @Test fun `the held model is reused for the same path`() {
        val slot = LocalCleanupModelSlot()
        slot.recordLoaded("/models/qwen.gguf", nowMs = 1_000L)
        assertTrue(slot.isLoaded)
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
        assertFalse(slot.isLoaded)
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
}
