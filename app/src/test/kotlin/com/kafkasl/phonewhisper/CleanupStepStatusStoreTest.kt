package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupStepStatusStoreTest {

    private val omniStep = CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6")
    private val openaiStep = CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini", baseUrlOverride = "https://example.com/v1")

    @Test fun `keyFor is stable across steps with identical fields`() {
        assertEquals(CleanupStepStatusStore.keyFor(omniStep), CleanupStepStatusStore.keyFor(omniStep.copy()))
    }

    @Test fun `keyFor distinguishes steps that differ only by baseUrlOverride`() {
        val withOverride = openaiStep
        val withoutOverride = openaiStep.copy(baseUrlOverride = null)
        assertTrue(CleanupStepStatusStore.keyFor(withOverride) != CleanupStepStatusStore.keyFor(withoutOverride))
    }

    @Test fun `round trips a status map through serialize and parse`() {
        val statuses = mapOf(
            CleanupStepStatusStore.keyFor(omniStep) to CleanupStepHealth.SUCCESS,
            CleanupStepStatusStore.keyFor(openaiStep) to CleanupStepHealth.FAILURE,
        )

        val parsed = CleanupStepStatusStore.parse(CleanupStepStatusStore.serialize(statuses))

        assertEquals(statuses, parsed)
    }

    @Test fun `parse returns empty map for blank or malformed input`() {
        assertEquals(emptyMap<String, CleanupStepHealth>(), CleanupStepStatusStore.parse(null))
        assertEquals(emptyMap<String, CleanupStepHealth>(), CleanupStepStatusStore.parse(""))
        assertEquals(emptyMap<String, CleanupStepHealth>(), CleanupStepStatusStore.parse("not json"))
    }

    @Test fun `parse defaults an unrecognized health value to UNTESTED instead of dropping the key`() {
        val parsed = CleanupStepStatusStore.parse("""{"some-key":"BOGUS"}""")
        assertEquals(CleanupStepHealth.UNTESTED, parsed["some-key"])
    }
}
