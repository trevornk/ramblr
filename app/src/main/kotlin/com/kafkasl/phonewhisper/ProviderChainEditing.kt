package com.kafkasl.phonewhisper

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
