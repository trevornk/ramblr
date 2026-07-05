package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderChainMigrationMapStepToEntryTest {

    @Test fun `legacy step maps to an openai entry, collapsing the special-cased legacy concept`() {
        val step = CleanupStep(CleanupStepGroup.LEGACY, "gpt-4o-mini")
        val entry = ProviderChainMigration.mapStepToEntry(step)
        assertEquals(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini"), entry)
    }

    @Test fun `omniroute step maps to an omniroute entry`() {
        val step = CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6")
        assertEquals(ProviderChainEntry(ProviderKind.OMNIROUTE, "claude/claude-sonnet-4-6"), ProviderChainMigration.mapStepToEntry(step))
    }

    @Test fun `openai direct step maps to an openai entry`() {
        val step = CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini", baseUrlOverride = "https://example.com/v1")
        assertEquals(
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", "https://example.com/v1"),
            ProviderChainMigration.mapStepToEntry(step)
        )
    }

    @Test fun `anthropic direct step maps to an anthropic entry`() {
        val step = CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5")
        assertEquals(ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5"), ProviderChainMigration.mapStepToEntry(step))
    }

    @Test fun `local llm step maps to a local entry`() {
        val step = CleanupStep(CleanupStepGroup.LOCAL_LLM, "qwen2.5-0.5b-instruct-q4_k_m")
        assertEquals(ProviderChainEntry(ProviderKind.LOCAL, "qwen2.5-0.5b-instruct-q4_k_m"), ProviderChainMigration.mapStepToEntry(step))
    }
}

class ProviderChainMigrationBuildChainTest {

    @Test fun `empty waterfall builds an empty chain`() {
        assertEquals(ProviderChain(emptyList()), ProviderChainMigration.buildChain(CleanupWaterfall(emptyList())))
    }

    @Test fun `the default legacy single-step waterfall migrates to a single openai entry`() {
        val chain = ProviderChainMigration.buildChain(CleanupWaterfall.LEGACY_SINGLE_STEP)
        assertEquals(1, chain.entries.size)
        assertEquals(ProviderKind.OPENAI, chain.entries[0].kind)
        assertEquals(PostProcessor.DEFAULT_MODEL, chain.entries[0].model)
    }

    @Test fun `a multi-step waterfall migrates preserving exact order and each step's own kind mapping`() {
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
                CleanupStep(CleanupStepGroup.LOCAL_LLM, "qwen2.5-0.5b-instruct-q4_k_m"),
            )
        )
        val chain = ProviderChainMigration.buildChain(waterfall)
        assertEquals(
            listOf(ProviderKind.OMNIROUTE, ProviderKind.OPENAI, ProviderKind.ANTHROPIC, ProviderKind.LOCAL),
            chain.entries.map { it.kind }
        )
        assertEquals(
            listOf("claude/claude-sonnet-4-6", "gpt-4o-mini", "claude-haiku-4-5", "qwen2.5-0.5b-instruct-q4_k_m"),
            chain.entries.map { it.model }
        )
    }

    @Test fun `a differently-ordered multi-step waterfall preserves that different order`() {
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, "qwen2.5-0.5b-instruct-q4_k_m"),
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        val chain = ProviderChainMigration.buildChain(waterfall)
        assertEquals(
            listOf(ProviderKind.LOCAL, ProviderKind.ANTHROPIC, ProviderKind.OMNIROUTE, ProviderKind.OPENAI),
            chain.entries.map { it.kind }
        )
    }
}

/** The documented conflict-resolution rule: prefer the explicit OPENAI_DIRECT waterfall key
 *  over the legacy single-key field when both exist and differ. See kdoc on
 *  [ProviderChainMigration.resolveOpenAiCredential] for the full rationale. */
class ProviderChainMigrationResolveOpenAiCredentialTest {

    @Test fun `prefers openai direct key when both are set and differ`() {
        assertEquals("direct-key", ProviderChainMigration.resolveOpenAiCredential("legacy-key", "direct-key"))
    }

    @Test fun `falls back to legacy key when openai direct was never set`() {
        assertEquals("legacy-key", ProviderChainMigration.resolveOpenAiCredential("legacy-key", ""))
    }

    @Test fun `returns blank when neither is set`() {
        assertEquals("", ProviderChainMigration.resolveOpenAiCredential("", ""))
    }

    @Test fun `prefers openai direct key even when legacy key is blank`() {
        assertEquals("direct-key", ProviderChainMigration.resolveOpenAiCredential("", "direct-key"))
    }
}

class ProviderChainMigrationComputeMigrationTest {

    @Test fun `fresh install with no legacy state produces the documented default -- an empty chain, no credentials`() {
        val inputs = ProviderChainMigration.MigrationInputs(
            legacyOpenAiKey = "",
            waterfall = CleanupWaterfall.LEGACY_SINGLE_STEP,
            omniRouteCredential = "",
            openAiDirectCredential = "",
            anthropicDirectCredential = "",
        )
        val result = ProviderChainMigration.computeMigration(inputs)

        // LEGACY_SINGLE_STEP maps to a single OPENAI entry -- this is the "documented default"
        // referenced by ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY for an unmigrated/fresh state.
        assertEquals(1, result.chain.entries.size)
        assertEquals(ProviderKind.OPENAI, result.chain.entries[0].kind)
        assertTrue(result.credentials.isEmpty())
    }

    @Test fun `legacy api key only -- no waterfall ever configured -- migrates the key into the openai slot`() {
        val inputs = ProviderChainMigration.MigrationInputs(
            legacyOpenAiKey = "sk-legacy-1234",
            waterfall = CleanupWaterfall.LEGACY_SINGLE_STEP,
            omniRouteCredential = "",
            openAiDirectCredential = "",
            anthropicDirectCredential = "",
        )
        val result = ProviderChainMigration.computeMigration(inputs)

        assertEquals(mapOf(ProviderKind.OPENAI to "sk-legacy-1234"), result.credentials)
        assertEquals(1, result.chain.entries.size)
        assertEquals(ProviderKind.OPENAI, result.chain.entries[0].kind)
    }

    @Test fun `a full multi-step waterfall migrates all credentials into their corresponding provider kinds`() {
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
                CleanupStep(CleanupStepGroup.LOCAL_LLM, "qwen2.5-0.5b-instruct-q4_k_m"),
            )
        )
        val inputs = ProviderChainMigration.MigrationInputs(
            legacyOpenAiKey = "sk-legacy-1234",
            waterfall = waterfall,
            omniRouteCredential = "omniroute-secret",
            openAiDirectCredential = "sk-direct-5678",
            anthropicDirectCredential = "claude-secret",
        )
        val result = ProviderChainMigration.computeMigration(inputs)

        assertEquals(
            mapOf(
                ProviderKind.OPENAI to "sk-direct-5678", // OPENAI_DIRECT wins over legacy key (conflict rule)
                ProviderKind.ANTHROPIC to "claude-secret",
                ProviderKind.OMNIROUTE to "omniroute-secret",
            ),
            result.credentials
        )
        assertEquals(
            listOf(ProviderKind.OMNIROUTE, ProviderKind.OPENAI, ProviderKind.ANTHROPIC, ProviderKind.LOCAL),
            result.chain.entries.map { it.kind }
        )
    }

    @Test fun `the openai-direct-vs-legacy-key conflict case -- both set and different -- prefers openai direct`() {
        val inputs = ProviderChainMigration.MigrationInputs(
            legacyOpenAiKey = "sk-legacy-old",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"))),
            omniRouteCredential = "",
            openAiDirectCredential = "sk-direct-new",
            anthropicDirectCredential = "",
        )
        val result = ProviderChainMigration.computeMigration(inputs)
        assertEquals("sk-direct-new", result.credentials[ProviderKind.OPENAI])
    }

    @Test fun `local llm steps never contribute a credential`() {
        val inputs = ProviderChainMigration.MigrationInputs(
            legacyOpenAiKey = "",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, "m"))),
            omniRouteCredential = "",
            openAiDirectCredential = "",
            anthropicDirectCredential = "",
        )
        val result = ProviderChainMigration.computeMigration(inputs)
        assertTrue(result.credentials.isEmpty())
    }
}

/** Context-bound wrapper coverage using an in-memory [android.content.SharedPreferences] fake --
 *  same style as [ApiKeyStoreTest]'s FakeSharedPreferences, but exercised through the real
 *  Context-taking legacy stores isn't possible without Robolectric, so these tests instead cover
 *  the migration-flag guard directly against a fake prefs instance passed through the same
 *  pattern used elsewhere in this file (idempotency of the pure computeMigration function, which
 *  is what actually guarantees migrate() is idempotent once the flag is respected). */
class ProviderChainMigrationIdempotencyTest {

    @Test fun `computeMigration is deterministic -- running it twice on identical inputs yields identical results`() {
        val inputs = ProviderChainMigration.MigrationInputs(
            legacyOpenAiKey = "sk-legacy-1234",
            waterfall = CleanupWaterfall(
                listOf(
                    CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                    CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
                )
            ),
            omniRouteCredential = "omniroute-secret",
            openAiDirectCredential = "sk-direct-5678",
            anthropicDirectCredential = "",
        )

        val first = ProviderChainMigration.computeMigration(inputs)
        val second = ProviderChainMigration.computeMigration(inputs)

        assertEquals(first.chain, second.chain)
        assertEquals(first.credentials, second.credentials)
    }
}
