package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupStepTest {

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

    @Test fun `local llm step has no credential slot (#37)`() {
        val step = CleanupStep(CleanupStepGroup.LOCAL_LLM, LocalCleanupProvider.MODEL.archive)
        assertEquals(null, step.credentialSlot())
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

    @Test fun `local group is not a paid fallback group`() {
        assertFalse(CleanupStepGroup.LOCAL_LLM.isPaidFallback())
    }
}

class CleanupWaterfallUsesLocalLlmTest {

    @Test fun `a single LOCAL_LLM step uses local llm`() {
        assertTrue(CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, "qwen2.5-0.5b-instruct-q4_k_m"))).usesLocalLlm())
    }

    @Test fun `a waterfall mixing local and network steps still uses local llm`() {
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.LOCAL_LLM, "qwen2.5-0.5b-instruct-q4_k_m"),
            )
        )
        assertTrue(waterfall.usesLocalLlm())
    }

    @Test fun `an all-cloud waterfall does not use local llm`() {
        assertFalse(CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"))).usesLocalLlm())
        assertFalse(CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"))).usesLocalLlm())
    }

    @Test fun `an empty waterfall does not use local llm`() {
        assertFalse(CleanupWaterfall(emptyList()).usesLocalLlm())
    }
}
