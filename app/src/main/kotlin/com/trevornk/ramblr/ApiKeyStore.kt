package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores the OpenAI API key in Keystore-backed EncryptedSharedPreferences.
 * Transparently migrates any legacy plaintext value from the old
 * "ramblr" prefs file the first time the key is read, then clears
 * the plaintext copy.
 *
 * Also mirrors every write into [ProviderCredentialStore]'s OPENAI slot (see [setApiKey]).
 * Without this, a key entered here (e.g. via the onboarding wizard's "Step 3/4: OpenAI API Key"
 * dialog, or Settings' shared cloud-key row) can silently never reach the provider chain: on a
 * fresh install [WhisperAccessibilityService.ensureProviderChainMigrated] runs
 * [ProviderChainMigration.migrate] the moment Accessibility is turned on -- Step 2, BEFORE the
 * user is ever asked for a key at Step 3/4 -- and that migration is a permanent one-shot flagged
 * by [ProviderChainMigration.isMigrated]. It reads whatever's in this store at that instant (still
 * blank), seeds nothing into [ProviderCredentialStore], and never runs again. The key then saves
 * successfully into this store a moment later, but [ProviderCredentialStore] -- what the actual
 * provider chain / [com.trevornk.ramblr.CloudProviderActivity] reads at runtime -- never learns
 * about it, so cloud transcription/cleanup silently falls through to the next configured provider
 * with no error. Mirroring on every write (both a real key and an explicit clear) keeps the two
 * stores from ever drifting again, regardless of onboarding step ordering.
 */
object ApiKeyStore {
    private const val KEY_API_KEY = "api_key"
    private const val LEGACY_PREFS_NAME = "ramblr"
    private const val SECURE_PREFS_NAME = "ramblr_secure"

    fun getApiKey(context: Context): String {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val securePrefs = securePrefs(context)
        return migrateLegacyKey(legacyPrefs, securePrefs) ?: securePrefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, apiKey: String) {
        securePrefs(context).edit().putString(KEY_API_KEY, apiKey).apply()
        ProviderCredentialStore.set(context, ProviderKind.OPENAI, apiKey)
    }

    fun maskForDisplay(apiKey: String): String = when {
        apiKey.isBlank() -> ""
        apiKey.length > 4 -> "sk-...${apiKey.takeLast(4)}"
        else -> "sk-...***"
    }

    /** Moves a legacy plaintext key into secure storage and wipes the plaintext copy. Returns the migrated value, if any. */
    internal fun migrateLegacyKey(legacyPrefs: SharedPreferences, securePrefs: SharedPreferences): String? {
        val legacyKey = legacyPrefs.getString(KEY_API_KEY, null)
        if (legacyKey.isNullOrBlank()) return null
        securePrefs.edit().putString(KEY_API_KEY, legacyKey).apply()
        legacyPrefs.edit().remove(KEY_API_KEY).apply()
        return legacyKey
    }

    /** Cached, crash-loop-proof encrypted prefs -- see [SecurePrefsFactory] (#79). */
    private fun securePrefs(context: Context): SharedPreferences =
        SecurePrefsFactory.getOrCreate(context, SECURE_PREFS_NAME)
}
