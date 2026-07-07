package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleCleanupChoiceTest {

    @Test fun `fresh-install default legacy step reads as CLOUD`() {
        assertEquals(SimpleCleanupChoice.CLOUD, simpleCleanupChoiceFor(CleanupWaterfall.LEGACY_SINGLE_STEP))
    }

    @Test fun `single LOCAL_LLM step reads as LOCAL`() {
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, "qwen2.5-0.5b-instruct-q4_k_m")))
        assertEquals(SimpleCleanupChoice.LOCAL, simpleCleanupChoiceFor(waterfall))
    }

    @Test fun `single LEGACY step reads as CLOUD`() {
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LEGACY, "gpt-4o-mini")))
        assertEquals(SimpleCleanupChoice.CLOUD, simpleCleanupChoiceFor(waterfall))
    }

    @Test fun `single step of another group reads as CUSTOM`() {
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6")))
        assertEquals(SimpleCleanupChoice.CUSTOM, simpleCleanupChoiceFor(waterfall))
    }

    @Test fun `multiple steps read as CUSTOM regardless of group`() {
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, "qwen2.5-0.5b-instruct-q4_k_m"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        assertEquals(SimpleCleanupChoice.CUSTOM, simpleCleanupChoiceFor(waterfall))
    }

    @Test fun `empty step list reads as CUSTOM`() {
        assertEquals(SimpleCleanupChoice.CUSTOM, simpleCleanupChoiceFor(CleanupWaterfall(emptyList())))
    }
}

/** #37/#52 follow-up: [simpleCleanupChoiceForChain] is the ProviderChain-native equivalent of
 *  [simpleCleanupChoiceFor] -- it's what CleanupActivity's Local/Cloud picker now actually reads/
 *  writes against, since the legacy CleanupWaterfallStore stopped mattering once the live
 *  cleanup path moved to ProviderChainStore (#95 Phase 2). This is the exact bug Trevor caught
 *  live on-device: selecting Local in Settings appeared to do nothing, because it was writing to
 *  a store nothing downstream reads anymore. */
class SimpleCleanupChoiceForChainTest {

    @Test fun `single LOCAL entry reads as LOCAL`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.LOCAL, "lfm2.5-350m-q4_0")))
        assertEquals(SimpleCleanupChoice.LOCAL, simpleCleanupChoiceForChain(chain))
    }

    @Test fun `single OPENAI entry reads as CLOUD`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-nano")))
        assertEquals(SimpleCleanupChoice.CLOUD, simpleCleanupChoiceForChain(chain))
    }

    @Test fun `single entry of another kind reads as CUSTOM`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5-20251001")))
        assertEquals(SimpleCleanupChoice.CUSTOM, simpleCleanupChoiceForChain(chain))
    }

    @Test fun `multiple entries read as CUSTOM regardless of kind`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-nano"),
                ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash-lite"),
            )
        )
        assertEquals(SimpleCleanupChoice.CUSTOM, simpleCleanupChoiceForChain(chain))
    }

    @Test fun `empty chain reads as CUSTOM`() {
        assertEquals(SimpleCleanupChoice.CUSTOM, simpleCleanupChoiceForChain(ProviderChain(emptyList())))
    }

    @Test fun `default single-OpenAI chain reads as CLOUD`() {
        // ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY is the fresh-install default -- must classify
        // the same way CleanupWaterfall.LEGACY_SINGLE_STEP does for the legacy function, so a
        // fresh install's Cleanup screen shows "Cloud" selected, not an unexpected CUSTOM state.
        assertEquals(SimpleCleanupChoice.CLOUD, simpleCleanupChoiceForChain(ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY))
    }
}
