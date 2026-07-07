package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Which network host/credential a waterfall step authenticates against. See ADR-0001
 * (docs/adr/0001-cleanup-waterfall.md). OMNIROUTE covers three model sub-steps (Claude,
 * OpenAI/Codex, Gemini) behind one shared consumer key and fixed base URL; OPENAI_DIRECT and
 * ANTHROPIC_DIRECT are the pay-per-token fallbacks used when OmniRoute (home LAN/VPN only) is
 * unreachable.
 */
enum class CleanupCredentialSlot { OMNIROUTE, OPENAI_DIRECT, ANTHROPIC_DIRECT, GEMINI_DIRECT }

/**
 * Stores the three cleanup-waterfall secrets (OmniRoute consumer key, direct-OpenAI key,
 * direct-Anthropic key) in the same Keystore-backed EncryptedSharedPreferences pattern as
 * [ApiKeyStore] (see #8). Deliberately a separate prefs file/object from [ApiKeyStore]: the
 * existing OpenAI key there is used for cloud transcription and the legacy single-step cleanup
 * config, and cleanup's direct-OpenAI waterfall step gets its own key so the user can use a
 * different key/quota for cleanup than transcription (see ADR-0001).
 */
object CleanupCredentialStore {
    private const val SECURE_PREFS_NAME = "ramblr_cleanup_credentials"
    private const val KEY_OMNIROUTE = "omniroute_key"
    private const val KEY_OPENAI_DIRECT = "openai_direct_key"
    private const val KEY_ANTHROPIC_DIRECT = "anthropic_direct_key"
    private const val KEY_GEMINI_DIRECT = "gemini_direct_key"

    internal fun prefKeyFor(slot: CleanupCredentialSlot): String = when (slot) {
        CleanupCredentialSlot.OMNIROUTE -> KEY_OMNIROUTE
        CleanupCredentialSlot.OPENAI_DIRECT -> KEY_OPENAI_DIRECT
        CleanupCredentialSlot.ANTHROPIC_DIRECT -> KEY_ANTHROPIC_DIRECT
        CleanupCredentialSlot.GEMINI_DIRECT -> KEY_GEMINI_DIRECT
    }

    fun get(context: Context, slot: CleanupCredentialSlot): String =
        securePrefs(context).getString(prefKeyFor(slot), "") ?: ""

    fun set(context: Context, slot: CleanupCredentialSlot, value: String) {
        securePrefs(context).edit().putString(prefKeyFor(slot), value).apply()
    }

    fun isConfigured(context: Context, slot: CleanupCredentialSlot): Boolean =
        get(context, slot).isNotBlank()

    /** Same masking convention as [ApiKeyStore.maskForDisplay]: last 4 chars only. */
    fun maskForDisplay(value: String): String = when {
        value.isBlank() -> ""
        value.length > 4 -> "***${value.takeLast(4)}"
        else -> "***"
    }

    /** Cached, crash-loop-proof encrypted prefs -- see [SecurePrefsFactory] (#79). */
    private fun securePrefs(context: Context): SharedPreferences =
        SecurePrefsFactory.getOrCreate(context, SECURE_PREFS_NAME)
}
