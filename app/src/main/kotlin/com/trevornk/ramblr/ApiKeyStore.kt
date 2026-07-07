package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores the OpenAI API key in Keystore-backed EncryptedSharedPreferences.
 * Transparently migrates any legacy plaintext value from the old
 * "ramblr" prefs file the first time the key is read, then clears
 * the plaintext copy.
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
