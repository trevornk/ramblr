package com.trevornk.ramblr

/**
 * Connection details for an optional self-hosted OmniRoute-style gateway (see ADR-0001).
 *
 * The actual host is intentionally **not** committed to this public repo — it's a private,
 * VPN/LAN-only endpoint, and even a non-secret personal hostname doesn't belong in source
 * that anyone can clone. [BASE_URL] is instead sourced from [BuildConfig.OMNIROUTE_BASE_URL],
 * which `app/build.gradle.kts` populates from an `OMNIROUTE_BASE_URL` entry in `local.properties`
 * (gitignored, machine-local). Leave that entry unset and this feature is simply dormant: empty
 * [BASE_URL] means [isConfigured] is false, [CloudProviderActivity] hides "Add OmniRoute
 * provider" from the picker entirely, and any already-migrated OMNIROUTE step just fails through
 * the cleanup waterfall to the next configured step like any other unreachable host.
 *
 * To use this yourself, add to `local.properties`:
 * ```
 * OMNIROUTE_BASE_URL=https://your-gateway.example/v1
 * ```
 */
object OmniRoute {
    val BASE_URL: String = BuildConfig.OMNIROUTE_BASE_URL

    /** True once a real base URL has been supplied via `local.properties`; see class doc. */
    val isConfigured: Boolean get() = BASE_URL.isNotBlank()
}
