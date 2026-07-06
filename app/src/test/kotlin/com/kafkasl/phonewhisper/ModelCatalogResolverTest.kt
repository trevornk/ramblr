package com.kafkasl.phonewhisper

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

    @Test fun `every OmniRoute entry uses an alias id -- latest or auto- not a pinned version string`() {
        val omniRouteEntries = ModelCatalogResolver.entriesFor(BUNDLED_DEFAULT_MODEL_CATALOG, ProviderKind.OMNIROUTE)
        assertTrue(omniRouteEntries.isNotEmpty())
        assertTrue(omniRouteEntries.all { it.modelId.contains("-latest") || it.modelId.startsWith("auto/") })
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
        assertEquals("gemini-2.5-flash-lite", recommended?.modelId)
        assertTrue(recommended!!.useCase.supportsTranscription())
    }

    @Test fun `the catalog round-trips through JSON serialization unchanged`() {
        val json = ModelCatalogJson.serialize(BUNDLED_DEFAULT_MODEL_CATALOG)
        assertEquals(BUNDLED_DEFAULT_MODEL_CATALOG, ModelCatalogJson.deserialize(json))
    }
}
