package com.trevornk.ramblr

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * JSON <-> [ModelCatalogEntry] (de)serialization for the remote-updatable model catalog (#98).
 * Kept separate from [ModelCatalogResolver]'s fallback-chain logic so the wire format and the
 * bundled/cached/fetched precedence rules can each be tested in isolation -- mirrors
 * [ProviderChainStore]'s serialize()/deserialize() split from [ProviderChain] itself.
 *
 * The JSON is deliberately plain public data (model ids, display names, descriptions, list
 * prices) -- no secrets, no credentials, safe to host in a public repo file or gist and fetch
 * unauthenticated at runtime (see [ModelCatalogFetcher]).
 */
object ModelCatalogJson {
    fun serialize(entries: List<ModelCatalogEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("provider", entry.provider.name)
                put("modelId", entry.modelId)
                put("displayName", entry.displayName)
                put("description", entry.description)
                put("tier", entry.tier.name)
                put("useCase", entry.useCase.name)
                put("costPer1MInputUsd", entry.costPer1MInputUsd)
                put("costPer1MOutputUsd", entry.costPer1MOutputUsd)
            })
        }
        return array.toString()
    }

    /**
     * Parses [raw] into a full list of entries, or null if it's blank/malformed/empty, or any
     * single entry fails to parse (an unknown [ProviderKind]/[ModelTier]/[ModelUseCase] name,
     * a missing required field, etc.) -- deliberately all-or-nothing rather than skipping bad
     * entries silently, so a corrupted cache or a malformed remote payload cleanly falls back
     * to the next link in [ModelCatalogResolver]'s chain instead of serving a partially-broken
     * catalog. An empty (but validly-parsed) array is treated as malformed too: a real catalog
     * should never be intentionally empty, so this is far more likely a bad response body than
     * a deliberate "no models" state.
     */
    fun deserialize(raw: String?): List<ModelCatalogEntry>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val array = JSONArray(raw)
            if (array.length() == 0) return null
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ModelCatalogEntry(
                    provider = ProviderKind.valueOf(obj.getString("provider")),
                    modelId = obj.getString("modelId"),
                    displayName = obj.getString("displayName"),
                    description = obj.getString("description"),
                    tier = ModelTier.valueOf(obj.getString("tier")),
                    useCase = ModelUseCase.valueOf(obj.getString("useCase")),
                    costPer1MInputUsd = obj.getDouble("costPer1MInputUsd"),
                    costPer1MOutputUsd = obj.getDouble("costPer1MOutputUsd"),
                )
            }
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Pure fallback-chain + query logic for the model catalog (#98): bundled default -> cached
 * (previously-fetched-and-saved) copy -> fresh remote fetch. Split out from [ModelCatalogStore]
 * (the Context/network-owning layer) the same way [LocalCleanupModelSlot] is split from
 * [LocalCleanupModelHolder] -- so the actual precedence rule is unit-testable without touching
 * Android or the network.
 */
object ModelCatalogResolver {
    /**
     * Resolves which catalog to actually use given [bundled] (always available, ships in the
     * APK), an optional [cached] copy (a previously successful fetch, persisted locally), and
     * an optional [fresh] fetch result from this call (null if no fetch was attempted or it
     * failed). Precedence: a successful [fresh] fetch always wins (it's the most current data);
     * otherwise fall back to [cached] if present; otherwise fall back to [bundled]. This is the
     * exact "bundled default -> cached -> fresh fetch" chain from #98, expressed with fresh
     * fetch checked first because it's the *most preferred* source when available, not because
     * it's chronologically first.
     */
    fun resolve(
        bundled: List<ModelCatalogEntry>,
        cached: List<ModelCatalogEntry>?,
        fresh: List<ModelCatalogEntry>?,
    ): List<ModelCatalogEntry> = fresh ?: cached ?: bundled

    /** True when [lastFetchedAtMs] is older than [ttlMs] (or the catalog was never fetched --
     *  [lastFetchedAtMs] null/0), i.e. a fresh remote fetch is worth attempting. */
    fun isCacheStale(lastFetchedAtMs: Long?, nowMs: Long, ttlMs: Long): Boolean =
        lastFetchedAtMs == null || nowMs - lastFetchedAtMs >= ttlMs

    /** Entries curated for [kind], ordered [ModelTier.RECOMMENDED] first, then [ModelTier.GOOD],
     *  then [ModelTier.ADVANCED] -- the picker's natural "best choice first" reading order. */
    fun entriesFor(catalog: List<ModelCatalogEntry>, kind: ProviderKind): List<ModelCatalogEntry> =
        catalog.filter { it.provider == kind }.sortedBy { it.tier.ordinal }

    /** Entries curated for [kind] that are actually usable for [useCase] -- e.g. the
     *  Transcription picker for GEMINI should exclude a hypothetical Gemini entry tagged
     *  cleanup-only. */
    fun entriesFor(catalog: List<ModelCatalogEntry>, kind: ProviderKind, useCase: ModelUseCase): List<ModelCatalogEntry> =
        entriesFor(catalog, kind).filter { entry ->
            when (useCase) {
                ModelUseCase.CLEANUP -> entry.useCase.supportsCleanup()
                ModelUseCase.TRANSCRIPTION -> entry.useCase.supportsTranscription()
                ModelUseCase.BOTH -> entry.useCase.supportsCleanup() || entry.useCase.supportsTranscription()
            }
        }

    /** The catalog's top (first RECOMMENDED, else first GOOD, else first at all) entry for
     *  [kind], or null if [kind] has no curated entries at all -- used to prefill a sane default
     *  model id when a user adds a new chain entry of this kind (#98), e.g. defaulting a fresh
     *  OmniRoute entry to its "-latest" alias instead of a blank field. */
    fun recommendedEntryFor(catalog: List<ModelCatalogEntry>, kind: ProviderKind): ModelCatalogEntry? =
        entriesFor(catalog, kind).firstOrNull()

    /** True when [modelId] matches a curated entry for [kind] -- false means it's either blank
     *  or a value only reachable through the hidden advanced escape hatch (a retired id, a typo,
     *  or a deliberately off-catalog choice). Used by the picker to decide whether to
     *  pre-select a catalog radio option or fall back to showing the advanced field expanded. */
    fun isCatalogModel(catalog: List<ModelCatalogEntry>, kind: ProviderKind, modelId: String): Boolean =
        entriesFor(catalog, kind).any { it.modelId == modelId }

    /** Short human label for [tier], used as the picker's tier badge text. */
    fun tierBadge(tier: ModelTier): String = when (tier) {
        ModelTier.RECOMMENDED -> "Recommended"
        ModelTier.GOOD -> "Good"
        ModelTier.ADVANCED -> "Advanced"
    }
}
