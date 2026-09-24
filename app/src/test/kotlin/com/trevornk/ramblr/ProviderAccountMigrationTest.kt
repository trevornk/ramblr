package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for [ProviderAccountMigration] -- the #274 migration onto per-entry
 * credentials. [ProviderAccountMigration.assignMissingIds] and
 * [ProviderAccountMigration.legacyKeyCopyPlan] are the two decision functions [runIfNeeded]
 * sequences against real storage; both are exercised directly here with no Android [android
 * .content.Context] involved.
 */
class ProviderAccountMigrationTest {

    private fun entry(kind: ProviderKind, id: String = "") = ProviderChainEntry(kind, "model", id = id)

    // --- assignMissingIds ---

    @Test fun `assigns a fresh id to every entry with a blank id`() {
        var counter = 0
        val entries = listOf(entry(ProviderKind.OPENAI), entry(ProviderKind.GEMINI))

        val result = ProviderAccountMigration.assignMissingIds(entries) { "generated-${counter++}" }

        assertEquals(listOf("generated-0", "generated-1"), result.map { it.id })
    }

    @Test fun `leaves an already-id'd entry completely untouched`() {
        val alreadyIdEntry = entry(ProviderKind.OPENAI, id = "existing-id")

        val result = ProviderAccountMigration.assignMissingIds(listOf(alreadyIdEntry)) { "should-not-be-called" }

        assertEquals(listOf(alreadyIdEntry), result)
    }

    @Test fun `re-running id assignment on an already-migrated list is a no-op`() {
        val entries = ProviderAccountMigration.assignMissingIds(
            listOf(entry(ProviderKind.OPENAI), entry(ProviderKind.ANTHROPIC)),
        )

        val secondPass = ProviderAccountMigration.assignMissingIds(entries) { "should-not-be-called" }

        assertEquals(entries, secondPass)
    }

    @Test fun `a mix of id'd and blank-id entries only assigns to the blank ones`() {
        val alreadyIdEntry = entry(ProviderKind.OPENAI, id = "keep-me")
        val blankEntry = entry(ProviderKind.GEMINI)
        var counter = 0

        val result = ProviderAccountMigration.assignMissingIds(listOf(alreadyIdEntry, blankEntry)) { "new-${counter++}" }

        assertEquals("keep-me", result[0].id)
        assertEquals("new-0", result[1].id)
    }

    @Test fun `preserves every other field of an entry whose id gets assigned`() {
        val original = ProviderChainEntry(
            ProviderKind.OPENAI,
            model = "gpt-5.4-mini",
            baseUrlOverride = "https://example.com/v1",
            transcriptionModel = "gpt-transcribe",
            enabled = false,
            useForTranscription = false,
            useForCleanup = true,
        )

        val result = ProviderAccountMigration.assignMissingIds(listOf(original)) { "new-id" }

        assertEquals(
            original.copy(id = "new-id"),
            result.single(),
        )
    }

    // --- legacyKeyCopyPlan ---

    @Test fun `copies the legacy key to every entry of that kind missing its own key`() {
        val plan = ProviderAccountMigration.legacyKeyCopyPlan(
            entryKindsMissingOwnKey = mapOf("entry-a" to ProviderKind.OPENAI, "entry-b" to ProviderKind.OPENAI),
            legacyKeysByKind = mapOf(ProviderKind.OPENAI to "sk-legacy-openai"),
        )

        assertEquals(mapOf("entry-a" to "sk-legacy-openai", "entry-b" to "sk-legacy-openai"), plan)
    }

    @Test fun `two same-kind entries both get the same legacy key, keeping them distinct only after a real per-entry save`() {
        // This proves the MIGRATION step alone (both entries copy from the one legacy slot);
        // ProviderCredentialStoreTest / the real per-entry store is what proves two entries can
        // subsequently diverge once a user edits one of them independently (#273's actual fix).
        val plan = ProviderAccountMigration.legacyKeyCopyPlan(
            entryKindsMissingOwnKey = mapOf("groq-entry" to ProviderKind.OPENAI, "openrouter-entry" to ProviderKind.OPENAI),
            legacyKeysByKind = mapOf(ProviderKind.OPENAI to "sk-shared-legacy"),
        )

        assertEquals("sk-shared-legacy", plan["groq-entry"])
        assertEquals("sk-shared-legacy", plan["openrouter-entry"])
    }

    @Test fun `a kind with no legacy key contributes no writes`() {
        val plan = ProviderAccountMigration.legacyKeyCopyPlan(
            entryKindsMissingOwnKey = mapOf("entry-a" to ProviderKind.ANTHROPIC),
            legacyKeysByKind = mapOf(ProviderKind.ANTHROPIC to ""),
        )

        assertTrue(plan.isEmpty())
    }

    @Test fun `a kind missing entirely from legacyKeysByKind contributes no writes`() {
        val plan = ProviderAccountMigration.legacyKeyCopyPlan(
            entryKindsMissingOwnKey = mapOf("entry-a" to ProviderKind.GEMINI),
            legacyKeysByKind = emptyMap(),
        )

        assertTrue(plan.isEmpty())
    }

    @Test fun `an entry already excluded from the input (because it already has its own key) gets no plan entry`() {
        // The caller (runIfNeeded) only ever includes entries with no key of their own in
        // entryKindsMissingOwnKey -- this test documents that contract at the pure-function
        // level: an entry simply absent from the map can never appear in the output, so it is
        // impossible for this function to overwrite a key a user already set on one entry.
        val plan = ProviderAccountMigration.legacyKeyCopyPlan(
            entryKindsMissingOwnKey = mapOf("entry-b" to ProviderKind.OPENAI), // entry-a omitted: has its own key
            legacyKeysByKind = mapOf(ProviderKind.OPENAI to "sk-legacy"),
        )

        assertEquals(mapOf("entry-b" to "sk-legacy"), plan)
        assertTrue("entry-a" !in plan)
    }

    @Test fun `re-running the copy plan against an unchanged missing-key map is idempotent`() {
        val missing = mapOf("entry-a" to ProviderKind.OPENAI)
        val legacy = mapOf(ProviderKind.OPENAI to "sk-legacy")

        val first = ProviderAccountMigration.legacyKeyCopyPlan(missing, legacy)
        val second = ProviderAccountMigration.legacyKeyCopyPlan(missing, legacy)

        assertEquals(first, second)
    }

    @Test fun `an empty entryKindsMissingOwnKey produces an empty plan`() {
        val plan = ProviderAccountMigration.legacyKeyCopyPlan(emptyMap(), mapOf(ProviderKind.OPENAI to "sk-legacy"))
        assertTrue(plan.isEmpty())
    }
}
