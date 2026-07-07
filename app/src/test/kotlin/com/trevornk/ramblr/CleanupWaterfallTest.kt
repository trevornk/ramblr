package com.trevornk.ramblr

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

    @Test fun `each credential slot maps to its own distinct pref key`() {
        val keys = CleanupCredentialSlot.values().map { CleanupCredentialStore.prefKeyFor(it) }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("omniroute_key", CleanupCredentialStore.prefKeyFor(CleanupCredentialSlot.OMNIROUTE))
        assertEquals("openai_direct_key", CleanupCredentialStore.prefKeyFor(CleanupCredentialSlot.OPENAI_DIRECT))
        assertEquals("anthropic_direct_key", CleanupCredentialStore.prefKeyFor(CleanupCredentialSlot.ANTHROPIC_DIRECT))
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

class CleanupWaterfallIsLocalOnlyTest {

    @Test fun `a single LOCAL_LLM step is local only`() {
        assertTrue(CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, "qwen2.5-0.5b-instruct-q4_k_m"))).isLocalOnly())
    }

    @Test fun `multiple LOCAL_LLM steps are still local only`() {
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, "qwen2.5-1.5b-instruct-q4_k_m"),
                CleanupStep(CleanupStepGroup.LOCAL_LLM, "smollm2-360m-instruct-q4_k_m"),
            )
        )
        assertTrue(waterfall.isLocalOnly())
    }

    @Test fun `a waterfall mixing local and any network group is not local only`() {
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, "qwen2.5-0.5b-instruct-q4_k_m"),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            )
        )
        assertFalse(waterfall.isLocalOnly())
    }

    @Test fun `the default legacy waterfall is not local only`() {
        assertFalse(CleanupWaterfall.LEGACY_SINGLE_STEP.isLocalOnly())
    }

    @Test fun `an empty waterfall is not local only`() {
        assertFalse(CleanupWaterfall(emptyList()).isLocalOnly())
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
        assertFalse(CleanupWaterfall.LEGACY_SINGLE_STEP.usesLocalLlm())
        assertFalse(CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"))).usesLocalLlm())
    }

    @Test fun `an empty waterfall does not use local llm`() {
        assertFalse(CleanupWaterfall(emptyList()).usesLocalLlm())
    }
}
