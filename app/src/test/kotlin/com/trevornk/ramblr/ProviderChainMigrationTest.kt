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
        // gpt-5.4-nano was this test's stand-in custom id until v4 made it a genuinely
        // superseded shipped default -- use an id that was never shipped instead.
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "my-finetuned-cleanup-model")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals("my-finetuned-cleanup-model", migrated.entries[0].model)
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

    // --- v2 (#101/#102): transcriptionModel seeding ---

    @Test fun `seeds a null transcriptionModel with the current OpenAI transcription default`() {
        // gpt-5.6-luna is the current OpenAI cleanup default (v4) -- an id the migration leaves
        // alone, so this test stays pinned to the transcriptionModel-seeding behavior only.
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.6-luna")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(TranscriberClient.DEFAULT_MODEL, migrated.entries[0].transcriptionModel)
    }

    @Test fun `seeds a null transcriptionModel with the current Gemini transcription default`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.1-flash-lite")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(GeminiTranscriberClient.DEFAULT_MODEL, migrated.entries[0].transcriptionModel)
    }

    @Test fun `does not overwrite an already-set transcriptionModel`() {
        val chain = ProviderChain(listOf(
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.6-luna", transcriptionModel = "whisper-1"),
        ))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals("whisper-1", migrated.entries[0].transcriptionModel)
    }

    @Test fun `does not seed transcriptionModel for kinds that don't support transcription`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5-20251001")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(null, migrated.entries[0].transcriptionModel)
    }

    @Test fun `does not seed transcriptionModel for LOCAL even though it may share a kind check elsewhere`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.LOCAL, "some-local-model")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(null, migrated.entries[0].transcriptionModel)
    }

    @Test fun `both the superseded-model rewrite and transcriptionModel seeding apply together in one pass`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(PostProcessor.DEFAULT_MODEL, migrated.entries[0].model)
        assertEquals(TranscriberClient.DEFAULT_MODEL, migrated.entries[0].transcriptionModel)
    }

    // --- v3 (2026-07-31): gpt-4o-transcribe -> gpt-transcribe ---

    @Test fun `rewrites a superseded gpt-4o-transcribe model field to gpt-transcribe`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-transcribe")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals("gpt-transcribe", migrated.entries[0].model)
        assertEquals(TranscriberClient.DEFAULT_MODEL, migrated.entries[0].model)
    }

    @Test fun `rewrites a v2-seeded gpt-4o-transcribe transcriptionModel to gpt-transcribe`() {
        val chain = ProviderChain(listOf(
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.6-luna", transcriptionModel = "gpt-4o-transcribe"),
        ))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals("gpt-5.6-luna", migrated.entries[0].model) // cleanup model untouched
        assertEquals("gpt-transcribe", migrated.entries[0].transcriptionModel)
    }

    @Test fun `does NOT rewrite a deliberate whisper-1 transcriptionModel -- only the seeded default is superseded`() {
        val chain = ProviderChain(listOf(
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.6-luna", transcriptionModel = "whisper-1"),
        ))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals("whisper-1", migrated.entries[0].transcriptionModel)
    }

    @Test fun `an already-current gpt-transcribe transcriptionModel is untouched`() {
        val chain = ProviderChain(listOf(
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.6-luna", transcriptionModel = "gpt-transcribe"),
        ))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(chain, migrated)
    }

    @Test fun `preserves baseUrlOverride when rewriting the transcriptionModel`() {
        val chain = ProviderChain(listOf(
            ProviderChainEntry(
                ProviderKind.OPENAI, "gpt-5.6-luna",
                baseUrlOverride = "https://example.com/v1",
                transcriptionModel = "gpt-4o-transcribe",
            ),
        ))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals("gpt-transcribe", migrated.entries[0].transcriptionModel)
        assertEquals("https://example.com/v1", migrated.entries[0].baseUrlOverride)
    }

    // --- v4 (2026-08-25, #194): cleanup-default refresh ---

    @Test fun `rewrites a superseded gpt-5-4-nano cleanup model to gpt-5-6-luna`() {
        val chain = ProviderChain(listOf(
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-nano", transcriptionModel = "gpt-transcribe"),
        ))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals("gpt-5.6-luna", migrated.entries[0].model)
        // Transcription side untouched by a cleanup-model rewrite.
        assertEquals("gpt-transcribe", migrated.entries[0].transcriptionModel)
    }

    @Test fun `rewrites a superseded gemini-3-1-flash-lite cleanup model to the current Gemini cleanup default`() {
        val chain = ProviderChain(listOf(
            ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.1-flash-lite", transcriptionModel = "gemini-3.1-flash-lite"),
        ))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals(GeminiCleanupProvider.DEFAULT_MODEL, migrated.entries[0].model)
        assertEquals("gemini-3.5-flash-lite", migrated.entries[0].model)
    }

    @Test fun `NEVER rewrites a gemini-3-1-flash-lite transcriptionModel -- the audio path deliberately stays on the faster model`() {
        // The whole point of keeping SUPERSEDED_TRANSCRIPTION_MODELS empty for this refresh:
        // gemini-3.5-flash-lite is ~3x slower on audio input, so a device's transcription model
        // (seeded or picked) must keep gemini-3.1-flash-lite even while the cleanup field of the
        // very same entry is upgraded.
        val chain = ProviderChain(listOf(
            ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.1-flash-lite", transcriptionModel = "gemini-3.1-flash-lite"),
        ))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals("gemini-3.1-flash-lite", migrated.entries[0].transcriptionModel)
        assertEquals(GeminiTranscriberClient.DEFAULT_MODEL, migrated.entries[0].transcriptionModel)
    }

    @Test fun `a Gemini entry rewritten for cleanup still seeds a null transcriptionModel with the FAST audio default`() {
        // Pre-#101 shared-field-era chain: `model` held gemini-3.1-flash-lite and
        // transcriptionModel was never set. The cleanup rewrite must not leak into the audio
        // seeding -- the seeded value comes from GeminiTranscriberClient.DEFAULT_MODEL
        // (3.1-flash-lite), not from the rewritten cleanup model.
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.1-flash-lite")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals("gemini-3.5-flash-lite", migrated.entries[0].model)
        assertEquals("gemini-3.1-flash-lite", migrated.entries[0].transcriptionModel)
    }

    @Test fun `an already-current gpt-5-6-luna cleanup model is untouched`() {
        val chain = ProviderChain(listOf(
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.6-luna", transcriptionModel = "gpt-transcribe"),
        ))
        assertEquals(chain, ProviderChainMigration.migrate(chain))
    }

    @Test fun `an already-current gemini-3-5-flash-lite cleanup model is untouched`() {
        val chain = ProviderChain(listOf(
            ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash-lite", transcriptionModel = "gemini-3.1-flash-lite"),
        ))
        assertEquals(chain, ProviderChainMigration.migrate(chain))
    }

    @Test fun `v4 does not touch a gpt-5-4-nano id on the WRONG provider kind`() {
        // Exact (kind, model) matching: an OmniRoute/custom route that happens to carry the same
        // literal id is not a shipped OpenAI default and must survive untouched.
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OMNIROUTE, "gpt-5.4-nano")))
        val migrated = ProviderChainMigration.migrate(chain)
        assertEquals("gpt-5.4-nano", migrated.entries[0].model)
    }
}
