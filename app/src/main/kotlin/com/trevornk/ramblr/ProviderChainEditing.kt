package com.trevornk.ramblr

/**
 * Pure add/remove/reorder logic for the unified Cloud provider-chain screen (#95 Phase 3) --
 * the exact same list-editing shape as [CleanupWaterfallEditing], just operating on
 * [ProviderChainEntry] instead of [CleanupStep]. Kept as a separate object rather than making
 * [CleanupWaterfallEditing] generic: the two element types are otherwise unrelated data classes
 * (different fields would be a coincidence, not a real generalization), and duplicating ~20 lines
 * of trivial list-splicing logic is cheaper than a shared abstraction two different legacy/new
 * models would both have to fit.
 */
object ProviderChainEditing {
    /**
     * Appends [newEntry] to [entries], but keeps any existing [ProviderKind.LOCAL] entry as the
     * last item rather than letting it get stranded ahead of a newly-added cloud provider. Plain
     * list-append (`entries + newEntry`) silently broke this: [ProviderChain]'s own kdoc and
     * [ProviderChain.withLocalFloor] both document LOCAL as "a guaranteed floor beneath every
     * chain", tried last -- but if a LOCAL entry was already first (e.g. left over from an
     * earlier Local-only choice, or a fresh install's default), appending a cloud provider after
     * it never actually surfaced the new cloud provider until LOCAL exhausted its own step budget
     * and failed on every single dictation (Trevor hit this live: adding OpenAI/Gemini/Anthropic
     * providers here still ran ~4-6s of local-model overhead, including entire-waterfall-aborting
     * timeouts, before a cloud provider was ever attempted). This only reorders when a LOCAL entry
     * is already present; a chain with no LOCAL entry just gets a plain append, unchanged from
     * before.
     */
    fun addCloud(entries: List<ProviderChainEntry>, newEntry: ProviderChainEntry): List<ProviderChainEntry> {
        val localIndex = entries.indexOfFirst { it.kind == ProviderKind.LOCAL }
        return if (localIndex >= 0) {
            entries.toMutableList().apply { add(localIndex, newEntry) }
        } else {
            entries + newEntry
        }
    }

    /** Swaps [index] with its predecessor. No-op if [index] is already first or out of range. */
    fun moveUp(entries: List<ProviderChainEntry>, index: Int): List<ProviderChainEntry> {
        if (index !in entries.indices || index == 0) return entries
        return entries.toMutableList().apply { add(index - 1, removeAt(index)) }
    }

    /** Swaps [index] with its successor. No-op if [index] is already last or out of range. */
    fun moveDown(entries: List<ProviderChainEntry>, index: Int): List<ProviderChainEntry> {
        if (index !in entries.indices || index == entries.lastIndex) return entries
        return entries.toMutableList().apply { add(index + 1, removeAt(index)) }
    }

    /**
     * Like [moveUp], but swaps with the nearest preceding entry that is NOT [ProviderKind.LOCAL]
     * (skipping over it if one sits directly in between) instead of the literal predecessor.
     * [CloudProviderActivity] only ever displays cloud-capable entries -- if a LOCAL floor entry
     * happens to sit between two cloud entries (e.g. added chain order was cloud, then Local, then
     * a second cloud provider), a plain index-based [moveUp] would silently swap the tapped entry
     * past the invisible LOCAL entry instead of past its visible cloud neighbor, producing zero
     * visible change and a confusing "the button did nothing" bug. No-op if there is no earlier
     * cloud entry or [index] is out of range.
     */
    fun moveCloudUp(entries: List<ProviderChainEntry>, index: Int): List<ProviderChainEntry> {
        if (index !in entries.indices) return entries
        val precedingCloudIndex = (index - 1 downTo 0).firstOrNull { entries[it].kind != ProviderKind.LOCAL } ?: return entries
        return entries.toMutableList().apply { add(precedingCloudIndex, removeAt(index)) }
    }

    /** [moveCloudUp]'s downward counterpart: swaps with the nearest following non-LOCAL entry. */
    fun moveCloudDown(entries: List<ProviderChainEntry>, index: Int): List<ProviderChainEntry> {
        if (index !in entries.indices) return entries
        val followingCloudIndex = (index + 1..entries.lastIndex).firstOrNull { entries[it].kind != ProviderKind.LOCAL } ?: return entries
        return entries.toMutableList().apply { add(followingCloudIndex, removeAt(index)) }
    }

    /** Removes the entry at [index]. No-op if out of range. */
    fun remove(entries: List<ProviderChainEntry>, index: Int): List<ProviderChainEntry> {
        if (index !in entries.indices) return entries
        return entries.toMutableList().apply { removeAt(index) }
    }

    /** Replaces the entry at [index] with [entry]. No-op if out of range. */
    fun replace(entries: List<ProviderChainEntry>, index: Int, entry: ProviderChainEntry): List<ProviderChainEntry> {
        if (index !in entries.indices) return entries
        return entries.toMutableList().apply { set(index, entry) }
    }

    /**
     * Formats the live capability badge text for [kind] shown next to each chain row (approved
     * brainstorm doc's "Cleanup ✓ · Transcription runs on-device" mock). Every [ProviderKind]
     * supports cleanup (see [supportsCleanup]), so the cleanup half never varies; only the
     * transcription half depends on the specific kind.
     */
    fun capabilityBadgeText(kind: ProviderKind): String {
        val transcription = if (kind.supportsTranscription()) "Transcription \u2713" else "Transcription runs on-device"
        return "Cleanup \u2713 \u00b7 $transcription"
    }
}
