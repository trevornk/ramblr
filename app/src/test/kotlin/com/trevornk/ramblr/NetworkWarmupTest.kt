package com.trevornk.ramblr

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [NetworkWarmup.hostsToWarm], the pure part of the pre-warm fix (#100 perceived-latency
 * follow-up): given the resolved transcription candidates and effective cleanup chain for a
 * dictation, it must return exactly the distinct cloud hostnames a real call could hit -- no
 * more (that would waste a handshake on a host nothing will call, and #168 showed "waste" can
 * mean "contact a proprietary service the user opted out of"), no less (that would leave the
 * real call to pay full DNS+TLS cold).
 */
class NetworkWarmupTest {
    private fun entry(kind: ProviderKind, model: String = "m", baseUrlOverride: String? = null) =
        ProviderChainEntry(kind, model, baseUrlOverride)

    /** Default for the pre-#168 cases: every provider has a key, so gating changes nothing. */
    private val allKeysPresent: (ProviderKind) -> Boolean = { true }

    @Test fun `maps each cloud provider kind to its default host`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = listOf(entry(ProviderKind.OPENAI)),
            cleanupChain = ProviderChain(listOf(entry(ProviderKind.ANTHROPIC), entry(ProviderKind.GEMINI))),
            hasCredential = allKeysPresent,
        )
        assertEquals(
            setOf("api.openai.com", "api.anthropic.com", "generativelanguage.googleapis.com"),
            hosts,
        )
    }

    @Test fun `LOCAL entries contribute no host`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = listOf(entry(ProviderKind.LOCAL)),
            cleanupChain = ProviderChain(listOf(entry(ProviderKind.LOCAL))),
            hasCredential = allKeysPresent,
        )
        assertEquals(emptySet<String>(), hosts)
    }

    @Test fun `duplicate hosts across transcription and cleanup are deduped`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = listOf(entry(ProviderKind.OPENAI)),
            cleanupChain = ProviderChain(listOf(entry(ProviderKind.OPENAI), entry(ProviderKind.OPENAI, "other-model"))),
            hasCredential = allKeysPresent,
        )
        assertEquals(setOf("api.openai.com"), hosts)
    }

    @Test fun `baseUrlOverride wins over the provider's default host`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = emptyList(),
            cleanupChain = ProviderChain(listOf(entry(ProviderKind.OPENAI, baseUrlOverride = "https://my-proxy.example.com/v1"))),
            hasCredential = allKeysPresent,
        )
        assertEquals(setOf("my-proxy.example.com"), hosts)
    }

    @Test fun `empty chains warm nothing`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = emptyList(),
            cleanupChain = ProviderChain(emptyList()),
            hasCredential = allKeysPresent,
        )
        assertEquals(emptySet<String>(), hosts)
    }

    @Test fun `OMNIROUTE warms OmniRoute BASE_URL's host when configured, otherwise contributes nothing`() {
        // Regression test for #110: OMNIROUTE used to unconditionally return null here ("no fixed
        // default host to guess at"), even though OmniRoute.BASE_URL (from local.properties via
        // BuildConfig, see OmniRoute.kt) is a real, build-time-known host once a dev configures
        // one. This repo is public and CI never sets OMNIROUTE_BASE_URL, so OmniRoute.isConfigured
        // is false in this test run -- mirroring OmniRouteTest, this pins the contract against
        // OmniRoute.isConfigured itself rather than a hardcoded assumption about its value, so the
        // same test is correct here and for any dev with a real local.properties override.
        //
        // NOTE: because isConfigured is false on CI, this case alone asserts emptySet() and passes
        // against the unfixed `-> null` too -- it cannot catch a regression in the environment that
        // actually gates merges. The override case below is the one with real teeth on CI; keep
        // both, since only this one pins the BuildConfig-sourced branch a configured dev hits.
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = emptyList(),
            cleanupChain = ProviderChain(listOf(entry(ProviderKind.OMNIROUTE))),
            hasCredential = allKeysPresent,
        )
        val expected = if (OmniRoute.isConfigured) {
            setOfNotNull(OmniRoute.BASE_URL.toHttpUrlOrNull()?.host)
        } else {
            emptySet()
        }
        assertEquals(expected, hosts)
    }

    // ---- #168: credential gating ----

    /**
     * The exact scenario F-Droid review reproduced on a Redmi Note 8T against 1.0.24
     * (fdroiddata!42401): Local mode, cleanup off, no API key -- and yet every dictation opened a
     * TLS connection to api.openai.com. The chain still carries its OPENAI entry (capability
     * filtering keeps it; only a credential check removes it), so this is the shape that leaked.
     */
    @Test fun `no host is warmed for a provider with no credential configured`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = listOf(entry(ProviderKind.OPENAI), entry(ProviderKind.LOCAL)),
            cleanupChain = ProviderChain(listOf(entry(ProviderKind.LOCAL))),
            hasCredential = { false },
        )
        assertEquals(emptySet<String>(), hosts)
    }

    /** #100 must not regress: a user who HAS configured a key still gets the pre-warm. */
    @Test fun `a provider with a credential is still warmed`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = listOf(entry(ProviderKind.OPENAI)),
            cleanupChain = ProviderChain(emptyList()),
            hasCredential = { it == ProviderKind.OPENAI },
        )
        assertEquals(setOf("api.openai.com"), hosts)
    }

    /** Gating is per-kind, not all-or-nothing: the keyed provider warms, the keyless one doesn't. */
    @Test fun `only the credentialed provider is warmed in a mixed chain`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = listOf(entry(ProviderKind.OPENAI)),
            cleanupChain = ProviderChain(listOf(entry(ProviderKind.GEMINI), entry(ProviderKind.ANTHROPIC))),
            hasCredential = { it == ProviderKind.GEMINI },
        )
        assertEquals(setOf("generativelanguage.googleapis.com"), hosts)
    }

    /**
     * A self-hosted proxy still carries the provider's Authorization header, so the real call is
     * skipped without a key for exactly the same reason -- the override host must not be warmed
     * either. Without this, pointing at a proxy would reopen the leak through a different host.
     */
    @Test fun `baseUrlOverride does not bypass the credential gate`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = emptyList(),
            cleanupChain = ProviderChain(listOf(entry(ProviderKind.OPENAI, baseUrlOverride = "https://my-proxy.example.com/v1"))),
            hasCredential = { false },
        )
        assertEquals(emptySet<String>(), hosts)
    }

    /** LOCAL has no credential slot at all, so it must never be filtered out by the gate. */
    @Test fun `LOCAL is unaffected by the credential gate`() {
        val hosts = NetworkWarmup.hostsToWarm(
            transcriptionCandidates = listOf(entry(ProviderKind.LOCAL), entry(ProviderKind.OPENAI)),
            cleanupChain = ProviderChain(listOf(entry(ProviderKind.LOCAL))),
            hasCredential = { false },
        )
        assertEquals(emptySet<String>(), hosts)
    }
}
