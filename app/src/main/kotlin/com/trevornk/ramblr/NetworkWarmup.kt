package com.trevornk.ramblr

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Pre-warms OkHttp's connection pool for whatever cloud hosts a dictation is actually going to
 * hit (#100 perceived-latency follow-up to the local-cleanup-floor fix). [NetworkClients.shared]
 * is a single shared [okhttp3.OkHttpClient], so a successful connection made here sits in its
 * pool and is reused by the real transcription/cleanup call moments later -- exactly the same
 * "pay the cost while the user is still talking" pattern [LocalCleanupModelHolder.warmUpAsync]
 * and [WhisperAccessibilityService.warmUpTranscribersIfTrimmed] already use for the on-device
 * model load path, just applied to DNS + TCP + TLS instead of GGUF mmap.
 *
 * Without this, a cold dictation's first cloud call pays full DNS resolution + TCP handshake +
 * TLS negotiation serially before a single byte of the real request goes out -- on a mobile
 * radio that's commonly 500ms-1.5s+, stacked directly onto the user-visible latency between
 * "stop talking" and "text appears", even though none of it is the transcription or cleanup
 * model actually working.
 *
 * [hostsToWarm] is the pure, unit-testable part: given the resolved transcription candidates and
 * effective cleanup chain for this dictation, return the distinct hostnames a real call could
 * hit. [warmUpAsync] does the actual (impure, Android-only) connection attempts.
 *
 * ## Credential gating (#168)
 *
 * A host is only warmed when a real call could actually reach it, which means the provider must
 * have a credential configured. Both real call paths already refuse to contact a keyless
 * provider -- [TranscriptionChain.precheck] returns SKIP for OPENAI/GEMINI without a credential,
 * and [CleanupWaterfallExecutor] fails the step with "No credential configured" before building
 * a request -- so warming a keyless host opened a connection that, by construction, nothing was
 * ever going to reuse.
 *
 * That was not merely a wasted handshake. F-Droid review of 1.0.24 (fdroiddata!42401) observed
 * every dictation opening a TLS connection to api.openai.com with cleanup off, Local mode, and
 * no API key set -- sending the user's IP and an api.openai.com SNI to a proprietary service the
 * user had explicitly opted out of. The optimization only ever paid off for users who configured
 * a key; gating on that costs those users nothing and makes the opted-out case silent.
 */
object NetworkWarmup {
    /**
     * Distinct hostnames the upcoming dictation might actually call, derived from the same
     * candidate lists [WhisperAccessibilityService] uses to route the real requests. A provider
     * with a [ProviderChainEntry.baseUrlOverride] warms that host instead of the default one, so
     * this stays correct for anyone pointed at a proxy/self-hosted endpoint. LOCAL entries never
     * produce a host -- there's no network call to warm for on-device inference.
     *
     * [hasCredential] is the caller's seam onto [ProviderCredentialStore]; an entry whose kind
     * has no credential contributes no host (#168). This applies to `baseUrlOverride` entries
     * too: a self-hosted proxy still carries the provider's Authorization header, so the real
     * call is skipped for exactly the same reason and its host must not be warmed either.
     */
    fun hostsToWarm(
        transcriptionCandidates: List<ProviderChainEntry>,
        cleanupChain: ProviderChain,
        hasCredential: (ProviderKind) -> Boolean,
    ): Set<String> {
        val entries = transcriptionCandidates + cleanupChain.entries
        return entries
            .filter { entry -> entry.kind == ProviderKind.LOCAL || hasCredential(entry.kind) }
            .mapNotNull { entry -> hostFor(entry) }
            .toSet()
    }

    private fun hostFor(entry: ProviderChainEntry): String? {
        entry.baseUrlOverride?.let { override ->
            return override.toHttpUrlOrNullHost()
        }
        return when (entry.kind) {
            ProviderKind.OPENAI -> "api.openai.com"
            ProviderKind.ANTHROPIC -> "api.anthropic.com"
            ProviderKind.GEMINI -> "generativelanguage.googleapis.com"
            // #110: unlike the other cloud providers there's no universal default host to guess
            // at -- OmniRoute is a self-hosted gateway -- but when a dev has configured one via
            // local.properties (see OmniRoute's kdoc), OmniRoute.BASE_URL is that real,
            // build-time-known host and should be warmed exactly like everyone else's.
            ProviderKind.OMNIROUTE -> if (OmniRoute.isConfigured) OmniRoute.BASE_URL.toHttpUrlOrNullHost() else null
            ProviderKind.LOCAL -> null
        }
    }

    private fun String.toHttpUrlOrNullHost(): String? =
        this.toHttpUrlOrNull()?.host

    /**
     * Fires a lightweight HEAD request at each of [hosts] on a background thread, ignoring the
     * response and any failure -- this only exists to make OkHttp resolve DNS and complete a TCP+
     * TLS handshake ahead of time; a 404/401 response is a totally successful warm-up (the
     * connection is what got reused, not the response body). Safe to call every time recording
     * starts: a host whose connection is already warm just gets a cheap extra HEAD, and OkHttp's
     * own connection pool timeout (5 idle minutes by default) already matches the cadence of
     * [LocalCleanupModelSlot.IDLE_UNLOAD_MS] closely enough that this doesn't need its own idle
     * tracking.
     */
    fun warmUpAsync(hosts: Set<String>) {
        if (hosts.isEmpty()) return
        val client = NetworkClients.shared
        hosts.forEach { host ->
            Thread {
                val t0 = System.currentTimeMillis()
                runCatching {
                    val request = Request.Builder().url("https://$host/").head().build()
                    client.newCall(request).execute().close()
                }.onSuccess {
                    android.util.Log.i("NetworkWarmup", "warm-up connect to $host succeeded in ${System.currentTimeMillis() - t0}ms")
                }.onFailure {
                    // Any outcome (connect refused, TLS ok but 404, DNS failure) is fine here --
                    // the goal was attempting the handshake, not getting a particular response.
                    android.util.Log.d("NetworkWarmup", "warm-up connect to $host failed (non-fatal) after ${System.currentTimeMillis() - t0}ms: ${it.message}")
                }
            }.apply { isDaemon = true }.start()
        }
    }
}
