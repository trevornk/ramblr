package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderKindCapabilityTest {

    @Test fun `openai supports both transcription and cleanup`() {
        assertTrue(ProviderKind.OPENAI.supportsTranscription())
        assertTrue(ProviderKind.OPENAI.supportsCleanup())
    }

    @Test fun `gemini supports both transcription and cleanup even though nothing calls it yet`() {
        assertTrue(ProviderKind.GEMINI.supportsTranscription())
        assertTrue(ProviderKind.GEMINI.supportsCleanup())
    }

    @Test fun `local supports both transcription and cleanup`() {
        assertTrue(ProviderKind.LOCAL.supportsTranscription())
        assertTrue(ProviderKind.LOCAL.supportsCleanup())
    }

    @Test fun `anthropic supports cleanup only -- no audio input capability at all`() {
        assertFalse(ProviderKind.ANTHROPIC.supportsTranscription())
        assertTrue(ProviderKind.ANTHROPIC.supportsCleanup())
    }

    @Test fun `omniroute supports cleanup only, not transcription, today`() {
        assertFalse(ProviderKind.OMNIROUTE.supportsTranscription())
        assertTrue(ProviderKind.OMNIROUTE.supportsCleanup())
    }

    @Test fun `every provider kind supports cleanup`() {
        ProviderKind.values().forEach { kind ->
            assertTrue("$kind should support cleanup", kind.supportsCleanup())
        }
    }
}

class ProviderChainCapableEntriesForTest {

    @Test fun `empty chain has no capable entries for either feature`() {
        val chain = ProviderChain(emptyList())
        assertEquals(emptyList<ProviderChainEntry>(), chain.capableEntriesFor(needsTranscription = true))
        assertEquals(emptyList<ProviderChainEntry>(), chain.capableEntriesFor(needsTranscription = false))
    }

    @Test fun `chain with no transcription-capable entries returns empty for transcription`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5"),
                ProviderChainEntry(ProviderKind.OMNIROUTE, "claude/claude-sonnet-4-6"),
            )
        )
        assertEquals(emptyList<ProviderChainEntry>(), chain.capableEntriesFor(needsTranscription = true))
        // both are cleanup-capable though
        assertEquals(2, chain.capableEntriesFor(needsTranscription = false).size)
    }

    @Test fun `mixed capable and incapable entries preserve order when filtered`() {
        val openai = ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini")
        val anthropic = ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5")
        val omniroute = ProviderChainEntry(ProviderKind.OMNIROUTE, "claude/claude-sonnet-4-6")
        val local = ProviderChainEntry(ProviderKind.LOCAL, "qwen2.5-0.5b-instruct-q4_k_m")
        val chain = ProviderChain(listOf(anthropic, openai, omniroute, local))

        val transcriptionCapable = chain.capableEntriesFor(needsTranscription = true)
        assertEquals(listOf(openai, local), transcriptionCapable)

        val cleanupCapable = chain.capableEntriesFor(needsTranscription = false)
        assertEquals(listOf(anthropic, openai, omniroute, local), cleanupCapable)
    }

    @Test fun `first capable entry is what callers should resolve to`() {
        val anthropic = ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5")
        val gemini = ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash")
        val local = ProviderChainEntry(ProviderKind.LOCAL, "qwen2.5-0.5b-instruct-q4_k_m")
        val chain = ProviderChain(listOf(anthropic, gemini, local))

        assertEquals(gemini, chain.capableEntriesFor(needsTranscription = true).first())
        assertEquals(anthropic, chain.capableEntriesFor(needsTranscription = false).first())
    }
}

class ProviderChainIsLocalOnlyAndUsesLocalLlmTest {

    @Test fun `a single LOCAL entry is local only`() {
        assertTrue(ProviderChain(listOf(ProviderChainEntry(ProviderKind.LOCAL, "m"))).isLocalOnly())
    }

    @Test fun `mixing local and network entries is not local only but does use local llm`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.LOCAL, "m"),
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini"),
            )
        )
        assertFalse(chain.isLocalOnly())
        assertTrue(chain.usesLocalLlm())
    }

    @Test fun `an empty chain is neither local only nor uses local llm`() {
        val chain = ProviderChain(emptyList())
        assertFalse(chain.isLocalOnly())
        assertFalse(chain.usesLocalLlm())
    }

    @Test fun `the default chain is not local only and does not use local llm`() {
        assertFalse(ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY.isLocalOnly())
        assertFalse(ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY.usesLocalLlm())
    }
}

class ProviderChainDefaultTest {
    @Test fun `default chain is a single openai entry using the existing default model`() {
        val chain = ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY
        assertEquals(1, chain.entries.size)
        assertEquals(ProviderKind.OPENAI, chain.entries[0].kind)
        assertEquals(PostProcessor.DEFAULT_MODEL, chain.entries[0].model)
    }
}
