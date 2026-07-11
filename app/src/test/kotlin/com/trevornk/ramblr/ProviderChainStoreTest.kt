package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderChainStoreTest {

    @Test fun `round trips a multi-entry multi-kind chain through serialize and deserialize`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.OMNIROUTE, "claude/claude-sonnet-4-6"),
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", baseUrlOverride = "https://example.com/v1", transcriptionModel = "gpt-4o-transcribe"),
                ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5"),
                ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash", transcriptionModel = "gemini-3.1-flash-lite"),
                ProviderChainEntry(ProviderKind.LOCAL, "qwen2.5-0.5b-instruct-q4_k_m"),
            )
        )

        val json = ProviderChainStore.serialize(chain)
        val parsed = ProviderChainStore.deserialize(json)

        assertEquals(chain, parsed)
    }

    @Test fun `deserialize returns null for a blank string`() {
        assertNull(ProviderChainStore.deserialize(""))
        assertNull(ProviderChainStore.deserialize(null))
    }

    @Test fun `deserialize returns null for malformed json`() {
        assertNull(ProviderChainStore.deserialize("not json"))
    }

    @Test fun `deserialize returns null for an unknown kind name`() {
        assertNull(ProviderChainStore.deserialize("""[{"kind":"BOGUS","model":"m","baseUrlOverride":null}]"""))
    }

    @Test fun `an explicitly-emptied chain round-trips as zero entries, not as never-configured`() {
        val emptied = ProviderChain(emptyList())
        val parsed = ProviderChainStore.deserialize(ProviderChainStore.serialize(emptied))
        assertEquals(emptied, parsed)
    }

    @Test fun `deserialize preserves a null baseUrlOverride`() {
        val parsed = ProviderChainStore.deserialize(
            """[{"kind":"ANTHROPIC","model":"claude-haiku-4-5","baseUrlOverride":null}]"""
        )
        assertEquals(null, parsed?.entries?.get(0)?.baseUrlOverride)
    }

    @Test fun `deserialize treats a missing transcriptionModel key as null (pre-#101 saved chains)`() {
        // Regression: every chain saved before the transcriptionModel field existed has no such
        // key in its stored JSON at all -- must not throw or coerce that into an empty string.
        val parsed = ProviderChainStore.deserialize(
            """[{"kind":"OPENAI","model":"gpt-5.4-nano","baseUrlOverride":null}]"""
        )
        assertEquals(null, parsed?.entries?.get(0)?.transcriptionModel)
    }

    @Test fun `deserialize preserves an explicit transcriptionModel value`() {
        val parsed = ProviderChainStore.deserialize(
            """[{"kind":"OPENAI","model":"gpt-5.4-nano","baseUrlOverride":null,"transcriptionModel":"gpt-4o-transcribe"}]"""
        )
        assertEquals("gpt-4o-transcribe", parsed?.entries?.get(0)?.transcriptionModel)
    }

    @Test fun `normalizeLocalPosition moves a leading LOCAL entry to the end`() {
        // Regression (#98 live bug, Trevor's device): a chain persisted before addCloud existed
        // could have LOCAL first (e.g. [LOCAL, OPENAI, GEMINI, ANTHROPIC]), which made every
        // cleanup pay local-model overhead -- including its own timeout -- before ever reaching
        // a configured cloud provider. load() must self-heal this on read, not just prevent new
        // instances of the bug going forward.
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.LOCAL, "mumble-cleanup-2stage-q4_0"),
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-nano"),
                ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash-lite"),
                ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5-20251001"),
            )
        )
        val result = ProviderChainStore.normalizeLocalPosition(chain)
        assertEquals(
            listOf(ProviderKind.OPENAI, ProviderKind.GEMINI, ProviderKind.ANTHROPIC, ProviderKind.LOCAL),
            result.entries.map { it.kind },
        )
    }

    @Test fun `normalizeLocalPosition is a no-op when LOCAL is already last`() {
        val chain = ProviderChain(
            listOf(ProviderChainEntry(ProviderKind.OPENAI, "a"), ProviderChainEntry(ProviderKind.LOCAL, "local"))
        )
        assertEquals(chain, ProviderChainStore.normalizeLocalPosition(chain))
    }

    @Test fun `normalizeLocalPosition is a no-op when there is no LOCAL entry`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "a")))
        assertEquals(chain, ProviderChainStore.normalizeLocalPosition(chain))
    }
}
