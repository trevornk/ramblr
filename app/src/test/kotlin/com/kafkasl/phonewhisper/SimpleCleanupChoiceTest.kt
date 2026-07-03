package com.kafkasl.phonewhisper

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
