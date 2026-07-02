package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the OpenAI API key in Keystore-backed EncryptedSharedPreferences.
 * Transparently migrates any legacy plaintext value from the old
 * "phonewhisper" prefs file the first time the key is read, then clears
 * the plaintext copy.
 */
object ApiKeyStore {
    private const val KEY_API_KEY = "api_key"
    private const val LEGACY_PREFS_NAME = "phonewhisper"
    private const val SECURE_PREFS_NAME = "phonewhisper_secure"

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

    private fun securePrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
