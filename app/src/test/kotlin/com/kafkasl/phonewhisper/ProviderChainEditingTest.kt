package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [ProviderChainEditing], especially [ProviderChainEditing.moveCloudUp]/[moveCloudDown]
 * (added alongside the CloudProviderActivity fix that stops rendering the LOCAL floor entry as a
 * removable/reorderable row -- see CloudProviderActivity.refreshChainRows kdoc): these must skip
 * over a LOCAL entry sitting between two cloud entries rather than swapping with it directly,
 * since a plain index-based moveUp/moveDown would silently swap past an entry the user can't even
 * see on this screen, producing a "the arrow button did nothing" bug.
 */
class ProviderChainEditingTest {
    private fun entry(kind: ProviderKind, model: String = "m") = ProviderChainEntry(kind, model)

    @Test fun `moveCloudUp skips over an intervening LOCAL entry`() {
        val entries = listOf(
            entry(ProviderKind.OPENAI, "a"),
            entry(ProviderKind.LOCAL, "local"),
            entry(ProviderKind.ANTHROPIC, "b"),
        )
        // Tap "up" on the ANTHROPIC entry (index 2) -- should land before OPENAI (index 0), not
        // just swap with the adjacent LOCAL entry at index 1.
        val result = ProviderChainEditing.moveCloudUp(entries, 2)
        assertEquals(listOf(ProviderKind.ANTHROPIC, ProviderKind.OPENAI, ProviderKind.LOCAL), result.map { it.kind })
    }

    @Test fun `moveCloudDown skips over an intervening LOCAL entry`() {
        val entries = listOf(
            entry(ProviderKind.OPENAI, "a"),
            entry(ProviderKind.LOCAL, "local"),
            entry(ProviderKind.ANTHROPIC, "b"),
        )
        // Tap "down" on the OPENAI entry (index 0) -- should land after ANTHROPIC (index 2), not
        // just swap with the adjacent LOCAL entry at index 1.
        val result = ProviderChainEditing.moveCloudDown(entries, 0)
        assertEquals(listOf(ProviderKind.LOCAL, ProviderKind.ANTHROPIC, ProviderKind.OPENAI), result.map { it.kind })
    }

    @Test fun `moveCloudUp is a no-op when there is no earlier cloud entry`() {
        val entries = listOf(entry(ProviderKind.LOCAL, "local"), entry(ProviderKind.OPENAI, "a"))
        val result = ProviderChainEditing.moveCloudUp(entries, 1)
        assertEquals(entries, result)
    }

    @Test fun `moveCloudDown is a no-op when there is no later cloud entry`() {
        val entries = listOf(entry(ProviderKind.OPENAI, "a"), entry(ProviderKind.LOCAL, "local"))
        val result = ProviderChainEditing.moveCloudDown(entries, 0)
        assertEquals(entries, result)
    }

    @Test fun `moveCloudUp swaps directly adjacent cloud entries with no LOCAL in between`() {
        val entries = listOf(entry(ProviderKind.OPENAI, "a"), entry(ProviderKind.ANTHROPIC, "b"))
        val result = ProviderChainEditing.moveCloudUp(entries, 1)
        assertEquals(listOf(ProviderKind.ANTHROPIC, ProviderKind.OPENAI), result.map { it.kind })
    }

    @Test fun `remove and replace are unaffected by the cloud-aware move helpers`() {
        val entries = listOf(entry(ProviderKind.OPENAI, "a"), entry(ProviderKind.LOCAL, "local"))
        assertEquals(listOf(ProviderKind.LOCAL), ProviderChainEditing.remove(entries, 0).map { it.kind })
        val replaced = ProviderChainEditing.replace(entries, 0, entry(ProviderKind.ANTHROPIC, "c"))
        assertEquals(listOf(ProviderKind.ANTHROPIC, ProviderKind.LOCAL), replaced.map { it.kind })
    }

    @Test fun `addCloud inserts a new provider ahead of an existing LOCAL entry, not after it`() {
        // Regression (#98 live bug, Trevor's device): plain list-append let a newly-added cloud
        // provider land AFTER an existing LOCAL entry, so every dictation still paid the full
        // local-model waterfall step (and its timeout) before the new cloud provider ever ran.
        val entries = listOf(entry(ProviderKind.LOCAL, "local"))
        val result = ProviderChainEditing.addCloud(entries, entry(ProviderKind.OPENAI, "gpt-5.4-nano"))
        assertEquals(listOf(ProviderKind.OPENAI, ProviderKind.LOCAL), result.map { it.kind })
    }

    @Test fun `addCloud inserts ahead of LOCAL even with other cloud entries already present`() {
        val entries = listOf(entry(ProviderKind.OPENAI, "a"), entry(ProviderKind.LOCAL, "local"))
        val result = ProviderChainEditing.addCloud(entries, entry(ProviderKind.GEMINI, "b"))
        assertEquals(listOf(ProviderKind.OPENAI, ProviderKind.GEMINI, ProviderKind.LOCAL), result.map { it.kind })
    }

    @Test fun `addCloud is a plain append when there is no LOCAL entry`() {
        val entries = listOf(entry(ProviderKind.OPENAI, "a"))
        val result = ProviderChainEditing.addCloud(entries, entry(ProviderKind.ANTHROPIC, "b"))
        assertEquals(listOf(ProviderKind.OPENAI, ProviderKind.ANTHROPIC), result.map { it.kind })
    }

    @Test fun `subtitleFor reads Not configured for a chain holding only the LOCAL floor entry`() {
        // Real bug Trevor hit live: MainActivity's Cloud row (and CleanupActivity's/
        // TranscriptionActivity's cloud link subtitle, which all share this function) said
        // "1 provider configured" with zero real cloud providers set up -- counting the LOCAL
        // floor entry as if it were a user-configured cloud provider.
        val chain = ProviderChain(listOf(entry(ProviderKind.LOCAL, "lfm2.5-350m-q4_0")))
        assertEquals("Not configured -- using on-device", CloudProviderActivity.subtitleFor(chain))
    }

    @Test fun `subtitleFor counts only cloud entries, ignoring LOCAL`() {
        val chain = ProviderChain(
            listOf(entry(ProviderKind.LOCAL, "local"), entry(ProviderKind.OPENAI, "gpt-4o-mini"))
        )
        assertEquals("1 provider configured", CloudProviderActivity.subtitleFor(chain))
    }

    @Test fun `subtitleFor pluralizes for two or more cloud entries`() {
        val chain = ProviderChain(
            listOf(
                entry(ProviderKind.OPENAI, "gpt-4o-mini"),
                entry(ProviderKind.ANTHROPIC, "claude-haiku"),
                entry(ProviderKind.LOCAL, "local"),
            )
        )
        assertEquals("2 providers configured", CloudProviderActivity.subtitleFor(chain))
    }
}
