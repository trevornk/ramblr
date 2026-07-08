package com.trevornk.ramblr

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

class ProviderChainUsesLocalLlmTest {

    @Test fun `a single LOCAL entry uses local llm`() {
        assertTrue(ProviderChain(listOf(ProviderChainEntry(ProviderKind.LOCAL, "m"))).usesLocalLlm())
    }

    @Test fun `mixing local and network entries uses local llm`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.LOCAL, "m"),
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini"),
            )
        )
        assertTrue(chain.usesLocalLlm())
    }

    @Test fun `an empty chain does not use local llm`() {
        assertFalse(ProviderChain(emptyList()).usesLocalLlm())
    }

    @Test fun `the default chain does not use local llm`() {
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

/** Covers [ProviderChain.withLocalFloor] (#37 follow-up, real regression Trevor hit live):
 *  Cleanup's simple Local/Cloud picker previously persisted "Local" as a full chain OVERWRITE,
 *  which silently deleted every configured cloud provider entry. These tests pin the
 *  non-destructive replacement: add/update only the LOCAL entry, leave every other entry alone. */
class ProviderChainWithLocalFloorTest {
    @Test fun `appends a LOCAL entry to an empty chain`() {
        val chain = ProviderChain(emptyList()).withLocalFloor("lfm2.5-350m-q4_0")
        assertEquals(listOf(ProviderChainEntry(ProviderKind.LOCAL, "lfm2.5-350m-q4_0")), chain.entries)
    }

    @Test fun `appends a LOCAL entry after existing cloud entries, never removing them`() {
        val openai = ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini")
        val anthropic = ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5")
        val chain = ProviderChain(listOf(openai, anthropic)).withLocalFloor("lfm2.5-350m-q4_0")

        assertEquals(
            listOf(openai, anthropic, ProviderChainEntry(ProviderKind.LOCAL, "lfm2.5-350m-q4_0")),
            chain.entries,
        )
    }

    @Test fun `replaces an existing LOCAL entry in place instead of duplicating or moving it`() {
        val openai = ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini")
        val oldLocal = ProviderChainEntry(ProviderKind.LOCAL, "old-model")
        val anthropic = ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5")
        val chain = ProviderChain(listOf(openai, oldLocal, anthropic)).withLocalFloor("new-model")

        assertEquals(
            listOf(openai, ProviderChainEntry(ProviderKind.LOCAL, "new-model"), anthropic),
            chain.entries,
        )
    }

    @Test fun `switching Local models updates the floor without touching cloud entries`() {
        val openai = ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini")
        val chain = ProviderChain(listOf(openai)).withLocalFloor("model-a").withLocalFloor("model-b")

        assertEquals(
            listOf(openai, ProviderChainEntry(ProviderKind.LOCAL, "model-b")),
            chain.entries,
        )
    }
}

class HasConfiguredCloudTranscriptionTest {
    private val allConfigured: (ProviderKind) -> Boolean = { true }
    private val noneConfigured: (ProviderKind) -> Boolean = { false }

    @Test fun `true when a configured non-LOCAL transcription provider is present (M8)`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash")))
        assertTrue(hasConfiguredCloudTranscription(chain, allConfigured))
    }

    @Test fun `a Gemini-only chain counts, not just OpenAI (M8)`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash")))
        assertTrue(hasConfiguredCloudTranscription(chain) { it == ProviderKind.GEMINI })
    }

    @Test fun `false when the only transcription provider is LOCAL`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.LOCAL, "model-a")))
        assertFalse(hasConfiguredCloudTranscription(chain, allConfigured))
    }

    @Test fun `false when a cloud transcription provider is present but unconfigured`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "whisper-1")))
        assertFalse(hasConfiguredCloudTranscription(chain, noneConfigured))
    }

    @Test fun `false when the only cloud entry is cleanup-only (Anthropic cannot transcribe)`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5")))
        assertFalse(hasConfiguredCloudTranscription(chain, allConfigured))
    }
}
