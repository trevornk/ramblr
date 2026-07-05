package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CleanupWaterfallStoreTest {

    @Test fun `round trips a multi-step multi-group waterfall through serialize and deserialize`() {
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini", baseUrlOverride = "https://example.com/v1"),
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
            )
        )

        val json = CleanupWaterfallStore.serialize(waterfall)
        val parsed = CleanupWaterfallStore.deserialize(json)

        assertEquals(waterfall, parsed)
    }

    @Test fun `deserialize returns null for a blank string`() {
        assertNull(CleanupWaterfallStore.deserialize(""))
        assertNull(CleanupWaterfallStore.deserialize(null))
    }

    @Test fun `deserialize returns null for malformed json`() {
        assertNull(CleanupWaterfallStore.deserialize("not json"))
    }

    @Test fun `deserialize returns null for an unknown group name`() {
        assertNull(CleanupWaterfallStore.deserialize("""[{"group":"BOGUS","model":"m","baseUrlOverride":null}]"""))
    }

    @Test fun `an explicitly-emptied waterfall round-trips as zero steps, not as never-configured`() {
        // Collapsing "[]" into null re-activated legacy Cloud cleanup after the user removed
        // their last (possibly local-only) step -- a silent privacy regression (#82).
        val emptied = CleanupWaterfall(emptyList())
        val parsed = CleanupWaterfallStore.deserialize(CleanupWaterfallStore.serialize(emptied))
        assertEquals(emptied, parsed)
    }

    @Test fun `an emptied waterfall is not local-only -- callers must skip cleanup, not run it`() {
        assertEquals(false, CleanupWaterfall(emptyList()).isLocalOnly())
    }

    @Test fun `deserialize preserves a null baseUrlOverride`() {
        val parsed = CleanupWaterfallStore.deserialize(
            """[{"group":"ANTHROPIC_DIRECT","model":"claude-haiku-4-5","baseUrlOverride":null}]"""
        )
        assertEquals(null, parsed?.steps?.get(0)?.baseUrlOverride)
    }
}
