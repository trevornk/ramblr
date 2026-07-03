package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for [CleanupCredentialStore] that doesn't need Android/Keystore. */
class CleanupCredentialStoreTest {

    @Test fun `maskForDisplay is blank for a blank value`() {
        assertEquals("", CleanupCredentialStore.maskForDisplay(""))
    }

    @Test fun `maskForDisplay shows only the last 4 characters for a long value`() {
        assertEquals("***cdef", CleanupCredentialStore.maskForDisplay("sk-abcdef"))
    }

    @Test fun `maskForDisplay does not crash on a short value`() {
        assertEquals("***", CleanupCredentialStore.maskForDisplay("ab"))
    }
}

class CleanupStepTest {

    @Test fun `legacy step has no credential slot`() {
        val step = CleanupStep(CleanupStepGroup.LEGACY, "gpt-4o-mini")
        assertEquals(null, step.credentialSlot())
    }

    @Test fun `omniroute step maps to the omniroute credential slot`() {
        val step = CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6")
        assertEquals(CleanupCredentialSlot.OMNIROUTE, step.credentialSlot())
    }

    @Test fun `openai direct step maps to the openai direct credential slot`() {
        val step = CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini")
        assertEquals(CleanupCredentialSlot.OPENAI_DIRECT, step.credentialSlot())
    }

    @Test fun `anthropic direct step maps to the anthropic direct credential slot`() {
        val step = CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5")
        assertEquals(CleanupCredentialSlot.ANTHROPIC_DIRECT, step.credentialSlot())
    }

    @Test fun `local llm step has no credential slot, same as legacy (#37)`() {
        val step = CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive)
        assertEquals(null, step.credentialSlot())
    }

    @Test fun `default waterfall is a single legacy step using the existing default model`() {
        val waterfall = CleanupWaterfall.LEGACY_SINGLE_STEP
        assertEquals(1, waterfall.steps.size)
        assertEquals(CleanupStepGroup.LEGACY, waterfall.steps[0].group)
        assertEquals(PostProcessor.DEFAULT_MODEL, waterfall.steps[0].model)
    }
}

/** Pure classification for the dictation-history "paid fallback" badge (#33): only the two
 *  direct-provider groups are pay-per-token from the user's own wallet. */
class CleanupStepGroupIsPaidFallbackTest {

    @Test fun `direct OpenAI and direct Anthropic are paid fallback groups`() {
        assertTrue(CleanupStepGroup.OPENAI_DIRECT.isPaidFallback())
        assertTrue(CleanupStepGroup.ANTHROPIC_DIRECT.isPaidFallback())
    }

    @Test fun `OmniRoute is not a paid fallback group despite being a network fallback, since it is subscription-billed`() {
        assertFalse(CleanupStepGroup.OMNIROUTE.isPaidFallback())
    }

    @Test fun `legacy and local groups are not paid fallback groups`() {
        assertFalse(CleanupStepGroup.LEGACY.isPaidFallback())
        assertFalse(CleanupStepGroup.LOCAL_LLM.isPaidFallback())
    }
}
