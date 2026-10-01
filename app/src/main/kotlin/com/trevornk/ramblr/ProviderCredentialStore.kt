package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Credential storage for the provider-chain model (#274 provider-accounts rework).
 *
 * Originally this had exactly one secret slot PER [ProviderKind] -- correct only while a kind
 * identified exactly one physical service. Once [ProviderChainEntry.baseUrlOverride] let an
 * OPENAI-kind entry point at a third-party OpenAI-compatible host (Groq, OpenRouter, ...), a kind
 * no longer identified a credential: two such entries shared one slot, and saving the second key
 * silently overwrote the first (#273).
 *
 * Credentials are now keyed by [ProviderChainEntry.id] -- see [get]/[set]/[isConfigured]/[clear]
 * below, all entry-based. The original per-kind slots ([getLegacyByKind] etc.) are kept
 * unmodified as a MIGRATION SOURCE ONLY: [ProviderAccountMigration] copies a legacy slot's value
 * onto every existing entry of that kind the first time it runs, and this release deliberately
 * does not delete the legacy slots afterwards (a real device holds real keys; the safest failure
 * mode if a migration edge case is ever found is "the old key is still sitting in the old slot,
 * recoverable"). A follow-up release can clean them up once that migration has run in the field.
 *
 * [ProviderKind.LOCAL] intentionally has no slot -- on-device inference has nothing to
 * authenticate against.
 *
 * Cached [androidx.security.crypto.EncryptedSharedPreferences] via [SecurePrefsFactory], with a
 * "***xxxx" (last 4 chars) masking convention for display.
 */
object ProviderCredentialStore {
    private const val SECURE_PREFS_NAME = "ramblr_provider_credentials"
    private const val KEY_OPENAI = "openai_key"
    private const val KEY_ANTHROPIC = "anthropic_key"
    private const val KEY_GEMINI = "gemini_key"
    private const val KEY_OMNIROUTE = "omniroute_key"
    private const val ENTRY_KEY_PREFIX = "entry_"
    private const val ENTRY_KEY_SUFFIX = "_key"

    /** Legacy per-kind slot name. Returns null for [ProviderKind.LOCAL], which has no credential
     *  slot. Kept as the migration source (see class kdoc) -- not a live per-entry lookup. */
    internal fun legacyPrefKeyFor(kind: ProviderKind): String? = when (kind) {
        ProviderKind.OPENAI -> KEY_OPENAI
        ProviderKind.ANTHROPIC -> KEY_ANTHROPIC
        ProviderKind.GEMINI -> KEY_GEMINI
        ProviderKind.OMNIROUTE -> KEY_OMNIROUTE
        ProviderKind.LOCAL -> null
    }

    /** Per-entry slot name for [entryId]. Null for a blank id (an entry that has not been through
     *  [ProviderAccountMigration] yet -- should not happen once migration has run at startup, but
     *  every entry-based accessor below fails closed to "" / no-op rather than crash or silently
     *  hit the wrong entry's slot if it somehow does). */
    internal fun entryPrefKeyFor(entryId: String): String? =
        entryId.takeIf { it.isNotBlank() }?.let { "$ENTRY_KEY_PREFIX${it}$ENTRY_KEY_SUFFIX" }

    // --- Per-entry API (#274): the live credential source for every provider chain entry. ---

    fun get(context: Context, entryId: String): String {
        val key = entryPrefKeyFor(entryId) ?: return ""
        return securePrefs(context).getString(key, "") ?: ""
    }

    fun get(context: Context, entry: ProviderChainEntry): String = get(context, entry.id)

    fun set(context: Context, entryId: String, value: String) {
        val key = entryPrefKeyFor(entryId) ?: return
        securePrefs(context).edit().putString(key, value).apply()
    }

    fun set(context: Context, entry: ProviderChainEntry, value: String) = set(context, entry.id, value)

    fun isConfigured(context: Context, entryId: String): Boolean = get(context, entryId).isNotBlank()

    fun isConfigured(context: Context, entry: ProviderChainEntry): Boolean = isConfigured(context, entry.id)

    /** Deletes the stored credential for [entryId]. No-op if none was stored, or if [entryId] is
     *  blank. Removing a chain entry now clears its credential too (#274) -- previously a removed
     *  entry's per-kind key was deliberately kept "in case you add it back", which is no longer
     *  meaningful once the slot belongs to one entry's id rather than to the whole kind. */
    fun clear(context: Context, entryId: String) {
        val key = entryPrefKeyFor(entryId) ?: return
        securePrefs(context).edit().remove(key).apply()
    }

    fun clear(context: Context, entry: ProviderChainEntry) = clear(context, entry.id)

    // --- Blank-id / kind-fallback helpers (#274) ---
    //
    // A [ProviderChainEntry] with a blank [ProviderChainEntry.id] is one that has never been
    // through [ProviderAccountMigration] -- notably [ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY]
    // (never persisted, so it never gets a migration pass) and any entry onboarding seeds via
    // [MainActivity]'s quick-setup flow before the next migration run assigns it a real id. For
    // these, the per-entry slot (keyed by id) cannot exist yet, so the correct answer for "is
    // this configured" / "what's the value" falls back to the legacy per-kind slot -- the exact
    // place a value seeded through that flow (or a device that predates #274 entirely) actually
    // lives until migration catches up. For an entry that already has a real id, these are
    // identical to the plain per-entry accessors above -- the legacy fallback never masks a
    // genuinely-blank per-entry credential once an id exists.

    fun isConfiguredOrLegacy(context: Context, entry: ProviderChainEntry): Boolean =
        if (entry.id.isNotBlank()) isConfigured(context, entry) else isConfiguredLegacyByKind(context, entry.kind)

    fun getOrLegacy(context: Context, entry: ProviderChainEntry): String =
        if (entry.id.isNotBlank()) get(context, entry) else getLegacyByKind(context, entry.kind)

    /** Searches [chain] for the first entry of [kind] with a configured credential (per-entry,
     *  falling back to legacy for a blank-id entry), or the bare legacy slot for [kind] if no
     *  entry has one -- used by call sites that only know a [kind], not a specific entry (e.g.
     *  onboarding readiness checks, [CloudLiveWiring]'s single-purpose Gemini lookup). */
    fun getForKind(context: Context, chain: ProviderChain, kind: ProviderKind): String =
        chain.entries.firstOrNull { it.kind == kind && isConfiguredOrLegacy(context, it) }
            ?.let { getOrLegacy(context, it) }
            ?: getLegacyByKind(context, kind)

    fun isConfiguredForKind(context: Context, chain: ProviderChain, kind: ProviderKind): Boolean =
        getForKind(context, chain, kind).isNotBlank()

    // --- Legacy per-kind API: migration source only (see class kdoc). Not for new call sites. ---

    fun getLegacyByKind(context: Context, kind: ProviderKind): String {
        val key = legacyPrefKeyFor(kind) ?: return ""
        return securePrefs(context).getString(key, "") ?: ""
    }

    fun setLegacyByKind(context: Context, kind: ProviderKind, value: String) {
        val key = legacyPrefKeyFor(kind) ?: return
        securePrefs(context).edit().putString(key, value).apply()
    }

    fun isConfiguredLegacyByKind(context: Context, kind: ProviderKind): Boolean =
        getLegacyByKind(context, kind).isNotBlank()

    fun clearLegacyByKind(context: Context, kind: ProviderKind) {
        val key = legacyPrefKeyFor(kind) ?: return
        securePrefs(context).edit().remove(key).apply()
    }

    /** Masking convention: last 4 chars only, e.g. "***cdef". */
    fun maskForDisplay(value: String): String = when {
        value.isBlank() -> ""
        value.length > 4 -> "***${value.takeLast(4)}"
        else -> "***"
    }

    /** Cached, crash-loop-proof encrypted prefs -- see [SecurePrefsFactory] (#79). */
    internal fun securePrefs(context: Context): SharedPreferences =
        SecurePrefsFactory.getOrCreate(context, SECURE_PREFS_NAME)
}
