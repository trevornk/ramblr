package com.trevornk.ramblr

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * End-to-end (real [Context], real encrypted prefs via Robolectric) coverage for #273/#274: two
 * same-kind chain entries with distinct per-entry credentials, [ProviderAccountMigration] running
 * against real [ProviderChainStore]/[ProviderCredentialStore] storage, and the sabotage-proof
 * scenario documented in the PR body (reverting to a per-kind lookup must make the "two distinct
 * keys" test fail).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderAccountMigrationEndToEndTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        prefs().edit().clear().apply()
        ProviderKind.values().forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
    }

    private fun prefs() = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    // --- The actual #273 bug, fixed: two same-kind entries, distinct keys ---

    @Test fun `two OPENAI-kind entries with different base URLs keep distinct keys (#273)`() {
        val groq = ProviderChainEntry(ProviderKind.OPENAI, "whisper-large-v3-turbo", baseUrlOverride = "https://api.groq.com/openai/v1")
        val openRouter = ProviderChainEntry(ProviderKind.OPENAI, "gpt-oss-120b", baseUrlOverride = "https://openrouter.ai/api/v1")
        ProviderChainStore.save(app, ProviderChain(listOf(groq, openRouter)))
        ProviderAccountMigration.runIfNeeded(app) // assigns real ids

        val chain = ProviderChainStore.load(app)
        val (groqWithId, openRouterWithId) = chain.entries[0] to chain.entries[1]
        assertNotEquals("", groqWithId.id)
        assertNotEquals(groqWithId.id, openRouterWithId.id)

        ProviderCredentialStore.set(app, groqWithId, "sk-groq-key")
        ProviderCredentialStore.set(app, openRouterWithId, "sk-openrouter-key")

        assertEquals("sk-groq-key", ProviderCredentialStore.get(app, groqWithId))
        assertEquals("sk-openrouter-key", ProviderCredentialStore.get(app, openRouterWithId))
        // The literal old bug: saving the second key must NOT have overwritten the first.
        assertNotEquals(
            ProviderCredentialStore.get(app, groqWithId),
            ProviderCredentialStore.get(app, openRouterWithId),
        )
    }

    /**
     * Sabotage proof (matches the PR body's manual check): if credential resolution regresses to
     * looking up by [ProviderKind] instead of by entry id, this test must fail. Simulated here by
     * calling the legacy per-kind accessor directly instead of the per-entry one -- exactly the
     * regression a revert of the #274 fix would reintroduce.
     */
    @Test fun `sabotage proof - a per-kind lookup cannot tell the two entries apart`() {
        val groq = ProviderChainEntry(ProviderKind.OPENAI, "m", id = "groq-id")
        val openRouter = ProviderChainEntry(ProviderKind.OPENAI, "m", id = "openrouter-id")
        ProviderCredentialStore.set(app, groq, "sk-groq-key")
        ProviderCredentialStore.set(app, openRouter, "sk-openrouter-key")

        // The real per-entry fix: these differ.
        assertNotEquals(ProviderCredentialStore.get(app, groq), ProviderCredentialStore.get(app, openRouter))

        // The old (broken) per-kind behavior, reproduced deliberately: both would resolve to
        // whatever the SHARED legacy slot for OPENAI holds, which is empty here (neither `set`
        // call above touched it) -- i.e. a per-kind lookup can't see either per-entry value at
        // all, let alone tell them apart. This is the exact failure mode #273 reported.
        assertEquals("", ProviderCredentialStore.getLegacyByKind(app, ProviderKind.OPENAI))
        assertEquals(
            ProviderCredentialStore.getLegacyByKind(app, groq.kind),
            ProviderCredentialStore.getLegacyByKind(app, openRouter.kind),
        )
    }

    // --- Migration copies the legacy key onto every entry of that kind ---

    @Test fun `migration copies the legacy per-kind key onto every existing entry of that kind`() {
        ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "sk-legacy-openai")
        val entryA = ProviderChainEntry(ProviderKind.OPENAI, "model-a")
        val entryB = ProviderChainEntry(ProviderKind.OPENAI, "model-b")
        ProviderChainStore.save(app, ProviderChain(listOf(entryA, entryB)))

        ProviderAccountMigration.runIfNeeded(app)

        val chain = ProviderChainStore.load(app)
        chain.entries.forEach { entry ->
            assertEquals("sk-legacy-openai", ProviderCredentialStore.get(app, entry))
        }
    }

    @Test fun `re-running migration is a no-op`() {
        ProviderCredentialStore.setLegacyByKind(app, ProviderKind.GEMINI, "sk-legacy-gemini")
        ProviderChainStore.save(app, ProviderChain(listOf(ProviderChainEntry(ProviderKind.GEMINI, "m"))))

        ProviderAccountMigration.runIfNeeded(app)
        val afterFirst = ProviderChainStore.load(app)
        val keyAfterFirst = ProviderCredentialStore.get(app, afterFirst.entries[0])

        ProviderAccountMigration.runIfNeeded(app)
        val afterSecond = ProviderChainStore.load(app)

        assertEquals(afterFirst, afterSecond)
        assertEquals(keyAfterFirst, ProviderCredentialStore.get(app, afterSecond.entries[0]))
    }

    @Test fun `removing one entry does not clear another entry's key`() {
        ProviderChainStore.save(
            app,
            ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "a"), ProviderChainEntry(ProviderKind.GEMINI, "b"))),
        )
        ProviderAccountMigration.runIfNeeded(app)
        val chain = ProviderChainStore.load(app)
        val (openaiEntry, geminiEntry) = chain.entries[0] to chain.entries[1]
        ProviderCredentialStore.set(app, openaiEntry, "sk-openai")
        ProviderCredentialStore.set(app, geminiEntry, "sk-gemini")

        // Remove the OpenAI entry from the chain (mirrors CloudProviderActivity.confirmRemoveEntry)
        // and clear only its own credential.
        ProviderCredentialStore.clear(app, openaiEntry)
        ProviderChainStore.save(app, ProviderChain(ProviderChainEditing.remove(chain.entries, 0)))

        assertFalse(ProviderCredentialStore.isConfigured(app, openaiEntry))
        assertTrue(ProviderCredentialStore.isConfigured(app, geminiEntry))
        assertEquals("sk-gemini", ProviderCredentialStore.get(app, geminiEntry))
    }

    @Test fun `legacy per-kind slots are untouched by migration`() {
        ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "sk-legacy-openai")
        ProviderChainStore.save(app, ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "m"))))

        ProviderAccountMigration.runIfNeeded(app)

        // The legacy slot is a deliberate recovery source (see ProviderCredentialStore's kdoc)
        // and must still read back exactly what it held before migration ran.
        assertEquals("sk-legacy-openai", ProviderCredentialStore.getLegacyByKind(app, ProviderKind.OPENAI))
    }

    @Test fun `a kind with no legacy key leaves every entry of that kind unconfigured after migration`() {
        ProviderChainStore.save(app, ProviderChain(listOf(ProviderChainEntry(ProviderKind.ANTHROPIC, "m"))))

        ProviderAccountMigration.runIfNeeded(app)

        val entry = ProviderChainStore.load(app).entries.single()
        assertFalse(ProviderCredentialStore.isConfigured(app, entry))
    }

    @Test fun `migration assigns ids to entries that have none, idempotently`() {
        ProviderChainStore.save(app, ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "m"))))

        ProviderAccountMigration.runIfNeeded(app)
        val idAfterFirst = ProviderChainStore.load(app).entries.single().id
        assertNotEquals("", idAfterFirst)

        ProviderAccountMigration.runIfNeeded(app)
        val idAfterSecond = ProviderChainStore.load(app).entries.single().id
        assertEquals(idAfterFirst, idAfterSecond)
    }

    /** Crash-safety: simulates a process death between id-assignment and key-copy by manually
     *  saving an id-assigned-but-not-yet-migrated chain, then confirming a subsequent
     *  [ProviderAccountMigration.runIfNeeded] still completes the key copy. */
    @Test fun `re-running migration after an interrupted first pass still completes the key copy`() {
        ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "sk-legacy-openai")
        // Simulate step 1 (ids assigned) having already run, with step 2 (key copy) NOT having
        // happened yet and the version gate NOT yet bumped -- exactly what a crash between the
        // two steps in runIfNeeded would leave on disk.
        val idAssignedEntry = ProviderChainEntry(ProviderKind.OPENAI, "m", id = "already-assigned-id")
        ProviderChainStore.save(app, ProviderChain(listOf(idAssignedEntry)))

        ProviderAccountMigration.runIfNeeded(app)

        val entry = ProviderChainStore.load(app).entries.single()
        assertEquals("already-assigned-id", entry.id) // unchanged, not reassigned
        assertEquals("sk-legacy-openai", ProviderCredentialStore.get(app, entry))
    }
}
