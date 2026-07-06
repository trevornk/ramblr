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
}
