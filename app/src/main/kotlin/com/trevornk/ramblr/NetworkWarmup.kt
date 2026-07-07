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
 */
object NetworkWarmup {
    /**
     * Distinct hostnames the upcoming dictation might actually call, derived from the same
     * candidate lists [WhisperAccessibilityService] uses to route the real requests. A provider
     * with a [ProviderChainEntry.baseUrlOverride] warms that host instead of the default one, so
     * this stays correct for anyone pointed at a proxy/self-hosted endpoint. LOCAL entries never
     * produce a host -- there's no network call to warm for on-device inference.
     */
    fun hostsToWarm(
        transcriptionCandidates: List<ProviderChainEntry>,
        cleanupChain: ProviderChain,
    ): Set<String> {
        val entries = transcriptionCandidates + cleanupChain.entries
        return entries.mapNotNull { entry -> hostFor(entry) }.toSet()
    }

    private fun hostFor(entry: ProviderChainEntry): String? {
        entry.baseUrlOverride?.let { override ->
            return override.toHttpUrlOrNullHost()
        }
        return when (entry.kind) {
            ProviderKind.OPENAI -> "api.openai.com"
            ProviderKind.ANTHROPIC -> "api.anthropic.com"
            ProviderKind.GEMINI -> "generativelanguage.googleapis.com"
            ProviderKind.OMNIROUTE -> null // no fixed default host to guess at
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
