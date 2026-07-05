package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderChainStoreTest {

    @Test fun `round trips a multi-entry multi-kind chain through serialize and deserialize`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.OMNIROUTE, "claude/claude-sonnet-4-6"),
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", baseUrlOverride = "https://example.com/v1"),
                ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5"),
                ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash"),
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
}
