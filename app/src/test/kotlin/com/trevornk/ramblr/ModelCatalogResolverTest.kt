package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogResolverResolveTest {

    private val bundled = listOf(
        ModelCatalogEntry(ProviderKind.OPENAI, "bundled-model", "Bundled", "d", ModelTier.RECOMMENDED, ModelUseCase.CLEANUP, 0.1, 0.2)
    )
    private val cached = listOf(
        ModelCatalogEntry(ProviderKind.OPENAI, "cached-model", "Cached", "d", ModelTier.RECOMMENDED, ModelUseCase.CLEANUP, 0.1, 0.2)
    )
    private val fresh = listOf(
        ModelCatalogEntry(ProviderKind.OPENAI, "fresh-model", "Fresh", "d", ModelTier.RECOMMENDED, ModelUseCase.CLEANUP, 0.1, 0.2)
    )

    @Test fun `a successful fresh fetch always wins over cached and bundled`() {
        assertEquals(fresh, ModelCatalogResolver.resolve(bundled, cached, fresh))
    }

    @Test fun `falls back to cached when there is no fresh fetch`() {
        assertEquals(cached, ModelCatalogResolver.resolve(bundled, cached, fresh = null))
    }

    @Test fun `falls back to bundled when there is neither a fresh fetch nor a cache`() {
        assertEquals(bundled, ModelCatalogResolver.resolve(bundled, cached = null, fresh = null))
    }
}

class ModelCatalogResolverCacheStaleTest {

    @Test fun `never-fetched -- null timestamp -- is always stale`() {
        assertTrue(ModelCatalogResolver.isCacheStale(null, nowMs = 1_000_000L, ttlMs = 1000L))
    }

    @Test fun `fresh within ttl is not stale`() {
        assertFalse(ModelCatalogResolver.isCacheStale(lastFetchedAtMs = 1000L, nowMs = 1500L, ttlMs = 1000L))
    }

    @Test fun `exactly at ttl boundary counts as stale`() {
        assertTrue(ModelCatalogResolver.isCacheStale(lastFetchedAtMs = 1000L, nowMs = 2000L, ttlMs = 1000L))
    }

    @Test fun `well past ttl is stale`() {
        assertTrue(ModelCatalogResolver.isCacheStale(lastFetchedAtMs = 1000L, nowMs = 999_999L, ttlMs = 1000L))
    }
}

class ModelCatalogResolverEntriesForTest {

    private val catalog = listOf(
        ModelCatalogEntry(ProviderKind.OPENAI, "gpt-5.4-mini", "Mini", "d", ModelTier.GOOD, ModelUseCase.CLEANUP, 0.25, 2.0),
        ModelCatalogEntry(ProviderKind.OPENAI, "gpt-5.4-nano", "Nano", "d", ModelTier.RECOMMENDED, ModelUseCase.CLEANUP, 0.05, 0.4),
        ModelCatalogEntry(ProviderKind.GEMINI, "gemini-2.5-flash-lite", "Flash-Lite", "d", ModelTier.RECOMMENDED, ModelUseCase.BOTH, 0.1, 0.4),
        ModelCatalogEntry(ProviderKind.OMNIROUTE, "auto/claude-opus", "Opus alias", "d", ModelTier.ADVANCED, ModelUseCase.CLEANUP, 15.0, 75.0),
    )

    @Test fun `filters to just the requested provider kind`() {
        val entries = ModelCatalogResolver.entriesFor(catalog, ProviderKind.OPENAI)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.provider == ProviderKind.OPENAI })
    }

    @Test fun `orders recommended before good before advanced`() {
        val entries = ModelCatalogResolver.entriesFor(catalog, ProviderKind.OPENAI)
        assertEquals("gpt-5.4-nano", entries[0].modelId) // RECOMMENDED
        assertEquals("gpt-5.4-mini", entries[1].modelId) // GOOD
    }

    @Test fun `returns empty list for a provider kind with no curated entries`() {
        assertTrue(ModelCatalogResolver.entriesFor(catalog, ProviderKind.ANTHROPIC).isEmpty())
    }

    @Test fun `entriesFor with a use case filters entries that do not support it`() {
        val transcriptionEntries = ModelCatalogResolver.entriesFor(catalog, ProviderKind.OPENAI, ModelUseCase.TRANSCRIPTION)
        assertTrue(transcriptionEntries.isEmpty()) // both OpenAI entries are CLEANUP-only in this fixture

        val geminiTranscription = ModelCatalogResolver.entriesFor(catalog, ProviderKind.GEMINI, ModelUseCase.TRANSCRIPTION)
        assertEquals(1, geminiTranscription.size) // BOTH supports transcription too
    }

    @Test fun `recommendedEntryFor returns the first entry after tier sort`() {
        assertEquals("gpt-5.4-nano", ModelCatalogResolver.recommendedEntryFor(catalog, ProviderKind.OPENAI)?.modelId)
        assertEquals("auto/claude-opus", ModelCatalogResolver.recommendedEntryFor(catalog, ProviderKind.OMNIROUTE)?.modelId)
    }

    @Test fun `recommendedEntryFor returns null for a kind with no curated entries`() {
        assertNull(ModelCatalogResolver.recommendedEntryFor(catalog, ProviderKind.ANTHROPIC))
    }

    @Test fun `isCatalogModel is true only for a real curated model id under the right kind`() {
        assertTrue(ModelCatalogResolver.isCatalogModel(catalog, ProviderKind.OPENAI, "gpt-5.4-nano"))
        assertFalse(ModelCatalogResolver.isCatalogModel(catalog, ProviderKind.OPENAI, "gpt-3.5-turbo"))
        assertFalse(ModelCatalogResolver.isCatalogModel(catalog, ProviderKind.GEMINI, "gpt-5.4-nano"))
    }

    @Test fun `tierBadge renders the three tiers distinctly`() {
        assertEquals("Recommended", ModelCatalogResolver.tierBadge(ModelTier.RECOMMENDED))
        assertEquals("Good", ModelCatalogResolver.tierBadge(ModelTier.GOOD))
        assertEquals("Advanced", ModelCatalogResolver.tierBadge(ModelTier.ADVANCED))
    }
}

/** #104 regression: entriesFor(catalog, kind) with no use-case filter mixes cleanup and
 *  transcription-only entries for the SAME kind -- CloudProviderActivity's cleanup-model picker
 *  used the unfiltered 2-arg overload and could offer a transcription-only model (e.g.
 *  gpt-4o-transcribe) as a cleanup choice, which then fails at /v1/chat/completions call time.
 *  [ModelCatalogResolverEntriesForTest] above never caught this because its OpenAI fixture only
 *  had CLEANUP-only entries -- this fixture deliberately mixes both use cases per kind, matching
 *  the real BUNDLED_DEFAULT_MODEL_CATALOG shape (gpt-5.4-nano/mini CLEANUP + gpt-4o-transcribe/
 *  whisper-1 TRANSCRIPTION, both under OPENAI). */
class ModelCatalogResolverMixedUseCaseTest {

    private val mixedCatalog = listOf(
        ModelCatalogEntry(ProviderKind.OPENAI, "gpt-5.4-nano", "Nano", "d", ModelTier.RECOMMENDED, ModelUseCase.CLEANUP, 0.05, 0.4),
        ModelCatalogEntry(ProviderKind.OPENAI, "gpt-4o-transcribe", "GPT-4o Transcribe", "d", ModelTier.RECOMMENDED, ModelUseCase.TRANSCRIPTION, 0.006, 0.0),
        ModelCatalogEntry(ProviderKind.OPENAI, "gpt-5.4-mini", "Mini", "d", ModelTier.GOOD, ModelUseCase.CLEANUP, 0.25, 2.0),
        ModelCatalogEntry(ProviderKind.OPENAI, "whisper-1", "Whisper", "d", ModelTier.GOOD, ModelUseCase.TRANSCRIPTION, 0.006, 0.0),
    )

    @Test fun `the unfiltered 2-arg overload does mix cleanup and transcription entries -- this is why callers must always pass a use case`() {
        val entries = ModelCatalogResolver.entriesFor(mixedCatalog, ProviderKind.OPENAI)
        assertEquals(4, entries.size)
        assertTrue(entries.any { it.modelId == "gpt-4o-transcribe" }) // the exact leak #104 found
    }

    @Test fun `the CLEANUP-filtered picker for a mixed catalog excludes every transcription-only entry`() {
        val entries = ModelCatalogResolver.entriesFor(mixedCatalog, ProviderKind.OPENAI, ModelUseCase.CLEANUP)
        assertEquals(2, entries.size)
        assertTrue(entries.none { it.modelId == "gpt-4o-transcribe" || it.modelId == "whisper-1" })
        assertEquals("gpt-5.4-nano", entries[0].modelId) // RECOMMENDED first, matches CloudProviderActivity's real default
    }

    @Test fun `the TRANSCRIPTION-filtered picker for a mixed catalog excludes every cleanup-only entry`() {
        val entries = ModelCatalogResolver.entriesFor(mixedCatalog, ProviderKind.OPENAI, ModelUseCase.TRANSCRIPTION)
        assertEquals(2, entries.size)
        assertTrue(entries.none { it.modelId == "gpt-5.4-nano" || it.modelId == "gpt-5.4-mini" })
        assertEquals("gpt-4o-transcribe", entries[0].modelId) // RECOMMENDED first
    }
}

/** Sanity checks on the real bundled catalog seeded per #98's curated design decisions --
 *  guards against a future edit silently breaking the OmniRoute-latest-default or
 *  accidentally promoting Anthropic to a pushed default. */
class BundledDefaultModelCatalogTest {

    @Test fun `every entry has a non-blank model id and display name`() {
        assertTrue(BUNDLED_DEFAULT_MODEL_CATALOG.all { it.modelId.isNotBlank() && it.displayName.isNotBlank() })
    }

    @Test fun `OmniRoute's recommended cleanup default is a -latest or auto alias, not a version-pinned id`() {
        val recommended = ModelCatalogResolver.recommendedEntryFor(BUNDLED_DEFAULT_MODEL_CATALOG, ProviderKind.OMNIROUTE)
        assertTrue(recommended != null)
        assertTrue(recommended!!.modelId.contains("-latest") || recommended.modelId.startsWith("auto/"))
    }

    @Test fun `OmniRoute's RECOMMENDED cleanup entry specifically is a -latest alias, not a pinned version string`() {
        val omniRouteEntries = ModelCatalogResolver.entriesFor(BUNDLED_DEFAULT_MODEL_CATALOG, ProviderKind.OMNIROUTE)
        assertTrue(omniRouteEntries.isNotEmpty())
        val recommended = omniRouteEntries.first { it.tier == ModelTier.RECOMMENDED }
        assertTrue(recommended.modelId.contains("-latest") || recommended.modelId.startsWith("auto/"))
        // Not every OmniRoute entry needs to be an alias -- e.g. Haiku and GPT-5.4-mini have no
        // "-latest"/"auto/" alias available on OmniRoute today, so they're pinned model ids
        // (still correctly tiered GOOD to mirror their direct-provider cheap-tier equivalents).
    }
    @Test fun `Anthropic's Claude Haiku entry is GOOD, never RECOMMENDED, given the transcription gap`() {
        val anthropicEntries = ModelCatalogResolver.entriesFor(BUNDLED_DEFAULT_MODEL_CATALOG, ProviderKind.ANTHROPIC)
        assertTrue(anthropicEntries.isNotEmpty())
        assertTrue(anthropicEntries.none { it.tier == ModelTier.RECOMMENDED })
    }

    @Test fun `Anthropic has no transcription-capable catalog entries -- Claude has no audio-input capability`() {
        val anthropicTranscription = ModelCatalogResolver.entriesFor(BUNDLED_DEFAULT_MODEL_CATALOG, ProviderKind.ANTHROPIC, ModelUseCase.TRANSCRIPTION)
        assertTrue(anthropicTranscription.isEmpty())
    }

    @Test fun `OpenAI's cheapest tier -- nano -- is recommended, not the pricier mini tier`() {
        val recommended = ModelCatalogResolver.recommendedEntryFor(BUNDLED_DEFAULT_MODEL_CATALOG, ProviderKind.OPENAI)
        assertEquals("gpt-5.4-nano", recommended?.modelId)
    }

    @Test fun `Gemini's cheapest tier -- flash-lite -- is recommended and transcription-capable`() {
        val recommended = ModelCatalogResolver.recommendedEntryFor(BUNDLED_DEFAULT_MODEL_CATALOG, ProviderKind.GEMINI)
        assertEquals("gemini-3.1-flash-lite", recommended?.modelId)
        assertTrue(recommended!!.useCase.supportsTranscription())
    }

    @Test fun `the catalog round-trips through JSON serialization unchanged`() {
        val json = ModelCatalogJson.serialize(BUNDLED_DEFAULT_MODEL_CATALOG)
        assertEquals(BUNDLED_DEFAULT_MODEL_CATALOG, ModelCatalogJson.deserialize(json))
    }
}
