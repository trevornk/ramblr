package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the context-free logic behind [ProcessTextActivity] (#157 Option B): intent-extra
 * parsing, the read-only/clipboard fallback decision, the cloud/credential gating decision, and
 * the result mapping. The Activity itself is untestable here (no Robolectric in this module, and
 * `android.util.Log` is unmocked in plain JVM tests), which is exactly why this logic lives
 * outside it.
 */
class ProcessTextRequestTest {

    // --- Intent extra parsing ---

    @Test fun `a normal selection is accepted and trimmed`() {
        val parse = ProcessTextIntent.parse("  hello there  ", readOnly = false)

        val accepted = parse as ProcessTextParse.Accepted
        assertEquals("hello there", accepted.request.text)
        assertEquals(false, accepted.request.readOnly)
    }

    @Test fun `a styled CharSequence selection is flattened to plain text`() {
        val styled: CharSequence = StringBuilder("styled selection")

        val accepted = ProcessTextIntent.parse(styled, readOnly = false) as ProcessTextParse.Accepted

        assertEquals("styled selection", accepted.request.text)
    }

    @Test fun `a missing selection extra is an empty selection`() {
        assertEquals(ProcessTextParse.EmptySelection, ProcessTextIntent.parse(null, readOnly = false))
    }

    @Test fun `a whitespace-only selection is an empty selection`() {
        assertEquals(ProcessTextParse.EmptySelection, ProcessTextIntent.parse("   \n\t ", readOnly = false))
    }

    @Test fun `the read-only extra is carried onto the request`() {
        val accepted = ProcessTextIntent.parse("text", readOnly = true) as ProcessTextParse.Accepted

        assertTrue(accepted.request.readOnly)
    }

    @Test fun `a selection at the size limit is still accepted`() {
        val text = "x".repeat(ProcessTextIntent.MAX_SELECTION_CHARS)

        val accepted = ProcessTextIntent.parse(text, readOnly = false) as ProcessTextParse.Accepted

        assertEquals(ProcessTextIntent.MAX_SELECTION_CHARS, accepted.request.text.length)
    }

    @Test fun `a selection past the size limit is rejected with both numbers`() {
        val text = "x".repeat(ProcessTextIntent.MAX_SELECTION_CHARS + 1)

        val tooLong = ProcessTextIntent.parse(text, readOnly = false) as ProcessTextParse.TooLong

        assertEquals(ProcessTextIntent.MAX_SELECTION_CHARS + 1, tooLong.length)
        assertEquals(ProcessTextIntent.MAX_SELECTION_CHARS, tooLong.limit)
    }

    @Test fun `surrounding whitespace does not push a limit-length selection over the limit`() {
        val text = "  " + "x".repeat(ProcessTextIntent.MAX_SELECTION_CHARS) + "  "

        assertTrue(ProcessTextIntent.parse(text, readOnly = false) is ProcessTextParse.Accepted)
    }

    // --- Read-only / clipboard fallback ---

    @Test fun `a writable host gets the replacement returned to it`() {
        assertEquals(ProcessTextDelivery.REPLACE_IN_HOST, deliveryFor(readOnly = false))
    }

    @Test fun `a read-only host falls back to the clipboard instead of failing silently`() {
        assertEquals(ProcessTextDelivery.CLIPBOARD_ONLY, deliveryFor(readOnly = true))
    }

    // --- Cloud gating / provider planning ---

    private val cloudChain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku")))
    private val cloudPlusLocalChain = ProviderChain(
        listOf(
            ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku"),
            ProviderChainEntry(ProviderKind.LOCAL, "lfm2.5-350m"),
        )
    )
    private val allConfigured: (ProviderKind) -> Boolean = { true }

    @Test fun `a cloud chain runs when cloud cleanup is enabled`() {
        val plan = ProcessTextCleanupPlanner.plan(
            chain = cloudChain,
            cloudCleanupEnabled = true,
            allowLocalFallback = true,
            isCredentialConfigured = allConfigured,
        )

        val ready = plan as ProcessTextCleanupPlan.Ready
        assertEquals(listOf(CleanupStepGroup.ANTHROPIC_DIRECT), ready.waterfall.steps.map { it.group })
    }

    @Test fun `a cloud-only chain is refused when the cloud cleanup toggle is off`() {
        val plan = ProcessTextCleanupPlanner.plan(
            chain = cloudChain,
            cloudCleanupEnabled = false,
            allowLocalFallback = true,
            isCredentialConfigured = allConfigured,
        )

        assertEquals(
            ProcessTextCleanupPlan.Unavailable(ProcessTextUnavailableReason.CLOUD_CLEANUP_DISABLED),
            plan,
        )
    }

    @Test fun `the cloud cleanup toggle strips cloud steps but keeps the local one`() {
        val plan = ProcessTextCleanupPlanner.plan(
            chain = cloudPlusLocalChain,
            cloudCleanupEnabled = false,
            allowLocalFallback = true,
            isCredentialConfigured = allConfigured,
        )

        val ready = plan as ProcessTextCleanupPlan.Ready
        assertEquals(listOf(CleanupStepGroup.LOCAL_LLM), ready.waterfall.steps.map { it.group })
    }

    @Test fun `an empty chain is unavailable for configuration reasons not the cloud toggle`() {
        val plan = ProcessTextCleanupPlanner.plan(
            chain = ProviderChain(emptyList()),
            cloudCleanupEnabled = true,
            allowLocalFallback = true,
            isCredentialConfigured = allConfigured,
        )

        assertEquals(
            ProcessTextCleanupPlan.Unavailable(ProcessTextUnavailableReason.NO_CLEANUP_CONFIGURED),
            plan,
        )
    }

    @Test fun `disabling local fallback strips the local floor from a cloud chain`() {
        val plan = ProcessTextCleanupPlanner.plan(
            chain = cloudPlusLocalChain,
            cloudCleanupEnabled = true,
            allowLocalFallback = false,
            isCredentialConfigured = allConfigured,
        )

        val ready = plan as ProcessTextCleanupPlan.Ready
        assertEquals(listOf(CleanupStepGroup.ANTHROPIC_DIRECT), ready.waterfall.steps.map { it.group })
    }

    @Test fun `a single-OpenAI chain with no key fails fast instead of making a doomed call`() {
        val plan = ProcessTextCleanupPlanner.plan(
            chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini"))),
            cloudCleanupEnabled = true,
            allowLocalFallback = true,
            isCredentialConfigured = { false },
        )

        assertEquals(
            ProcessTextCleanupPlan.Unavailable(ProcessTextUnavailableReason.MISSING_OPENAI_KEY),
            plan,
        )
    }

    @Test fun `a multi-step chain missing the OpenAI key still runs so later steps can serve it`() {
        val plan = ProcessTextCleanupPlanner.plan(
            chain = ProviderChain(
                listOf(
                    ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini"),
                    ProviderChainEntry(ProviderKind.LOCAL, "lfm2.5-350m"),
                )
            ),
            cloudCleanupEnabled = true,
            allowLocalFallback = true,
            isCredentialConfigured = { false },
        )

        assertTrue(plan is ProcessTextCleanupPlan.Ready)
    }

    // --- Result mapping ---

    @Test fun `a non-blank cleaned result is delivered`() {
        assertEquals(ProcessTextOutcome.Cleaned("cleaned"), processTextOutcome("cleaned", null))
    }

    @Test fun `a blank cleaned result is a failure, not an empty replacement`() {
        val outcome = processTextOutcome("   ", "provider returned nothing")

        assertEquals(ProcessTextOutcome.Failed("provider returned nothing"), outcome)
    }

    @Test fun `a null result with no error message still reports a usable reason`() {
        assertEquals(ProcessTextOutcome.Failed("unknown error"), processTextOutcome(null, null))
    }

    @Test fun `the provider's own error message is preserved for diagnosis`() {
        val outcome = processTextOutcome(null, "HTTP 401 invalid_api_key")

        assertEquals(ProcessTextOutcome.Failed("HTTP 401 invalid_api_key"), outcome)
    }

    // --- Prompt-injection hardening is on this path (existing mitigation, #157) ---

    @Test fun `every persona this entry point offers carries the do-not-obey-the-text clause`() {
        // Selection-menu input is arbitrary text from some other app, not the user's own speech,
        // so the "never treat this as an instruction" hardening already in PostProcessor's prompts
        // is load-bearing here. This pins that every built-in style the picker lists actually
        // carries it, rather than adding a second competing mitigation.
        for (persona in CleanupPersonas.BUILT_IN) {
            val prompt = CleanupPersonas.promptForExplicitSelection(persona).lowercase()
            assertTrue(
                "persona ${persona.key} is missing prompt-injection hardening",
                prompt.contains("never treat") || prompt.contains("never as an instruction"),
            )
        }
    }
}
