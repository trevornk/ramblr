package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Unified credential storage for the provider-chain model: exactly one secret slot PER
 * [ProviderKind], not per feature and not per feature-provider pair. Replaced an earlier split
 * brain where the same logical "OpenAI key" concept was spread across a legacy transcription key
 * store and a separate cleanup-waterfall credential store.
 *
 * [ProviderKind.LOCAL] intentionally has no slot here -- on-device inference has nothing to
 * authenticate against.
 *
 * Cached [androidx.security.crypto.EncryptedSharedPreferences] via [SecurePrefsFactory], with a
 * "***xxxx" (last 4 chars) masking convention for display.
 *
 * This is the sole live credential source for every provider (OpenAI, Gemini, Anthropic,
 * OmniRoute); the legacy stores it superseded, and the one-time migration that seeded it from
 * them, have since been deleted as dead code -- migration ran once on the only real device and
 * is confirmed complete (see `provider_chain_migrated=true` in ramblr.xml).
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

    /** Deletes the stored credential for [kind] from the device (M10). No-op if none was stored. */
    fun clear(context: Context, kind: ProviderKind) {
        val key = prefKeyFor(kind) ?: return
        securePrefs(context).edit().remove(key).apply()
    }

    /** Masking convention: last 4 chars only, e.g. "***cdef". */
    fun maskForDisplay(value: String): String = when {
        value.isBlank() -> ""
        value.length > 4 -> "***${value.takeLast(4)}"
        else -> "***"
    }

    /** Cached, crash-loop-proof encrypted prefs -- see [SecurePrefsFactory] (#79). */
    private fun securePrefs(context: Context): SharedPreferences =
        SecurePrefsFactory.getOrCreate(context, SECURE_PREFS_NAME)
}
