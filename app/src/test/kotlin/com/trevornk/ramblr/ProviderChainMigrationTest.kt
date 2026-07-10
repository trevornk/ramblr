package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProviderChainMigrationTest {

    @Test fun `rewrites a superseded OpenAI cleanup model to the current default`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(PostProcessor.DEFAULT_MODEL, migrated.entries[0].model)
    }

    @Test fun `rewrites a superseded OpenAI transcription model to the current default`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "whisper-1")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(TranscriberClient.DEFAULT_MODEL, migrated.entries[0].model)
    }

    @Test fun `rewrites a superseded Gemini flash model to the current default`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(GeminiCleanupProvider.DEFAULT_MODEL, migrated.entries[0].model)
    }

    @Test fun `rewrites a superseded Gemini flash-lite model to the current default`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash-lite")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(GeminiCleanupProvider.DEFAULT_MODEL, migrated.entries[0].model)
    }

    @Test fun `leaves an already-current model id untouched`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, PostProcessor.DEFAULT_MODEL)))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(PostProcessor.DEFAULT_MODEL, migrated.entries[0].model)
    }

    @Test fun `leaves a deliberate custom advanced model id untouched`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-nano")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals("gpt-5.4-nano", migrated.entries[0].model)
    }

    @Test fun `leaves LOCAL and OMNIROUTE entries untouched regardless of model id`() {
        val chain = ProviderChain(listOf(
            ProviderChainEntry(ProviderKind.LOCAL, "gpt-4o-mini"), // nonsense id, but LOCAL is never in the map
            ProviderChainEntry(ProviderKind.OMNIROUTE, "gemini/gemini-flash-lite-latest"),
        ))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(chain.entries, migrated.entries)
    }

    @Test fun `only rewrites the matching entries in a mixed chain, preserving order`() {
        val chain = ProviderChain(listOf(
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini"),
            ProviderChainEntry(ProviderKind.GEMINI, "gemini-2.5-flash-lite"),
            ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5-20251001"),
        ))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(3, migrated.entries.size)
        assertEquals(PostProcessor.DEFAULT_MODEL, migrated.entries[0].model)
        assertEquals(ProviderKind.OPENAI, migrated.entries[0].kind)
        assertEquals(GeminiCleanupProvider.DEFAULT_MODEL, migrated.entries[1].model)
        assertEquals(ProviderKind.GEMINI, migrated.entries[1].kind)
        // Anthropic Haiku was never a shipped default in the superseded map -- untouched.
        assertEquals("claude-haiku-4-5-20251001", migrated.entries[2].model)
        assertEquals(ProviderKind.ANTHROPIC, migrated.entries[2].kind)
    }

    @Test fun `preserves baseUrlOverride on a rewritten entry`() {
        val chain = ProviderChain(listOf(
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", baseUrlOverride = "https://example.com/v1"),
        ))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(PostProcessor.DEFAULT_MODEL, migrated.entries[0].model)
        assertEquals("https://example.com/v1", migrated.entries[0].baseUrlOverride)
    }

    @Test fun `an empty chain migrates to an empty chain`() {
        val chain = ProviderChain(emptyList())
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(0, migrated.entries.size)
    }

    @Test fun `migrating an already-current chain is a true no-op (equal, not just same values)`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5-20251001")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(chain, migrated)
    }
}
