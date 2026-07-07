package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Unified credential storage for the provider-chain model (Phase 1 of the provider-chain
 * unification): exactly one secret slot PER [ProviderKind], not per feature and not per
 * feature-provider pair. This replaces the split brain of the legacy model, where the same
 * logical "OpenAI key" concept was spread across [ApiKeyStore] (transcription + legacy cleanup)
 * and [CleanupCredentialStore]'s OPENAI_DIRECT slot (a power user's separate waterfall key).
 *
 * [ProviderKind.LOCAL] intentionally has no slot here -- on-device inference has nothing to
 * authenticate against, mirroring today's LOCAL_LLM steps having no [CleanupCredentialSlot].
 *
 * Follows the exact same pattern as [CleanupCredentialStore]: cached
 * [androidx.security.crypto.EncryptedSharedPreferences] via [SecurePrefsFactory], and the same
 * masking convention (last 4 chars, "***xxxx").
 *
 * Deliberately does not delete [ApiKeyStore]/[CleanupCredentialStore]'s underlying encrypted
 * prefs files on disk (a user's already-stored secrets stay put, no silent data loss) -- but as
 * of the #95 Phase 3 restructure, no live code reads or writes either of those two stores
 * anymore. [ApiKeyStore] survives only as [ProviderChainMigration]'s one-time legacy input;
 * [CleanupCredentialStore]'s three slots are likewise migration-only. This store is populated by
 * [ProviderChainMigration] and is the sole live credential source for every provider going
 * forward.
 */
object ProviderCredentialStore {
    private const val SECURE_PREFS_NAME = "ramblr_provider_credentials"
    private const val KEY_OPENAI = "openai_key"
    private const val KEY_ANTHROPIC = "anthropic_key"
    private const val KEY_GEMINI = "gemini_key"
    private const val KEY_OMNIROUTE = "omniroute_key"

    /** Returns null for [ProviderKind.LOCAL], which has no credential slot. */
    internal fun prefKeyFor(kind: ProviderKind): String? = when (kind) {
        ProviderKind.OPENAI -> KEY_OPENAI
        ProviderKind.ANTHROPIC -> KEY_ANTHROPIC
        ProviderKind.GEMINI -> KEY_GEMINI
        ProviderKind.OMNIROUTE -> KEY_OMNIROUTE
        ProviderKind.LOCAL -> null
    }

    fun get(context: Context, kind: ProviderKind): String {
        val key = prefKeyFor(kind) ?: return ""
        return securePrefs(context).getString(key, "") ?: ""
    }

    fun set(context: Context, kind: ProviderKind, value: String) {
        val key = prefKeyFor(kind) ?: return
        securePrefs(context).edit().putString(key, value).apply()
    }

    fun isConfigured(context: Context, kind: ProviderKind): Boolean =
        get(context, kind).isNotBlank()

    /** Same masking convention as [CleanupCredentialStore.maskForDisplay]: last 4 chars only. */
    fun maskForDisplay(value: String): String = when {
        value.isBlank() -> ""
        value.length > 4 -> "***${value.takeLast(4)}"
        else -> "***"
    }

    /** Cached, crash-loop-proof encrypted prefs -- see [SecurePrefsFactory] (#79). */
    private fun securePrefs(context: Context): SharedPreferences =
        SecurePrefsFactory.getOrCreate(context, SECURE_PREFS_NAME)
}
